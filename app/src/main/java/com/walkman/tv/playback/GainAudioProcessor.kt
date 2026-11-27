package com.walkman.tv.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Media3 [androidx.media3.common.audio.AudioProcessor] that adds a fixed built-in gain
 * (~+6 dB) to the PCM stream.
 *
 * Curve (2026-11-26, 替代原来的全程 tanh 软限幅)：线性增益 + 只在接近满幅处才接管的
 * 软顶 —— 输入 |x·gain| ≤ 0.85 时输出就是 x·gain（一点压缩、一点谐波都没有），超过
 * 0.85 才用 tanh 平滑地收到 ±1.0，满幅永不削波。
 *
 * 为什么换掉全程 tanh：tanh 是随电平变的增益，不是固定增益。实测（Python，1 kHz 正弦，
 * 与本文件公式逐项对过）——
 *   旧曲线 tanh(1.9953x)/tanh(1.9953)：峰值 0.1 时有效增益 +6.21 dB、0.3 时 +5.36 dB、
 *   0.5 时 +3.97 dB、满幅时 0 dB；THD 0.3 峰值 2.7%、0.5 峰值 6.7%、满幅 17.3%；
 *   一首母带歌（峰值 0.89、RMS 0.317）RMS 只 +3.9 dB，动态范围 9.0 dB 被压到 5.9 dB。
 *   也就是说同一首歌里安静段落拿满 +6 dB、响的段落增益自己掉下来还带失真——这就是
 *   Yang 报的“同一首歌里忽大忽小、一会儿好听一会儿发闷/发糊”，不需要任何开关状态
 *   来回切就能出现；代码里除音频焦点（已关）外也没有别的增益重置路径（播放器每进程
 *   只建一次、开关只响应用户点按、offload 默认关、没有 setVolume 调用）。
 *   新曲线：峰值 ≤0.43 的输入 THD 0%、有效增益恒定 +6.00 dB；0.5 峰值 THD 1.3%
 *   （旧 6.7%）；同一首母带歌 RMS +4.75 dB。满幅附近的瞬态峰仍要限幅（+6 dB 推
 *   -1 dBFS 的母带，峰顶不做处理物理上就不可能不超满幅），但限幅只发生在瞬态峰顶，
 *   不再压整段响的段落。
 * 注意 tanh 本身不吃高频（各频段能量比实测都在 2.4–3.0 倍之间，没有频段被切掉）：
 * “发闷（高频没了）”另有来源——解析到的实际档位低于请求档位时角标仍显示请求档位，
 * 见 PlaybackController 的探头校正 + CrashDiagnostics 的音质记录。
 *
 * Sits in the same [androidx.media3.exoplayer.audio.DefaultAudioSink] processor chain as
 * [AudioLevelProcessor], *after* it — so the waveform meter still measures the pre-gain
 * level. It touches no audio session, so it cannot conflict with [EqualizerManager]
 * (system Equalizer bound to ExoPlayer's audio session); the two compose independently.
 *
 * Like every processor in the chain it is bypassed entirely when ExoPlayer chooses audio
 * offload (DSP / HDMI passthrough) — the gain then simply doesn't apply. Offload defaults
 * to off (see Settings), so the gain applies for normal playback.
 *
 * Formats: PCM 16/24/32-bit int and PCM float. Anything else throws
 * [UnhandledAudioFormatException] and the sink skips us — playback continues ungained
 * rather than failing. (24/32-bit int were added 2026-11-26: the old code only accepted
 * 16-bit and float, so a decoder handing the sink 24-bit PCM silently bypassed the gain
 * — one of the "有时候有、有时候没有" holes.)
 */
class GainAudioProcessor : BaseAudioProcessor() {

    /** +6 dB ≈ ×1.995. Plain var now: the gain is a fixed multiplier — the old
     *  tanh-normalisation factor is gone with the old curve (see [limitGain]). */
    var gain: Float = GAIN_DB_6

    /** Off ⇒ pure pass-through (bytes copied unchanged). */
    @Volatile var enabled: Boolean = true

    /** 诊断用（第二十轮）：累计进来的采样数 / 其中开着增益处理的采样数。服务在
     *  解码器初始化时把这两个数写进诊断——放着歌这两个数一直是 0，说明处理链
     *  被绕过了（硬件直通），增益开关自然没效果。 */
    @Volatile var samplesSeen: Long = 0
        private set
    @Volatile var samplesGained: Long = 0
        private set

    private var bytesPerSample = 2
    private var floatSamples = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> { bytesPerSample = 2; floatSamples = false }
            C.ENCODING_PCM_24BIT -> { bytesPerSample = 3; floatSamples = false }
            C.ENCODING_PCM_32BIT -> { bytesPerSample = 4; floatSamples = false }
            C.ENCODING_PCM_FLOAT -> { bytesPerSample = 4; floatSamples = true }
            else -> throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        // 第二十轮：记处理量（bytesPerSample 在 onConfigure 里定好，float 也是 4）。
        val sampleCount = remaining / bytesPerSample
        samplesSeen += sampleCount
        if (enabled) samplesGained += sampleCount
        val output = replaceOutputBuffer(remaining).order(inputBuffer.order())
        if (!enabled) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        if (floatSamples) {
            val n = remaining / 4
            var i = 0
            while (i < n) {
                output.putFloat(limitGain(inputBuffer.float))
                i++
            }
        } else {
            // Integer PCM: 2, 3 or 4 bytes per sample (see onConfigure). Normalise to
            // [-1, 1], soft-limit, scale back — the same curve for every width, so the
            // gain sounds identical whatever the source bit depth is.
            val n = remaining / bytesPerSample
            var i = 0
            while (i < n) {
                val s = when (bytesPerSample) {
                    2 -> inputBuffer.short.toFloat() / Short.MAX_VALUE
                    3 -> readInt24(inputBuffer).toFloat() / INT24_MAX
                    else -> inputBuffer.int.toFloat() / Int.MAX_VALUE.toFloat()
                }
                val y = limitGain(s)
                when (bytesPerSample) {
                    2 -> output.putShort(
                        (y * Short.MAX_VALUE).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    )
                    3 -> writeInt24(
                        output,
                        (y * INT24_MAX).toInt().coerceIn(INT24_MIN, INT24_MAX),
                    )
                    else -> output.putInt(
                        (y * Int.MAX_VALUE.toFloat()).toInt()
                            .coerceIn(Int.MIN_VALUE + 1, Int.MAX_VALUE),
                    )
                }
                i++
            }
        }
        // Any trailing partial sample (buffer not frame-aligned) passes through untouched.
        if (inputBuffer.hasRemaining()) output.put(inputBuffer)
        output.flip()
    }

    /**
     * Fixed linear gain with a soft ceiling that only engages near full scale.
     *
     * u = x·gain is passed through untouched while |u| ≤ [KNEE] (0.85); above the knee
     * it is smoothly rounded toward ±1.0 with a tanh shoulder. The curve is continuous
     * in value and slope at the knee (both 1.0 there) and never exceeds ±1.0, so loud
     * masters don't hard-clip — but unlike the old whole-signal tanh, the body of the
     * music (everything below the knee, i.e. input peaks up to ~0.43) gets exactly ×gain
     * with zero added distortion and zero gain pumping.
     */
    private fun limitGain(x: Float): Float {
        val u = (x * gain).toDouble()
        val a = abs(u)
        if (a <= KNEE) return u.toFloat()
        val y = KNEE + (1.0 - KNEE) * tanh((a - KNEE) / (1.0 - KNEE))
        return (if (u < 0) -y else y).toFloat().coerceIn(-1f, 1f)
    }

    /** 24-bit PCM has no ByteBuffer accessor — assemble/disassemble by hand, honouring
     *  the buffer's byte order (same order on input and output, set in queueInput). */
    private fun readInt24(buf: ByteBuffer): Int {
        val b0 = buf.get().toInt() and 0xFF
        val b1 = buf.get().toInt() and 0xFF
        val b2 = buf.get().toInt() and 0xFF
        val v = if (buf.order() == java.nio.ByteOrder.LITTLE_ENDIAN) {
            b0 or (b1 shl 8) or (b2 shl 16)
        } else {
            (b0 shl 16) or (b1 shl 8) or b2
        }
        // Sign-extend from 24 bits to a full Int.
        return if (v and 0x800000 != 0) v or -0x1000000 else v
    }

    private fun writeInt24(buf: ByteBuffer, value: Int) {
        val u = value and 0xFFFFFF
        if (buf.order() == java.nio.ByteOrder.LITTLE_ENDIAN) {
            buf.put((u and 0xFF).toByte())
            buf.put(((u shr 8) and 0xFF).toByte())
            buf.put(((u shr 16) and 0xFF).toByte())
        } else {
            buf.put(((u shr 16) and 0xFF).toByte())
            buf.put(((u shr 8) and 0xFF).toByte())
            buf.put((u and 0xFF).toByte())
        }
    }

    private companion object {
        const val GAIN_DB_6 = 1.9953f // 10^(6/20)
        /** Output level where the soft ceiling starts: |x·gain| ≤ 0.85 is pure linear gain. */
        const val KNEE = 0.85
        const val INT24_MAX = 8388607 // 2^23 - 1
        const val INT24_MIN = -8388608 // -(2^23)
    }
}
