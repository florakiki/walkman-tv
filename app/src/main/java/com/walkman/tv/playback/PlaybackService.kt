package com.walkman.tv.playback

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.walkman.tv.di.CrashDiagnostics
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * 播放进程的 Service（2026-11-27 第十五轮）：ExoPlayer 只活在这个 `:playback`
 * 独立进程里（AndroidManifest 里 `android:process=":playback"`）。
 *
 * 为什么：原生解码器在系统底层直接把整个 App 进程杀了（SIGSEGV），Kotlin 层
 * 换源的代码根本没机会跑，App 退回电视桌面，诊断里什么都不留。隔离之后，解码器
 * 再死也只死这个进程；主进程还活着，感知到死亡（MediaController.Listener.
 * onDisconnected）后重启播放进程、换源或跳下一首，App 不退桌面。
 *
 * 主进程通过 MediaController（Media3 session IPC）跟它通信：播 / 停 / 切歌 /
 * 队列 / 进度都走 Player 接口；强制软件解码走自定义命令；音量电平
 * （AudioLevelProcessor 的回调）走 Messenger 回推。主进程不直接碰这个播放器。
 *
 * 这个进程里只初始化播放器：Application.onCreate 在 :playback 里直接 return，
 * 不建容器、不初始化 QuickJS、不跑 bootstrap（见 App.kt）。
 */
class PlaybackService : MediaSessionService() {

    companion object {
        /** 主进程 → :playback 的自定义命令（SessionCommand.customAction）。 */
        const val CMD_SET_FORCE_SOFTWARE_DECODING = "com.walkman.tv.playback.SET_FORCE_SOFTWARE_DECODING"
        const val CMD_REGISTER_LEVEL_MESSENGER = "com.walkman.tv.playback.REGISTER_LEVEL_MESSENGER"

        /** 命令参数：开关值 / 音量 Messenger。 */
        const val ARG_ENABLED = "enabled"
        const val ARG_MESSENGER = "messenger"

        /** :playback → 主进程的音量消息（Messenger.what）。 */
        const val MSG_AUDIO_LEVEL = 1
        const val ARG_LEVEL = "level"

        private const val TAG = "PlaybackService"
        /** 音量回推节流：音频线程 ~50Hz 回调，只推 10Hz，免得 Binder 刷屏。 */
        private const val LEVEL_THROTTLE_MS = 100L
    }

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    /** 增益（第二十五轮）：固定开、约 +3dB，没有开关也没有命令。GainAudioProcessor
     *  默认就是开的（见 [GainAudioProcessor.enabled]），建播放器时直接挂进音频链，
     *  不用等主进程发什么值——撤开关不能断线，增益一直开。 */
    private lateinit var gainProcessor: GainAudioProcessor
    private lateinit var levelProcessor: AudioLevelProcessor

    /** 强制软件解码（主进程通过自定义命令发过来；默认关）。 */
    @Volatile
    var forceSoftwareDecoding: Boolean = false

    /** 上次写进 [音频设置] 诊断的解码器名（只在换解码器时再写，免得每首歌刷屏）。 */
    private var lastAudioStateDecoder: String? = null

    /** 主进程的音量 Messenger（注册后才发；发失败 / 断了就停，下次注册再发）。 */
    @Volatile
    private var levelMessenger: Messenger? = null
    private var lastLevelSentMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        // 第二十五轮：增益固定开——GainAudioProcessor 默认就是开的，直接建出来
        // 挂进音频链，不用等命令。enabled 在这里不显式设，免得留一条“值从哪来”
        // 的断线。
        gainProcessor = GainAudioProcessor()
        levelProcessor = AudioLevelProcessor { level -> sendLevel(level) }
        // 建播放器（第十六轮：这里绝不能抛错杀掉 Service——启动闪退的头号嫌疑就是
        // 这里。先试 FFmpeg 优先；建不起来（比如这台电视加载 FFmpeg native 库失败）
        // 就退回平台解码再建一次；再建不起来也不死 Service，只记诊断、留 mediaSession
        // 为空，主进程那边连不上会后台重试，App 照样进首页）。
        val exoPlayer = runCatching {
            buildPlayer(this, preferFfmpeg = true).also {
                // 第十八轮：FFmpeg 之前其实一直没用上（DefaultRenderersFactory 反射找
                // androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer，NextLib 的类在
                // io.github.anilbeesetti…包下，ClassNotFound 被静默吞掉），所以这里
                // 把库的实际可用情况也记下来，下次诊断直接看，不用再猜。
                CrashDiagnostics.recordPlaybackServiceStart(
                    this,
                    "播放器建成：FFmpeg 软解优先（${ffmpegStatus()}）",
                )
            }
        }.getOrElse { firstError ->
            android.util.Log.w(TAG, "FFmpeg-first player build failed, retrying platform-only", firstError)
            runCatching {
                buildPlayer(this, preferFfmpeg = false).also {
                    CrashDiagnostics.recordPlaybackServiceStart(
                        this,
                        "播放器建成：平台解码（FFmpeg 优先失败：${firstError.javaClass.simpleName}: ${firstError.message}；${ffmpegStatus()}）",
                    )
                }
            }.getOrElse { secondError ->
                android.util.Log.e(TAG, "player build failed entirely", secondError)
                CrashDiagnostics.recordPlaybackServiceStart(
                    this,
                    "播放器建不起来：FFmpeg 优先失败 ${firstError.javaClass.simpleName}: ${firstError.message}；" +
                        "平台解码失败 ${secondError.javaClass.simpleName}: ${secondError.message}",
                )
                null
            }
        } ?: return
        player = exoPlayer
        // 第二十轮：记下建播放器时的音频实况（增益固定开、直通模式）。直通开着时
        // 解码器永远不会初始化（音频走 DSP），那时这条就是唯一的音频证据。
        runCatching { recordAudioState(null) }
        // 记下实际在解码的解码器名（FFmpeg 叫 "ffmpeg"，平台叫 c2.android.* 等）：
        // 播放进程原生崩溃后，主进程读这条就知道是哪条解码器在崩（第十八轮）。
        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                runCatching { PlaybackCrashGuard.writeDecoder(this@PlaybackService, decoderName) }
                // 第二十轮：解码器换了才记一条音频实况（带增益处理采样数）——增益处理
                // 采样数 >0 说明处理链在跑、增益在生效；放着歌一直是 0 说明链被绕过了。
                if (decoderName != lastAudioStateDecoder) {
                    lastAudioStateDecoder = decoderName
                    runCatching { recordAudioState(decoderName) }
                }
            }
        })
        mediaSession = runCatching {
            MediaSession.Builder(this, exoPlayer)
                .setCallback(SessionCallback())
                .build()
        }.getOrElse { e ->
            android.util.Log.e(TAG, "MediaSession build failed", e)
            CrashDiagnostics.recordPlaybackServiceStart(
                this,
                "播放器建成但 MediaSession 建不起来：${e.javaClass.simpleName}: ${e.message}",
            )
            null
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    /** 音量回推给主进程（节流后；Messenger 断了就停发）。音频线程回调，Messenger.send 是线程安全的。 */
    private fun sendLevel(level: Float) {
        val messenger = levelMessenger ?: return
        val now = System.currentTimeMillis()
        if (now - lastLevelSentMs < LEVEL_THROTTLE_MS) return
        lastLevelSentMs = now
        val msg = Message.obtain(null, MSG_AUDIO_LEVEL).apply {
            data = Bundle().apply { putFloat(ARG_LEVEL, level) }
        }
        try {
            messenger.send(msg)
        } catch (e: RemoteException) {
            // 主进程死了或 Messenger 断了：停发，下次注册再发。
            levelMessenger = null
        }
    }

    /** 主进程发来的自定义命令：强制软件解码、注册音量 Messenger。 */
    private inner class SessionCallback : MediaSession.Callback {
        @Suppress("DEPRECATION")
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SET_FORCE_SOFTWARE_DECODING -> {
                    val next = args.getBoolean(ARG_ENABLED, false)
                    val changed = next != forceSoftwareDecoding
                    forceSoftwareDecoding = next
                    Log.d(TAG, "force software decoding=$forceSoftwareDecoding (applies to next track)")
                    if (changed) runCatching { recordAudioState(lastAudioStateDecoder) }
                }
                CMD_REGISTER_LEVEL_MESSENGER -> {
                    levelMessenger = args.getParcelable(ARG_MESSENGER)
                    Log.d(TAG, "level messenger registered")
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /** FFmpeg 库在电视上的实际可用情况（下次诊断直接看这条，不用猜 FFmpeg 有没有在用）。 */
    private fun ffmpegStatus(): String {
        val available = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
        val mp3 = if (available) {
            runCatching { FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_MPEG) }.getOrDefault(false)
        } else {
            false
        }
        return "FFmpeg 库：${if (available) "可用" else "不可用"}，MP3：${if (mp3) "支持" else "不支持"}"
    }

    /**
     * 把当前音频实况写进诊断（第二十轮）：直通模式、实际在用的解码器、增益处理器
     * 处理过的采样数。增益是固定开的（第二十五轮），这里只记“固定开”——增益
     * 没效果时，看采样数是不是在涨、直通是不是开着，就能定性。
     */
    private fun recordAudioState(decoder: String?) {
        val offloadMode = player?.trackSelectionParameters?.audioOffloadPreferences?.audioOffloadMode
        val offload = if (offloadMode ==
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        ) "开" else "关"
        val detail = buildString {
            appendLine("增益：固定开（约 +3dB）")
            appendLine("硬件直通：$offload")
            appendLine("强制软解：${if (forceSoftwareDecoding) "开" else "关"}")
            appendLine("解码器：${decoder ?: "（还没解码）"}")
            appendLine("增益已处理：${gainProcessor.samplesSeen} 采样（其中增益采样 ${gainProcessor.samplesGained}）")
        }
        CrashDiagnostics.recordAudioState(this, ":playback", detail)
    }

    /**
     * Build the ExoPlayer with settings tuned for music playback on Android TV
     * （从 PlaybackController 搬过来——播放器现在活在这个 :playback 进程里）：
     *
     * - Larger LoadControl buffers (60s max / 30s min target) so a Hi-Res FLAC at
     *   ~10 Mbps doesn't constantly underrun on slower connections; the start
     *   threshold (3s) keeps perceived play latency low.
     * - DefaultAudioSink with audio offload DISABLED by default — offload would route
     *   the stream to DSP/HDMI passthrough and bypass the processor chain (built-in
     *   gain + waveform meter). Users who want bit-perfect PCM/FLAC passthrough can
     *   switch Hi-Res 直通 on in settings; the main process applies it from there.
     * - NextRenderersFactory (NextLib) in PREFER mode so the bundled FFmpeg audio
     *   renderer wins over the platform MediaCodec path. Plain
     *   DefaultRenderersFactory can't do this: it reflectively looks up
     *   androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer, which isn't in the
     *   build (NextLib's renderer lives in io.github.anilbeesetti…), so FFmpeg
     *   was silently never used before round 18 — every song, including 找巢,
     *   was decoded by the platform decoder all along.
     * - setEnableDecoderFallback(true)（第十五轮）：解码器初始化失败时自动换一个
     *   解码器再试。只能救初始化失败，播起来才崩的 SIGSEGV 救不了——那靠
     *   :playback 隔离。
     */
    private fun buildPlayer(appContext: Context, preferFfmpeg: Boolean): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 3_000,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Prefer bundled FLAC/OPUS/VORBIS extension renderers over MediaCodec; pipe audio at
        // float precision so 24-bit sources don't get prematurely truncated to 16-bit PCM.
        // (Audio offload is opted in via TrackSelectionParameters below — the deprecated
        //  DefaultRenderersFactory.setEnableAudioOffload was removed in Media3 1.4.)
        //
        // Anonymous subclass overrides buildAudioSink so we can append our
        // [AudioLevelProcessor] to the DefaultAudioSink's processor chain. The processor
        // forwards bytes unchanged, so audio quality is preserved. When the OS chooses
        // audio offload (DSP / HDMI passthrough / A2DP offload), this chain is bypassed
        // entirely and the processor stops receiving samples — the consumer naturally
        // falls back to a synthetic waveform.
        // 第十八轮：FFmpeg 必须用 NextLib 的 NextRenderersFactory（它直接 new
        // io.github.anilbeesetti…FfmpegAudioRenderer），不能用 DefaultRenderersFactory——
        // 后者反射找 androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer，那个类根本不在
        // 包里（NextLib 的类在 io.github.anilbeesetti 包下），ClassNotFound 被静默吞掉，
        // FFmpeg 渲染器从来没被创建过，所有歌一直都是平台 MediaCodec 在解，找巢的
        // SIGSEGV 才拦不住。NextRenderersFactory 是 DefaultRenderersFactory 的子类，
        // 同一套 setter、同样的 buildAudioSink 覆盖都能用。
        val renderersFactory = object : NextRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        // Order matters: level meter first (measures pre-gain),
                        // gain second (soft-limited +3 dB, see GainAudioProcessor).
                        DefaultAudioSink.DefaultAudioProcessorChain(levelProcessor, gainProcessor),
                    )
                    .build()
            }

            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>,
            ) {
                // MV 视频不走 FFmpeg 软解（仍走硬解，软解 1080p 在盒子上会卡）：
                // 只给音频加 FFmpeg，视频这里传 OFF，让 NextRenderersFactory 跳过
                // FfmpegVideoRenderer。
                super.buildVideoRenderers(
                    context,
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out,
                )
            }
        }.apply {
            // FFmpeg 优先：扩展渲染器排在平台 MediaCodec 前面；关掉就是纯平台解码
            // （第十六轮的退路：FFmpeg native 库在某台电视上加载失败时用）。
            if (preferFfmpeg) {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            }
        }
            .setMediaCodecSelector { mimeType, secure, tunneling ->
                val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                // 只管音频；MV 视频仍走硬解，软解 1080p 在盒子上会卡。没有可用的软件解码器时退回
                // 全部，宁可走硬解也别直接放不了。
                if (!forceSoftwareDecoding || !MimeTypes.isAudio(mimeType)) all
                else all.filter { it.softwareOnly }.ifEmpty { all }
            }
            .setEnableAudioFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(true)
            .setEnableDecoderFallback(true)

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Use the 1-arg constructor + setRenderersFactory(): the 2-arg Builder(Context,
        // RenderersFactory) overload is ambiguous with Builder(Context, MediaSource.Factory).
        val player = ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            // handleAudioFocus = false（2026-11-26）：之前开着时，系统 / 别的 App 一抢音频焦点，
            // ExoPlayer 就把音量压到约 20%（ducking），焦点回来再恢复——同一首歌里音量
            // 一会儿有一会儿没有、反复切好几次，听着就像增益在来回开关。增益本身在代码
            // 里没有任何中途重置的路径（播放器只建一次、换源一首只换一次、增益固定开
            // 没有开关可拨），所以这条反复压音量的路只能是焦点 ducking。关掉它：同一首歌
            // 从头到尾音量只由固定增益决定。代价：别的 App 放音频时音乐不再自动让路
            // （不会暂停 / 压低），要停得手动按一下——电视上没电话，这点可以接受。
            .setAudioAttributes(audioAttrs, /* handleAudioFocus = */ false)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Audio offload defaults to DISABLED here (2026-11-26): offload hands the
        // compressed stream straight to the DSP / HDMI passthrough and bypasses the
        // whole DefaultAudioSink processor chain — including the built-in gain. With
        // the old ENABLED default, whether a track got the gain depended on whether
        // ExoPlayer chose offload for that track's format, i.e. the gain could
        // silently drop out per track (and the setting default is off, see
        // SettingsStore.audioOffloadEnabled). The settings collector in AppContainer
        // still turns offload on for users who switch Hi-Res 直通 on — this default
        // only closes the window where a track could offload before settings apply.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
                    )
                    .build(),
            )
            .build()
        return player
    }
}
