package com.walkman.tv.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.tanh

/**
 * Media3 [androidx.media3.common.audio.AudioProcessor] that adds a fixed built-in gain
 * (~+6 dB) to the PCM stream, with a tanh soft limiter so loud masters don't hard-clip
 * ("破音"). Quiet passages are amplified almost linearly (×~1.9); peaks are smoothly
 * compressed and the output never exceeds ±1.0.
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
 * Formats: PCM 16-bit and PCM float, same as [AudioLevelProcessor]. Anything else throws
 * [UnhandledAudioFormatException] and the sink skips us — playback continues ungained
 * rather than failing.
 */
class GainAudioProcessor : BaseAudioProcessor() {

    /** +6 dB ≈ ×1.995. */
    var gain: Float = GAIN_DB_6
        set(value) {
            field = value
            tanhGain = tanh(value.toDouble()).toFloat()
        }

    /** Off ⇒ pure pass-through (bytes copied unchanged). */
    @Volatile var enabled: Boolean = true

    private var tanhGain: Float = tanh(GAIN_DB_6.toDouble()).toFloat()
    private var bytesPerSample = 2
    private var floatSamples = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> { bytesPerSample = 2; floatSamples = false }
            C.ENCODING_PCM_FLOAT -> { bytesPerSample = 4; floatSamples = true }
            else -> throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
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
                output.putFloat(softLimit(inputBuffer.float))
                i++
            }
        } else {
            val n = remaining / 2
            var i = 0
            while (i < n) {
                val s = inputBuffer.short.toFloat() / Short.MAX_VALUE
                val y = (softLimit(s) * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                output.putShort(y)
                i++
            }
        }
        // Any trailing partial sample (buffer not frame-aligned) passes through untouched.
        if (inputBuffer.hasRemaining()) output.put(inputBuffer)
        output.flip()
    }

    /** tanh soft limiter: ~linear ×gain for small signals, smoothly compressed near ±1. */
    private fun softLimit(x: Float): Float =
        (tanh((x * gain).toDouble()).toFloat() / tanhGain).coerceIn(-1f, 1f)

    private companion object {
        const val GAIN_DB_6 = 1.9953f // 10^(6/20)
    }
}
