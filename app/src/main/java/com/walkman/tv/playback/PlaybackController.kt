package com.walkman.tv.playback

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Messenger
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.walkman.tv.data.store.PlaybackSnapshot
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.Track
import com.walkman.tv.di.CrashDiagnostics
import com.walkman.tv.source.ResolveOrigin
import com.walkman.tv.source.ResolvedTrack
import com.walkman.tv.source.SourceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

enum class RepeatMode { OFF, ALL, ONE }

data class PlaybackState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val resolving: Boolean = false,
    val warning: String? = null,
    val originLabel: String? = null,
    val quality: Quality? = null,
    val error: String? = null,
    val repeatMode: RepeatMode = RepeatMode.ALL,
    val shuffle: Boolean = false,
    val isMv: Boolean = false,
) {
    val currentTrack: Track? get() = queue.getOrNull(index)
}

/**
 * Single source of truth for playback (iOS `PlaybackEngine`). The ExoPlayer lives in the
 * `:playback` process (PlaybackService) and is driven here through a MediaController; resolves
 * each track's URL lazily via [SourceManager] on switch (URLs expire), records history, loads
 * lyrics, and reuses the same player for MV video. If the playback process dies (decoder
 * SIGSEGV), this controller restarts it and recovers (cross-source / skip) — the app never
 * exits to the desktop.
 */
class PlaybackController(
    context: Context,
    private val sources: SourceManager,
    private val lyricsFetcher: LyricsFetcher,
    private val audioSpecProbe: AudioSpecProbe? = null,
) {
    /**
     * Real-time audio level (0..1), smoothed by the audio thread inside [AudioLevelProcessor].
     * Drives the reactive waveform in the player + recommend NowPlayingPanel. Stays at 0 when
     * the player is paused or when ExoPlayer chooses audio offload (DSP path bypasses the
     * processor chain — the consuming Waveform then falls back to its synthetic animation).
     */
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    /** Last quality the cascade settled on for the current track — used by [onPlayerError]
     *  to decide which tier to step down from on format-reject errors. */
    private var lastAttemptedQuality: Quality? = null

    /** 这首歌解析时试过哪些源（成功时从 ResolvedTrack 带回来，播时换源成功再并进来）：
     *  播失败写诊断记录时用；playAt 新歌时清掉。 */
    private var lastAttemptedSources: List<String> = emptyList()

    /** Number of consecutive network-error retries on the current track. Reset to 0 on a
     *  successful play, on a fresh playAt(), or once we've given up and skipped to next. */
    private var networkRetryCount: Int = 0

    /** Cross-source retry already attempted for this track id (play-time failure path).
     *  One attempt per track: if the other platform's URL is bad too, we advance instead
     *  of bouncing between platforms forever. Reset on a fresh playAt(). */
    private var crossSourceTriedFor: String? = null

    /** Background pre-resolution of the next track in the queue. [preloadCache] holds a
     *  fully-resolved URL (already cross-source-fallback'd by resolveMusicURL) so the
     *  next song starts without waiting for script calls; a dead next track is
     *  discovered — and its replacement found — before the user reaches it. */
    private var preloadJob: Job? = null
    private val preloadCache = mutableMapOf<String, ResolvedTrack>()
    private var preloadedForIndex = -1

    /** Number of consecutive *tracks* that have failed back-to-back (resolve failed or an
     *  unrecoverable player error). Auto-skip to next on each failure; stop only once this hits
     *  [MAX_CONSECUTIVE_FAILURES]. Reset to 0 when any track actually reaches READY, and on a
     *  fresh user-initiated queue. Distinct from [networkRetryCount], which retries one track. */
    private var consecutiveFailures: Int = 0

    /**
     * Optional local-first URL resolver. When set, [playAt] checks it before going to
     * [SourceManager]. Wired by [com.walkman.tv.di.AppContainer] to point at
     * DownloadStore.localFile + LocalMusicStore.fileUri so already-downloaded songs and
     * SAF-imported tracks skip the online resolve cascade entirely.
     *
     * Returns a uri string (`file://...`, `content://...`) or null if the track isn't local.
     */
    var localUrlResolver: ((Track) -> String?)? = null

    /**
     * Measured audio spec for the currently-playing URL (FLAC/MP3 codec params from the
     * file header). Null while probing or when the format isn't recognised. Drives the
     * badge ceiling-clamp in spec §6 and the small "FLAC 24bit/192kHz" caption next to it.
     */
    private val _audioSpec = MutableStateFlow<AudioSpec?>(null)
    val audioSpec: StateFlow<AudioSpec?> = _audioSpec.asStateFlow()

    /** Url currently being probed — used to discard late results when the user has switched
     *  tracks before the probe returned. */
    @Volatile private var probingUrl: String? = null
    private var probeJob: Job? = null

    /**
     * 远程播放器（第十五轮）：ExoPlayer 活在 `:playback` 独立进程里（PlaybackService），
     * 主进程通过 MediaController 连它。所有播放控制都走这个 Player 接口；它为空时
     * （刚启动 / 播放进程刚死 / 还没连上）调用是 no-op，绝不让 UI 因为播放器没连上就崩。
     * 播放进程死了它就失效（onDisconnected），主进程重建一个新的连上去。
     */
    private val _remotePlayer = MutableStateFlow<Player?>(null)

    /** 播放页 PlayerView 绑的播放器（只读快照流；MV 播放时才用到）。 */
    val playerFlow: StateFlow<Player?> = _remotePlayer.asStateFlow()

    /** 同步读当前远程播放器（控制方法内部用；可能为 null）。 */
    private val player: Player? get() = _remotePlayer.value

    private val appContext = context.applicationContext

    /** 主进程收音量的 Messenger（:playback 里的 AudioLevelProcessor 回推，10Hz 节流）。 */
    private val levelMessenger = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == PlaybackService.MSG_AUDIO_LEVEL) {
                _audioLevel.value = msg.data?.getFloat(PlaybackService.ARG_LEVEL, 0f) ?: 0f
            }
            true
        },
    )

    /** :playback 连接（MediaController.buildAsync 的 future；重建时取消旧的）。 */
    private var controllerFuture: ListenableFuture<MediaController>? = null

    /** 当前这次连接尝试是什么时候发起的（防 future 永远挂起：超时就取消重连）。 */
    private var connectionAttemptStartedMs = 0L

    /** 累计连接失败次数 + 最后一次失败原因（诊断用；连上后清掉）。 */
    private var connectFailures = 0
    private var lastConnectError: String? = null

    /** 当前连着的 MediaController（发自定义命令用；Player 接口上没有 sendCustomCommand）。
     *  跟 _remotePlayer 同步：连上时设、死了 / 释放时清。 */
    private var mediaController: MediaController? = null

    /** 播放进程死后重建连接时要跑的恢复动作（换源 / 跳下一首 / 从头再播），见
     *  [handlePlaybackProcessDeath]；连上后跑一次就清掉。 */
    private var pendingRecovery: (() -> Unit)? = null

    /** 同一首歌死过几次（内存计数；播起来了清掉，用户主动点新歌也清掉）。
     *  死两次就跳下一首并写进拉黑文件（PlaybackCrashGuard）。 */
    private val playbackDeathsByTrack = mutableMapOf<String, Int>()

    /** :playback 连续死亡的次数和最近一次死亡时间（重连退避用：短时间内死太多次
     *  就慢点重连，别锤死循环。连上后清掉，见 onControllerConnected。） */
    private var consecutiveDeaths = 0
    private var lastDeathMs = 0L

    /** release() 后为 true：后台重连循环停掉，不再发起新连接。 */
    private var released = false

    /** 增益开关（设置 → 增益）：值存在这儿，发给 :playback（见 [sendPlaybackCommand]）；
     *  播放器在 :playback 进程里，GainAudioProcessor 也在那儿。默认开，跟设置默认值一致。 */
    @Volatile var builtinGainEnabled: Boolean = true
        set(value) {
            field = value
            sendPlaybackCommand(PlaybackService.CMD_SET_GAIN_ENABLED, value)
        }

    /** 强制软件解码：只用系统自带的软件解码器（c2.android.* / OMX.google.*），绕开部分电视、盒子
     *  厂商硬件解码器的闪退或无声问题。解码器在每次准备新歌时挑选，所以切换后从下一首开始生效。
     *  值存在这儿，发给 :playback；重建连接时重发。 */
    @Volatile var forceSoftwareDecoding: Boolean = false
        set(value) {
            field = value
            sendPlaybackCommand(PlaybackService.CMD_SET_FORCE_SOFTWARE_DECODING, value)
        }

    /** Hi-Res 直通开关（设置 → 音频解码）：值存在这儿，applyAudioOffload 发给远程播放器；
     *  重建连接时重发（光靠 setAudioOffloadEnabled 那次发，死后重建就丢了）。 */
    private var audioOffloadEnabled: Boolean = false

    private companion object {
        /** ExoPlayer error codes that indicate the URL was fetched fine but the bytes can't be
         *  decoded — i.e. a format-level rejection. Spec §3 calls for one-tier cascade here. */
        val FORMAT_REJECT_CODES = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )

        /** ExoPlayer error codes that point at transient network issues — connection drops,
         *  flaky CDN, expired URL on a paused track, etc. Worth retrying a few times before
         *  giving up on the track. */
        val NETWORK_RETRY_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        )
        const val MAX_NETWORK_RETRIES = 3

        /** Stop auto-skipping once this many tracks fail back-to-back (a whole playlist of dead
         *  links shouldn't spin forever). */
        const val MAX_CONSECUTIVE_FAILURES = 3

        /** 点播前验地址（AudioSpecProbe.validate）最多等多久：超时就放行，绝不把播放卡死。 */
        const val GATE_TIMEOUT_MS = 5_000L

        /** 同一首歌死两次就跳下一首并拉黑（见 handlePlaybackProcessDeath）。 */
        const val MAX_DEATHS_PER_TRACK = 2

        /** 等远程播放器连上最多等多久（playResolvedOrFail 里 awaitPlayer 用）。 */
        const val AWAIT_PLAYER_TIMEOUT_MS = 5_000L

        /** 一次连接尝试超过多久没完成就当卡住了（buildAsync 的 future 永远不回）：
         *  取消掉重连。8 秒：电视冷启动连服务一般 1–3 秒，8 秒还没回就是卡住了。 */
        const val CONNECT_ATTEMPT_TIMEOUT_MS = 8_000L

        /** 解码器记录多新鲜才可信（:playback 里解码器初始化时写；死的时候读它。
         *  10 分钟：一首歌一般 3–5 分钟，超过这个数就是上一首的残留，不采信）。 */
        const val DECODER_FRESH_MS = 10 * 60 * 1000L

        /** 解析地址总超时（兜底，第十七轮）：脚本线程卡死、源太慢等任何原因
         *  导致解析一直不返回时，90 秒就当失败，不让 UI 永远卡在
         *  “正在获取播放地址…”。正常解析几秒就完，慢的也就几十秒，
         *  90 秒不会误伤正常解析。 */
        const val RESOLVE_TIMEOUT_MS = 90_000L
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()

    /** Set by the app to record played tracks into history. */
    var onTrackStarted: ((Track) -> Unit)? = null
    /** Preferred play quality, fed from settings. */
    var preferredQuality: Quality = Quality.K320

    private var resolveJob: Job? = null

    /**
     * 远程播放器的回调（挂在 MediaController 上；重建连接时重新挂）。
     * 只处理 Kotlin 层能收到的事：播 / 停、播完切歌、格式拒收降级、网络重试、
     * 换源。原生 SIGSEGV 触发不了这里（进程直接死了），那靠 onDisconnected。
     */
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
            // Pause: drop the audio level immediately so the waveform settles instead
            // of holding whatever amplitude the last decoded chunk reported.
            if (!isPlaying) _audioLevel.value = 0f
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                if (_state.value.isMv) exitMv() else handleEnded()
            }
            if (playbackState == Player.STATE_READY) {
                // A track actually started — clear the consecutive-failure streak.
                consecutiveFailures = 0
                // 这首歌播起来了：之前的死亡计数清掉（它能播，不是一播就死的歌）。
                _state.value.currentTrack?.id?.let { playbackDeathsByTrack.remove(it) }
                // ... and drop the resolve-time notice ("已自动换源" etc.): it was only
                // relevant while the URL was being found; once the song is playing it is
                // stale and must not linger on the player / home panel status line.
                // Real failures still surface via error / failAndAdvance (a failed track
                // never reaches READY, so its warning is untouched here).
                _state.value = _state.value.copy(durationMs = (player?.duration ?: 0L).coerceAtLeast(0), warning = null)
                // Start resolving the next track in the background while this one plays.
                preloadNext()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Format/codec rejections are the "encrypted .mgg" / "actually MP3 not FLAC" case
            // from spec §3 — re-cascade from one tier below the current attempt. Network /
            // unauthorized errors get surfaced as-is.
            val isFormatReject = error.errorCode in FORMAT_REJECT_CODES
            val current = lastAttemptedQuality
            val track = _state.value.currentTrack
            // Format reject already at the lowest tier: this platform has nothing left —
            // try the same song from another platform before giving up on it.
            if (isFormatReject && track != null && current == Quality.K128) {
                tryCrossSourceOrAdvance(track, error.errorCodeName)
                return
            }
            if (isFormatReject && track != null && current != null && current != Quality.K128) {
                val full = Quality.orderedHighToLow
                val idx = full.indexOf(current)
                val below = if (idx >= 0 && idx + 1 < full.size) full[idx + 1] else Quality.K128
                android.util.Log.w(
                    "PlaybackController",
                    "format reject @ ${current.key} (${error.errorCodeName}); re-cascading from ${below.key}",
                )
                _state.value = _state.value.copy(
                    warning = "${current.displayName} 格式不支持，降级到 ${below.displayName}",
                )
                networkRetryCount = 0 // format error path — separate from the network counter
                playAt(_state.value.index, qualityCap = below)
                return
            }
            // Transient network — exponential backoff retry up to MAX_NETWORK_RETRIES.
            // After that, try another platform for the same song before auto-skipping.
            if (error.errorCode in NETWORK_RETRY_CODES && track != null) {
                if (networkRetryCount < MAX_NETWORK_RETRIES) {
                    networkRetryCount++
                    val delayMs = 500L shl (networkRetryCount - 1) // 500ms / 1s / 2s
                    android.util.Log.w(
                        "PlaybackController",
                        "network error (${error.errorCodeName}); retry ${networkRetryCount}/$MAX_NETWORK_RETRIES in ${delayMs}ms",
                    )
                    _state.value = _state.value.copy(
                        warning = "网络不稳定，正在重试 ($networkRetryCount/$MAX_NETWORK_RETRIES)…",
                        resolving = true,
                        error = null,
                    )
                    val trackIdAtRetry = track.id
                    scope.launch {
                        kotlinx.coroutines.delay(delayMs)
                        // Guard: user may have skipped to a different track during the delay.
                        if (_state.value.currentTrack?.id == trackIdAtRetry) {
                            playAt(_state.value.index, qualityCap = lastAttemptedQuality)
                        }
                    }
                    return
                }
                // Retries exhausted — try another platform for the same song first.
                android.util.Log.w(
                    "PlaybackController",
                    "network retries exhausted on ${track.name}; trying other sources",
                )
                tryCrossSourceOrAdvance(track, "网络不稳定")
                return
            }
            // Any other error — try another platform for the same song; only auto-skip
            // (or stop after too many in a row) once no platform can play it.
            if (track != null) {
                tryCrossSourceOrAdvance(track, error.errorCodeName)
                return
            }
            failAndAdvance(error.errorCodeName)
        }
    }

    init {
        // 播放器连接改成后台懒加载（第十六轮）：启动时不再同步去连 :playback——
        // Service 连不上 App 也要能进首页。后台循环带退避重试；播歌前 awaitPlayer 会等它。
        startConnectLoop()
        startPositionTicker()
    }

    // MARK: - queue control

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, autoPlay: Boolean = true) {
        runCatching { setQueueInternal(tracks, startIndex, autoPlay) }
            .onFailure { _state.value = _state.value.copy(error = "播放失败：${it.message}") }
    }

    private fun setQueueInternal(tracks: List<Track>, startIndex: Int = 0, autoPlay: Boolean = true) {
        if (tracks.isEmpty()) return
        consecutiveFailures = 0 // fresh user-initiated queue — start the failure streak over
        preloadJob?.cancel()
        preloadCache.clear()
        preloadedForIndex = -1
        _state.value = _state.value.copy(queue = tracks, index = startIndex.coerceIn(0, tracks.size - 1))
        if (autoPlay) playAt(_state.value.index)
    }

    /** Capture the parts of state we persist across launches. */
    fun snapshot(): PlaybackSnapshot {
        val s = _state.value
        return PlaybackSnapshot(
            queue = s.queue,
            currentIndex = s.index,
            wasPlaying = s.isPlaying,
            repeatModeOrdinal = s.repeatMode.ordinal,
            shuffle = s.shuffle,
        )
    }

    /** Restore queue + index from a snapshot. Resumes playback if the previous session was
     *  playing; URLs are resolved fresh (they expire). Called once from AppContainer.bootstrap. */
    fun restoreSnapshot(s: PlaybackSnapshot) {
        val mode = RepeatMode.values().getOrNull(s.repeatModeOrdinal) ?: RepeatMode.ALL
        _state.value = _state.value.copy(repeatMode = mode, shuffle = s.shuffle)
        if (s.queue.isEmpty() || s.currentIndex !in s.queue.indices) return
        // Stage the queue without auto-play first, so the UI shows the track immediately.
        _state.value = _state.value.copy(queue = s.queue, index = s.currentIndex, isPlaying = false)
        if (s.wasPlaying) {
            // 上次播到一半原生闪退过的歌（:playback 死了），这次启动别再自动播它，
            // 否则一启动就又死一次。跳到下一首；整单就这一首时停在原地等用户点。
            val trackId = s.queue[s.currentIndex].id
            val startIndex = if (PlaybackCrashGuard.isCrashed(appContext, trackId)) s.currentIndex + 1 else s.currentIndex
            if (startIndex < s.queue.size) playAt(startIndex)
        }
    }

    fun playTrack(track: Track) = setQueue(listOf(track), 0, true)

    fun playAt(index: Int, qualityCap: Quality? = null) {
        val track = _state.value.queue.getOrNull(index) ?: return
        // Fresh attempt (no cap) clears the last-attempted memory + the network retry counter;
        // cascade retries (cap != null) keep them so [onPlayerError] can keep stepping down or
        // continue counting retries within the same logical attempt.
        if (qualityCap == null) {
            lastAttemptedQuality = null
            lastAttemptedSources = emptyList()
            networkRetryCount = 0
            crossSourceTriedFor = null
            // 用户主动点了一首新歌：这首歌之前的死亡计数清掉（重新给一次机会）。
            playbackDeathsByTrack.remove(track.id)
            // A new track is starting — the old preload (if any) is for a different next.
            preloadJob?.cancel()
            preloadedForIndex = -1
            // Silence the previous song right away. Resolving can take a long time with a slow or
            // dead source (every quality tier × 15s script timeout, then other-platform lookups),
            // and the old track playing on under the new title looked like "切歌没成功".
            runCatching { player?.stop() }
            _state.value = _state.value.copy(positionMs = 0)
        }
        val effectivePreferred = qualityCap ?: preferredQuality
        _state.value = _state.value.copy(index = index, resolving = true, error = null, warning = null, isMv = false)
        // Switch lyrics immediately with the metadata — lyrics depend only on the track, not the
        // resolved play URL, so don't make them wait for (or hinge on) a successful audio resolve.
        // Guarded to fresh attempts so a quality-cascade retry of the same track doesn't refetch.
        if (qualityCap == null) loadLyrics(track)
        resolveJob?.cancel()
        resolveJob = scope.launch {
            // Local fast-path: downloaded tracks + SAF-imported tracks skip the online cascade.
            // localUrlResolver is set up at AppContainer init time.
            val localUrl = runCatching { localUrlResolver?.invoke(track) }.getOrNull()
            // Preloaded URL from the background preload (only for a fresh attempt — quality
            // cascade retries always resolve fresh so an expired preloaded URL can't loop).
            val cached = if (qualityCap == null) preloadCache.remove(track.id) else null
            val resolved = if (localUrl != null) {
                Result.success(
                    ResolvedTrack(
                        url = localUrl,
                        origin = ResolveOrigin.LocalFile,
                        quality = track.qualities.firstOrNull() ?: effectivePreferred,
                        warning = null,
                    ),
                )
            } else if (cached != null) {
                Result.success(cached)
            } else {
                // 第十七轮：解析总超时（兜底）。JsScriptRuntime 的 25 秒超时只包着
                // deferred.await()，JS 线程卡死时 withContext(jsDispatcher) 会永远等下去、
                // 解析永远不完成也不失败，UI 一直卡在“正在获取播放地址…”。
                // 90 秒还没回就当失败，走正常失败流程（记诊断、静默跳下一首）。
                withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                    runCatching { sources.resolveMusicURL(track, effectivePreferred) }
                } ?: Result.failure(SourceManager.SourceException("解析超时（${RESOLVE_TIMEOUT_MS / 1000} 秒无应答）"))
            }
            // playResolvedOrFail 是 suspend（关口验地址要等网络），不能包在 runCatching 里
            // （它的 lambda 不是 suspend 上下文），改 try/catch。
            try {
                playResolvedOrFail(track, resolved)
            } catch (e: Exception) {
                android.util.Log.e("PlaybackController", "playAt crashed", e)
                failAndAdvance(
                    e.message ?: e::class.simpleName ?: "播放出错",
                    (e as? SourceManager.SourceException)?.attemptedSources ?: emptyList(),
                )
            }
        }
    }

    private suspend fun playResolvedOrFail(track: Track, resolved: Result<ResolvedTrack>) {
        // 注意：这里不用 resolved.onSuccess {}——关口验地址要调 suspend 的 validate，
        // onSuccess 的 lambda 不是 suspend 上下文。先拆出值再走直线代码。
        val r = resolved.getOrNull()
        if (r == null) {
            // Couldn't get a play URL for this track — auto-skip (or stop after too many).
            val e = resolved.exceptionOrNull()
            failAndAdvance(
                e?.message ?: "无法播放",
                (e as? SourceManager.SourceException)?.attemptedSources ?: emptyList(),
            )
            return
        }
            // 脏地址兜底：解析器偶尔会吐出空地址或非音频 scheme（例如把报错页当成音频
            // 文件）。直接交给 ExoPlayer 会撞死电视原生解码器（SIGSEGV，Kotlin 层抓不
            // 住、无回调）。先拦下来，按失败处理自动跳下一首，不交给播放器。
            val scheme = runCatching { android.net.Uri.parse(r.url).scheme?.lowercase() }.getOrNull()
            if (r.url.isBlank() || scheme !in setOf("http", "https", "file", "content")) {
                failAndAdvance("播放地址无效", r.attemptedSources)
                return
            }
            // 点播前验播放地址（2026-11-26《相亲相爱》原生闪退）：Ki.js 只认 URL 以
            // http 开头就返回，内容从不验——后端把报错页/截断文件当音频发过来，头几个
            // 字节看着像 FLAC（角标能读出 24bit/192kHz），后面的帧是坏的，解码器几秒
            // 后直接 SIGSEGV，进程被杀、诊断里什么都没有。先拿头 64KB 验文件头/容器/
            // 帧/大小（见 AudioSpecProbe.validate），脏的拦下来走播时换源（其它平台
            // 同一首），哪个平台都播不了再跳下一首；验不出（探头为空、网络不给 Range、
            // 超时、容器不认）一律放行，绝不把播放卡死。本地文件（已下载/SAF 导入）
            // 同样验：file:// 走探头读文件，content:// 这里读头字节再验。
            // 播时换源那条路也进这个函数，所以中途换源同样验。预载的地址播到时也
            // 经过这里。MV 不走这里。
            val gate: AudioSpecProbe.HeadVerdict? = when {
                audioSpecProbe == null -> null
                scheme == "content" -> readContentHead(r.url, track.duration)
                else -> withTimeoutOrNull(GATE_TIMEOUT_MS) {
                    audioSpecProbe.validate(r.url, track.duration)
                } ?: AudioSpecProbe.HeadVerdict.Unknown("校验超时 ${GATE_TIMEOUT_MS}ms，放行")
            }
            val dirty = gate as? AudioSpecProbe.HeadVerdict.Dirty
            if (dirty != null) {
                android.util.Log.w(
                    "PlaybackController",
                    "dirty play URL intercepted for ${track.name} - ${track.singer}: ${dirty.reason}",
                )
                // 静默换源：屏幕上什么都不显示（2026-11-26 定“不用提示”），只记诊断。
                tryCrossSourceOrAdvance(track, "点播前拦截到脏地址（${dirty.reason}）", silent = true)
                return
            }
            val item = MediaItem.Builder()
                .setUri(r.url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.name)
                        .setArtist(track.singer)
                        .setAlbumTitle(track.albumName)
                        .apply { track.picURL?.let { setArtworkUri(android.net.Uri.parse(it)) } }
                        .build(),
                )
                .build()
            // Remember the tier so onPlayerError can re-cascade from one tier below if
            // ExoPlayer rejects the file format.
            lastAttemptedQuality = r.quality
            // 记下这次解析试过哪些源（播时换源成功会再并进来），播失败写诊断记录时用。
            lastAttemptedSources = (lastAttemptedSources + r.attemptedSources).distinct()
            // Successful resolve + setMediaItem → reset the network retry counter so the
            // next track gets a fresh budget.
            networkRetryCount = 0
            // 等远程播放器连上（刚启动 / 播放进程死后重建要一点时间）；连不上就当这首失败。
            // 第十八轮：失败原因里带上连接失败的次数和最后一次原因，诊断里一眼能看到
            // 是连不上、还是连上又断了。
            val remote = awaitPlayer() ?: run {
                failAndAdvance("播放进程未连接${connectionFailureSummary()}", r.attemptedSources)
                return
            }
            // 留痕（第十五轮）：解析出地址后、setMediaItem 之前，把赢家地址写进诊断
            // 文件 + 状态文件。播放进程原生闪退死后，主进程靠这两条知道它当时在播
            // 哪条地址，也能验证隔离到底生效没有。写盘失败绝不拦播放。
            runCatching {
                CrashDiagnostics.recordNowPlaying(
                    appContext,
                    "${track.name} - ${track.singer}",
                    r.origin.label,
                    r.quality.displayName,
                    r.url,
                )
                PlaybackCrashGuard.writeAttempt(
                    appContext,
                    track.id,
                    "${track.name} - ${track.singer}",
                    r.url,
                    r.origin.label,
                    r.quality.displayName,
                )
            }
            remote.setMediaItem(item)
            remote.prepare()
            // Fire the file-header probe so the badge can later clamp the claimed tier
            // down if the bytes turn out to be lower-grade than the script reported.
            // Spec §5.4: clear current spec first, run async, validate URL is still
            // current before publishing.
            // 2026-11-26 音质核对：脚本只回一个 URL、不回它实际用的档位，ResolvedTrack.quality
            // 记的是“请求档位”（pickPlayQuality 定的 first），不是到手那份文件的档位——
            // 提供方静默降档（要 FLAC24 给了 128k/AAC）时角标会一直显示请求档位，听着发闷
            // 却看不出原因。探头是唯一实测：拿到结果就按 displayQuality 校正 state.quality
            // （角标只有一个真相来源），并把“请求档位 vs 实测”写进诊断记录；探头不认的容器
            // （AAC/M4A 等）也记一笔，免得角标显示 Hi-Res、实际却没测到。
            // lastAttemptedQuality 不动：降级重试要从“尝试过的档位”往下走，不是实测档位。
            _audioSpec.value = null
            probingUrl = r.url
            probeJob?.cancel()
            if (audioSpecProbe != null) {
                val claimedQuality = r.quality
                val songLabel = "${track.name} - ${track.singer}"
                probeJob = scope.launch {
                    val targetUrl = r.url
                    val spec = audioSpecProbe.probe(targetUrl)
                    if (probingUrl != targetUrl) return@launch
                    if (spec != null) {
                        _audioSpec.value = spec
                        val shown = displayQuality(claimedQuality, spec)
                        if (shown != null && Quality.rank(shown) > Quality.rank(claimedQuality)) {
                            _state.value = _state.value.copy(quality = shown)
                            CrashDiagnostics.recordQualityNote(
                                appContext,
                                songLabel,
                                requested = claimedQuality.displayName,
                                measured = spec.displayText,
                            )
                        }
                    } else if (Quality.rank(claimedQuality) <= Quality.rank(Quality.FLAC)) {
                        CrashDiagnostics.recordQualityNote(
                            appContext,
                            songLabel,
                            requested = claimedQuality.displayName,
                            measured = "文件头未识别（可能是 AAC/M4A 等探头不认的容器），角标显示的仍是请求档位",
                        )
                    }
                }
            }
            remote.playWhenReady = true
            _state.value = _state.value.copy(
                resolving = false,
                warning = r.warning,
                originLabel = r.origin.label,
                quality = r.quality,
            )
            onTrackStarted?.invoke(track)
            // (lyrics already loaded in playAt when the track switched)
    }

    /**
     * 读 content:// 本地文件的头字节再验（SAF 导入的歌）。读不到、验不出都放行——
     * 本地文件验的目的是拦坏文件，不是拦播。
     */
    private suspend fun readContentHead(url: String, durationSec: Int?): AudioSpecProbe.HeadVerdict {
        val probe = audioSpecProbe ?: return AudioSpecProbe.HeadVerdict.Unknown("无探头")
        return runCatching {
            val bytes = withContext(Dispatchers.IO) {
                appContext.contentResolver.openInputStream(android.net.Uri.parse(url))?.use { stream ->
                    val buf = ByteArray(65_536)
                    var total = 0
                    while (total < buf.size) {
                        val n = stream.read(buf, total, buf.size - total)
                        if (n <= 0) break
                        total += n
                    }
                    buf.copyOf(total)
                }
            } ?: return AudioSpecProbe.HeadVerdict.Unknown("content:// 读不到")
            probe.validateHead(bytes, contentType = null, contentLength = null, durationSec = durationSec)
        }.getOrElse { AudioSpecProbe.HeadVerdict.Unknown("content:// 校验异常：${it.message}") }
    }

    /**
     * A track has definitively failed (couldn't resolve a URL, or an unrecoverable player error).
     * Auto-skip to the next track so the queue keeps going; only stop once
     * [MAX_CONSECUTIVE_FAILURES] tracks fail back-to-back. The streak resets when any track
     * reaches READY (see the player listener). Guarded so a user skipping mid-delay wins.
     *
     * 真播不了时屏幕上什么都不显示（2026-11-26 Yang 定：不弹框、不 toast、不在状态行
     * 显示），只静默记进诊断记录（设置 → 查看诊断记录能看到是哪首歌、换了哪几个源、
     * 什么原因）。自动跳下一首 / 连败 3 首停下来的行为本身不动。
     */
    private fun failAndAdvance(reason: String, attemptedSources: List<String> = emptyList()) {
        // Stop whatever is still loaded (e.g. a cascade retry's half-played attempt) so a failed
        // track actually goes silent.
        runCatching { player?.stop() }
        networkRetryCount = 0
        consecutiveFailures++
        val track = _state.value.currentTrack
        val sources = attemptedSources.ifEmpty { lastAttemptedSources }
            .ifEmpty { _state.value.originLabel?.let { listOf(it) } ?: emptyList() }
        val logReason = if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            "连续 $MAX_CONSECUTIVE_FAILURES 首播放失败，已停止（$reason）"
        } else {
            "播放失败，自动播放下一首（$reason）"
        }
        if (track != null) {
            CrashDiagnostics.recordPlaybackFailure(
                appContext,
                "${track.name} - ${track.singer}",
                sources,
                logReason,
            )
        }
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            consecutiveFailures = 0
            _state.value = _state.value.copy(
                error = null,
                resolving = false,
                warning = null,
            )
            return
        }
        _state.value = _state.value.copy(
            warning = null,
            resolving = false,
            error = null,
        )
        val failedId = _state.value.currentTrack?.id
        scope.launch {
            delay(400)
            // Only auto-advance if we're still parked on the track that failed (the user may
            // have manually picked another song during the delay).
            if (_state.value.currentTrack?.id == failedId) next()
        }
    }

    /**
     * Play-time cross-source fallback (第 6 条)：解析时拿到的地址播起来才发现是坏的
     * （坏文件 / 死链），先在其它平台搜同一首歌换个地址再播，播起来为止；哪个平台都
     * 播不了才跳下一首。同一首歌只换一次源，避免在坏地址之间来回跳。
     * [silent]：点播前验地址拦下来的脏 URL 用——换源中屏幕上什么都不显示（2026-11-26
     * 定“不用提示”），只记诊断记录；其它错误路径保持原样。
     */
    private fun tryCrossSourceOrAdvance(track: Track, reason: String, silent: Boolean = false) {
        if (crossSourceTriedFor == track.id) {
            failAndAdvance(reason, lastAttemptedSources + "播时换源（其它平台）")
            return
        }
        crossSourceTriedFor = track.id
        if (!silent) {
            _state.value = _state.value.copy(
                warning = "这首歌当前源播不了，正在换其它源…",
                resolving = true,
                error = null,
            )
        } else {
            _state.value = _state.value.copy(resolving = true, error = null)
        }
        val trackId = track.id
        scope.launch {
            // 第十七轮：同上，播时换源的解析也要总超时兜底，超时当失败、走原有失败流程。
            val res = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                runCatching { sources.resolveOtherSource(track, preferredQuality) }.getOrNull()
            }
            // 用户切歌了就丢弃换好的地址，别塞给新歌。
            if (_state.value.currentTrack?.id != trackId) return@launch
            if (res != null) {
                networkRetryCount = 0
                playResolvedOrFail(track, Result.success(res))
            } else {
                failAndAdvance(reason, lastAttemptedSources + "播时换源（其它平台）")
            }
        }
    }

    /**
     * Preload the next track (第 7 条)：这首播着的时候，后台把下一首的地址先解析出来
     * （走 resolveMusicURL，里面自带跨平台换源——源不行提前就换好了），存进缓存；
     * 播到那首时直接用缓存地址，不用再等脚本逐档解析。解析失败就不缓存，播到那首时
     * 走正常解析 + 播时换源兜底。
     */
    private fun preloadNext() {
        val s = _state.value
        if (s.queue.isEmpty() || s.index !in s.queue.indices) return
        val nextIdx = when {
            s.shuffle -> if (s.queue.size <= 1) return else randomOther(s.index, s.queue.size)
            s.index + 1 < s.queue.size -> s.index + 1
            s.repeatMode != RepeatMode.OFF -> 0
            else -> return
        }
        if (nextIdx == preloadedForIndex) return
        preloadedForIndex = nextIdx
        val currentIndex = s.index
        preloadJob?.cancel()
        preloadJob = scope.launch {
            val track = _state.value.queue.getOrNull(nextIdx) ?: return@launch
            val local = runCatching { localUrlResolver?.invoke(track) }.getOrNull()
            val res = if (local != null) {
                ResolvedTrack(
                    url = local,
                    origin = ResolveOrigin.LocalFile,
                    quality = track.qualities.firstOrNull() ?: preferredQuality,
                    warning = null,
                )
            } else {
                runCatching { sources.resolveMusicURL(track, preferredQuality) }.getOrNull()
            }
            // 用户已经切到别的歌就丢弃这次预载。
            if (res != null && _state.value.index == currentIndex) {
                preloadCache[track.id] = res
            }
        }
    }

    /**
     * Toggle audio offload (DSP direct path). Some vendor audio HALs (小米/鸿蒙/坚果 etc.)
     * advertise offload support but crash natively when fed FLAC/Hi-Res through it, so this
     * is exposed as a settings switch. Takes effect on the next format change / track switch.
     */
    fun setAudioOffloadEnabled(enabled: Boolean) {
        audioOffloadEnabled = enabled
        applyAudioOffload()
    }

    /** 把 Hi-Res 直通开关发给远程播放器（没连上就等连上时重发，见 onControllerConnected）。 */
    private fun applyAudioOffload() {
        val remote = player ?: return
        val mode = if (audioOffloadEnabled) {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        } else {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        }
        remote.trackSelectionParameters = remote.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(mode)
                    .build(),
            )
            .build()
    }

    fun togglePlay() {
        runCatching { togglePlayInternal() }
            .onFailure { _state.value = _state.value.copy(error = "播放失败：${it.message}") }
    }

    private fun togglePlayInternal() {
        val remote = player
        if (remote?.isPlaying == true) {
            remote.pause()
            return
        }
        // Cold-start path: restoreSnapshot() stages the queue + index in [_state] but only
        // calls playAt() when the previous session was actively playing (wasPlaying=true).
        // If the user left paused, ExoPlayer is empty on relaunch — `player.play()` is a
        // no-op. Lazy-resolve the current track now so the play button actually starts the
        // song that the UI is already showing.
        val s = _state.value
        if ((remote?.mediaItemCount ?: 0) == 0 && s.queue.isNotEmpty() && s.index in s.queue.indices) {
            playAt(s.index)
        } else {
            remote?.play()
        }
    }

    fun next() {
        runCatching { nextInternal() }
            .onFailure { _state.value = _state.value.copy(error = "切换失败：${it.message}") }
    }

    private fun nextInternal() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        val nextIdx = when {
            s.shuffle -> if (s.queue.size <= 1) s.index else randomOther(s.index, s.queue.size)
            s.index + 1 < s.queue.size -> s.index + 1
            s.repeatMode != RepeatMode.OFF -> 0
            else -> { player?.pause(); return }
        }
        playAt(nextIdx)
    }

    fun prev() {
        runCatching { prevInternal() }
            .onFailure { _state.value = _state.value.copy(error = "切换失败：${it.message}") }
    }

    private fun prevInternal() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        if ((player?.currentPosition ?: 0L) > 3000) { player?.seekTo(0); return }
        val prevIdx = when {
            s.shuffle -> if (s.queue.size <= 1) s.index else randomOther(s.index, s.queue.size)
            s.index - 1 >= 0 -> s.index - 1
            else -> s.queue.size - 1
        }
        playAt(prevIdx)
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0))
    }

    fun setRepeatMode(mode: RepeatMode) { _state.value = _state.value.copy(repeatMode = mode) }

    fun toggleShuffle() { _state.value = _state.value.copy(shuffle = !_state.value.shuffle) }

    fun stop() {
        player?.stop()
        player?.clearMediaItems()
        preloadJob?.cancel()
        preloadCache.clear()
        preloadedForIndex = -1
        PlaybackCrashGuard.clearAttempt(appContext)
        playbackDeathsByTrack.clear()
        _state.value = PlaybackState()
        _lyrics.value = emptyList()
    }

    // MARK: - MV

    /** Play an MV video url on the same player (UI attaches a PlayerView for output). */
    fun playMvUrl(url: String) {
        resolveJob?.cancel()
        _state.value = _state.value.copy(isMv = true, resolving = false, error = null)
        scope.launch {
            val remote = awaitPlayer() ?: return@launch
            remote.setMediaItem(MediaItem.fromUri(url))
            remote.prepare()
            remote.playWhenReady = true
        }
    }

    /** Exit MV and resume the current audio track. */
    fun exitMv() {
        if (!_state.value.isMv) return
        _state.value = _state.value.copy(isMv = false)
        playAt(_state.value.index)
    }

    // MARK: - internals

    private fun handleEnded() {
        if (_state.value.repeatMode == RepeatMode.ONE) {
            player?.seekTo(0); player?.play(); return
        }
        next()
    }

    private fun loadLyrics(track: Track) {
        _lyrics.value = emptyList()
        scope.launch {
            val lines = runCatching { lyricsFetcher.fetch(track) }.getOrDefault(emptyList())
            if (_state.value.currentTrack?.id == track.id) _lyrics.value = lines
        }
    }

    private fun randomOther(current: Int, size: Int): Int {
        var i = current
        while (i == current) i = Random.nextInt(size)
        return i
    }

    private fun startPositionTicker() {
        scope.launch {
            while (true) {
                val remote = player
                if (remote?.isPlaying == true || _state.value.isMv) {
                    _state.value = _state.value.copy(
                        positionMs = (remote?.currentPosition ?: 0L).coerceAtLeast(0),
                        durationMs = (remote?.duration ?: 0L).coerceAtLeast(0),
                    )
                }
                delay(1000)
            }
        }
    }

    fun release() {
        released = true
        resolveJob?.cancel()
        controllerFuture?.cancel(true)
        controllerFuture = null
        _remotePlayer.value?.let { runCatching { it.release() } }
        _remotePlayer.value = null
        mediaController = null
        PlaybackCrashGuard.clearAttempt(appContext)
    }

    /** Current ExoPlayer audio session id — what an `Equalizer` etc must bind to.
     *  远程播放器还没连上时是 UNSET（均衡器会等连上再绑）。 */
    val audioSessionId: Int get() = player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET

    // MARK: - :playback 进程连接与死亡恢复（第十五轮）

    /** 远程播放器的回调（MediaController.Listener）：只关心断开（:playback 死了）。 */
    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            handlePlaybackProcessDeath(controller)
        }
    }

    /** 后台连 :playback（第十六轮）：启动时不再同步连，Service 连不上 App 也要能
     *  进首页。循环带退避重试；只在“没连上且没有正在进行的连接”时才发起一次，
     *  避免取消掉正在连的 future。release() 后停掉。 */
    private fun startConnectLoop() {
        scope.launch {
            var backoffMs = 500L
            while (!released) {
                if (_remotePlayer.value == null) {
                    val pending = controllerFuture?.let { !it.isDone } ?: false
                    if (!pending) {
                        runCatching { connectController() }
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
                        continue
                    }
                    // 第十八轮：连接 future 挂起太久（buildAsync 永远不回）时，循环以前
                    // 只会每秒看一次、永远等下去，播歌每次都 5 秒超时失败。现在超时就
                    // 取消掉、记一次失败、马上重连，不让连接永久卡住。
                    val stuckMs = System.currentTimeMillis() - connectionAttemptStartedMs
                    if (connectionAttemptStartedMs > 0L && stuckMs > CONNECT_ATTEMPT_TIMEOUT_MS) {
                        runCatching { controllerFuture?.cancel(true) }
                        controllerFuture = null
                        connectFailures++
                        lastConnectError = "连接超时（${CONNECT_ATTEMPT_TIMEOUT_MS / 1000} 秒未完成）"
                        android.util.Log.w(
                            "PlaybackController",
                            "MediaController connect stuck, cancelling and retrying (#$connectFailures)",
                        )
                        runCatching { connectController() }
                        backoffMs = 500L
                        continue
                    }
                } else {
                    backoffMs = 500L
                }
                delay(1_000L)
            }
        }
    }

    /** 播歌前确保正在连（没连上且没有正在进行的连接时发起一次）。 */
    private fun ensureConnected() {
        if (released || _remotePlayer.value != null) return
        val pending = controllerFuture?.let { !it.isDone } ?: false
        if (!pending) runCatching { connectController() }
    }

    /** 播放进程死后重连（带退避）：短时间内连续死多次时，先等几秒再连，别锤死循环。 */
    private fun reconnectWithBackoff() {
        if (released) return
        if (consecutiveDeaths >= 3) {
            val waitMs = 5_000L
            android.util.Log.w(
                "PlaybackController",
                "playback died $consecutiveDeaths times in a row; delaying reconnect ${waitMs}ms",
            )
            scope.launch {
                delay(waitMs)
                if (!released && _remotePlayer.value == null) runCatching { connectController() }
            }
        } else {
            runCatching { connectController() }
        }
    }

    /** 连上 :playback 里的 MediaSession（MediaController.buildAsync）。连接是异步的：
     *  刚启动要等一会儿；播放进程死后重建也要等一会儿。播歌前 awaitPlayer 会等它。
     *  重复调用会取消旧的连接（连续死了两次时，旧 future 的回调会放掉旧控制器）。 */
    private fun connectController() {
        if (released) return
        controllerFuture?.cancel(true)
        controllerFuture = null
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token)
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future
        connectionAttemptStartedMs = System.currentTimeMillis()
        future.addListener({
            // 已经被更新的连接取代了（连续死了两次）：放掉这个旧控制器。
            if (future !== controllerFuture) {
                runCatching { future.get() }.getOrNull()?.let { runCatching { it.release() } }
                return@addListener
            }
            // 第十八轮：连接失败以前是静默丢掉（get() 抛错就直接 return），诊断里
            // 什么都不留，只剩一句“播放进程未连接”。现在把失败次数和原因记下来，
            // 播歌失败时写进诊断记录。
            val controller = try {
                future.get()
            } catch (e: Exception) {
                connectFailures++
                lastConnectError = e.javaClass.simpleName + ": " + (e.message ?: "")
                android.util.Log.w(
                    "PlaybackController",
                    "MediaController connect failed (#$connectFailures): $lastConnectError",
                )
                return@addListener
            }
            onControllerConnected(controller)
        }, ContextCompat.getMainExecutor(appContext))
    }

    /** 远程播放器连上了：挂回调、把当前设置发给 :playback、注册音量回推、跑恢复动作。 */
    private fun onControllerConnected(controller: MediaController) {
        _remotePlayer.value = controller
        mediaController = controller
        consecutiveDeaths = 0
        // 第十八轮：连上了，之前累计的连接失败清掉（下次再失败重新数）。
        connectFailures = 0
        lastConnectError = null
        // 第十七轮：连上就记一条。诊断里没有 [播放器已连接] 就说明主进程根本
        // 没连上 :playback——这是第十六轮后“全部不能听”排查时缺的那条证据。
        runCatching { CrashDiagnostics.recordPlaybackConnected(appContext) }
        controller.addListener(playerListener)
        // 把当前设置发给 :playback（刚建好的播放器用的是默认值）。
        sendPlaybackCommand(PlaybackService.CMD_SET_GAIN_ENABLED, builtinGainEnabled)
        sendPlaybackCommand(PlaybackService.CMD_SET_FORCE_SOFTWARE_DECODING, forceSoftwareDecoding)
        applyAudioOffload()
        // 第二十轮：记下主进程发给服务的值——跟服务那边的 [音频设置] 对上，
        // 两边不一样就说明自定义命令没送到。
        runCatching {
            CrashDiagnostics.recordAudioState(
                appContext,
                "主进程",
                "发给服务：增益${if (builtinGainEnabled) "开" else "关"}" +
                    "、直通${if (audioOffloadEnabled) "开" else "关"}" +
                    "、强制软解${if (forceSoftwareDecoding) "开" else "关"}",
            )
        }
        registerLevelMessenger(controller)
        // 播放进程死后重建：跑之前定好的恢复动作（换源 / 跳下一首 / 从头再播）。
        val recovery = pendingRecovery
        pendingRecovery = null
        recovery?.invoke()
    }

    /** 给 :playback 发一个带开关值的自定义命令（增益 / 强制软件解码）。没连上就丢掉，
     *  连上时会重发当前值（见 onControllerConnected）。 */
    private fun sendPlaybackCommand(action: String, enabled: Boolean) {
        val controller = mediaController ?: return
        val args = Bundle().apply { putBoolean(PlaybackService.ARG_ENABLED, enabled) }
        runCatching {
            controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        }
    }

    /** 把主进程的音量 Messenger 注册给 :playback（AudioLevelProcessor 回推用）。 */
    private fun registerLevelMessenger(controller: MediaController) {
        val args = Bundle().apply { putParcelable(PlaybackService.ARG_MESSENGER, levelMessenger) }
        runCatching {
            controller.sendCustomCommand(
                SessionCommand(PlaybackService.CMD_REGISTER_LEVEL_MESSENGER, Bundle.EMPTY),
                args,
            )
        }
    }

    /** 等远程播放器连上（刚启动 / 播放进程死后重建要一点时间）。超时返回 null。 */
    private suspend fun awaitPlayer(timeoutMs: Long = AWAIT_PLAYER_TIMEOUT_MS): Player? {
        _remotePlayer.value?.let { return it }
        ensureConnected()
        return withTimeoutOrNull(timeoutMs) {
            _remotePlayer.first { it != null }
        }
    }

    /** 连接失败的摘要（诊断用）："（连接失败 3 次，最后：IllegalStateException: …）"，没失败过就是空串。 */
    private fun connectionFailureSummary(): String {
        if (connectFailures <= 0) return ""
        val last = lastConnectError?.let { "，最后：$it" } ?: ""
        return "（连接失败 $connectFailures 次$last）"
    }

    /**
     * :playback 进程死了（原生解码器 SIGSEGV 或系统杀进程）。主进程还活着：
     * 记诊断、重启播放进程、按规则恢复（换源 / 跳下一首），App 不退桌面。
     *
     * 恢复规则：
     * - 没在播（暂停 / 空闲 / 正在解析新歌）：只重建连接，不恢复——解析完会自己连上
     *   新播放器播，用户暂停的歌不自动起播。
     * - 内存杀死：同一首歌没问题，从头再播一遍。
     * - 原生崩溃：同一首歌死一次就换源再试；两次都死（或已经换过源了还死）就跳下一首，
     *   并拉黑 24 小时（启动自动恢复时跳过它）。
     */
    private fun handlePlaybackProcessDeath(deadController: MediaController) {
        // 不是当前连着的控制器（已经重建过 / 正在释放）：忽略。
        if (_remotePlayer.value !== deadController) {
            runCatching { deadController.release() }
            return
        }
        runCatching { deadController.release() }
        _remotePlayer.value = null
        mediaController = null
        _audioLevel.value = 0f

        val track = _state.value.currentTrack
        val wasPlaying = _state.value.isPlaying
        val attempt = PlaybackCrashGuard.readAttempt(appContext)
        val song = track?.let { "${it.name} - ${it.singer}" } ?: attempt?.song ?: "未知"
        // 尝试记录的地址只在它就是当前这首歌时才可信（解析中死了，记录还是上一首的）。
        val url = attempt?.takeIf { it.trackId == track?.id }?.url ?: ""
        val origin = attempt?.originLabel ?: _state.value.originLabel ?: "未知"

        // 区分原生崩溃和内存杀死（API 30+ 才有退出原因；低版本 / 读不到一律按崩溃处理，
        // 宁可多跳一首也不死循环）。
        val exitReason = readPlaybackExitReason()
        val isCrash = exitReason == null ||
            exitReason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            exitReason == ApplicationExitInfo.REASON_CRASH
        val reasonText = when {
            exitReason == ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存不足杀死播放进程"
            isCrash -> "原生崩溃（SIGSEGV，解码器）"
            else -> "播放进程异常退出（原因 $exitReason）"
        }
        // 第十八轮：把当时在用的解码器也记上（:playback 里解码器初始化时写的，
        // 只采信 10 分钟内的新鲜记录），下次诊断直接看到是 FFmpeg 还是平台解码器在崩。
        val decoder = runCatching { PlaybackCrashGuard.readDecoder(appContext) }
            .getOrNull()
            ?.takeIf { System.currentTimeMillis() - it.atMs < DECODER_FRESH_MS }
            ?.decoder
        CrashDiagnostics.recordPlaybackProcessDeath(appContext, song, url, origin, reasonText, decoder)
        android.util.Log.w("PlaybackController", "playback process died: $song ($reasonText)")

        // 连续死亡计数（重连退避用）：10 秒内又死一次就 +1，否则重新算。
        val nowMs = System.currentTimeMillis()
        consecutiveDeaths = if (nowMs - lastDeathMs < 10_000L) consecutiveDeaths + 1 else 1
        lastDeathMs = nowMs

        // 正在播的那首歌的“尝试记录”清掉（恢复逻辑会重新写）。
        PlaybackCrashGuard.clearAttempt(appContext)

        // 没在播 / 正在解析新歌：只重建连接，不恢复。
        if (track == null || !wasPlaying || _state.value.resolving) {
            reconnectWithBackoff()
            return
        }

        if (!isCrash) {
            // 内存杀死：同一首歌没问题，从头再播一遍。
            val index = _state.value.index
            pendingRecovery = { playAt(index) }
            reconnectWithBackoff()
            return
        }

        // 原生崩溃：同一首歌死了，换源再试一次；两次都死就跳下一首并拉黑 24 小时。
        val trackId = track.id
        val deaths = (playbackDeathsByTrack[trackId] ?: 0) + 1
        playbackDeathsByTrack[trackId] = deaths
        if (deaths >= MAX_DEATHS_PER_TRACK || crossSourceTriedFor == trackId) {
            PlaybackCrashGuard.markCrashed(appContext, trackId, song)
            pendingRecovery = { failAndAdvance("播放进程连续崩溃，已跳过") }
        } else {
            pendingRecovery = { tryCrossSourceOrAdvance(track, "播放进程崩溃") }
        }
        reconnectWithBackoff()
    }

    /** 读 :playback 进程最近一次的退出原因（API 30+）。返回 null 表示读不到（低版本 /
     *  无记录 / 记录太旧），调用方按崩溃处理。 */
    private fun readPlaybackExitReason(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val reasons = am.getHistoricalProcessExitReasons(appContext.packageName, 0, 10)
            val now = System.currentTimeMillis()
            reasons.firstOrNull { info ->
                info.processName?.endsWith(":playback") == true &&
                    now - info.timestamp < 10_000L
            }?.reason
        }.getOrNull()
    }
}
