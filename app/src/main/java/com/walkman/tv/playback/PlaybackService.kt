package com.walkman.tv.playback

import android.content.Context
import android.os.Bundle
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
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.walkman.tv.di.CrashDiagnostics

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
 * 队列 / 进度都走 Player 接口；增益开关、强制软件解码走自定义命令；音量电平
 * （AudioLevelProcessor 的回调）走 Messenger 回推。主进程不直接碰这个播放器。
 *
 * 这个进程里只初始化播放器：Application.onCreate 在 :playback 里直接 return，
 * 不建容器、不初始化 QuickJS、不跑 bootstrap（见 App.kt）。
 */
class PlaybackService : MediaSessionService() {

    companion object {
        /** 主进程 → :playback 的自定义命令（SessionCommand.customAction）。 */
        const val CMD_SET_GAIN_ENABLED = "com.walkman.tv.playback.SET_GAIN_ENABLED"
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
    private lateinit var gainProcessor: GainAudioProcessor
    private lateinit var levelProcessor: AudioLevelProcessor

    /** 增益开关（主进程通过自定义命令发过来；默认开，跟设置默认值一致）。 */
    @Volatile
    var gainEnabled: Boolean = true

    /** 强制软件解码（主进程通过自定义命令发过来；默认关）。 */
    @Volatile
    var forceSoftwareDecoding: Boolean = false

    /** 主进程的音量 Messenger（注册后才发；发失败 / 断了就停，下次注册再发）。 */
    @Volatile
    private var levelMessenger: Messenger? = null
    private var lastLevelSentMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        gainProcessor = GainAudioProcessor().apply { enabled = gainEnabled }
        levelProcessor = AudioLevelProcessor { level -> sendLevel(level) }
        // 建播放器（第十六轮：这里绝不能抛错杀掉 Service——启动闪退的头号嫌疑就是
        // 这里。先试 FFmpeg 优先；建不起来（比如这台电视加载 FFmpeg native 库失败）
        // 就退回平台解码再建一次；再建不起来也不死 Service，只记诊断、留 mediaSession
        // 为空，主进程那边连不上会后台重试，App 照样进首页）。
        val exoPlayer = runCatching {
            buildPlayer(this, preferFfmpeg = true).also {
                CrashDiagnostics.recordPlaybackServiceStart(this, "播放器建成：FFmpeg 软解优先")
            }
        }.getOrElse { firstError ->
            android.util.Log.w(TAG, "FFmpeg-first player build failed, retrying platform-only", firstError)
            runCatching {
                buildPlayer(this, preferFfmpeg = false).also {
                    CrashDiagnostics.recordPlaybackServiceStart(
                        this,
                        "播放器建成：平台解码（FFmpeg 优先失败：${firstError.javaClass.simpleName}: ${firstError.message}）",
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

    /** 主进程发来的自定义命令：增益开关、强制软件解码、注册音量 Messenger。 */
    private inner class SessionCallback : MediaSession.Callback {
        @Suppress("DEPRECATION")
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SET_GAIN_ENABLED -> {
                    gainEnabled = args.getBoolean(ARG_ENABLED, true)
                    gainProcessor.enabled = gainEnabled
                    Log.d(TAG, "gain enabled=$gainEnabled")
                }
                CMD_SET_FORCE_SOFTWARE_DECODING -> {
                    forceSoftwareDecoding = args.getBoolean(ARG_ENABLED, false)
                    Log.d(TAG, "force software decoding=$forceSoftwareDecoding (applies to next track)")
                }
                CMD_REGISTER_LEVEL_MESSENGER -> {
                    levelMessenger = args.getParcelable(ARG_MESSENGER)
                    Log.d(TAG, "level messenger registered")
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
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
     * - DefaultRenderersFactory in PREFER_EXTENSION mode so the bundled FLAC /
     *   OPUS / VORBIS extension renderers (when present) win over the slower
     *   MediaCodec path.
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
        val renderersFactory = object : DefaultRenderersFactory(appContext) {
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
                        // gain second (soft-limited +6 dB, see GainAudioProcessor).
                        DefaultAudioSink.DefaultAudioProcessorChain(levelProcessor, gainProcessor),
                    )
                    .build()
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
            // 里没有任何中途重置的路径（播放器只建一次、换源一首只换一次、开关只响应
            // 用户点按），所以这条反复压音量的路只能是焦点 ducking。关掉它：同一首歌
            // 从头到尾音量只由增益开关决定。代价：别的 App 放音频时音乐不再自动让路
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
