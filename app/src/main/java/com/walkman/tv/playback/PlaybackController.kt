package com.walkman.tv.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.walkman.tv.data.store.PlaybackSnapshot
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.Track
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
import kotlinx.coroutines.launch
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
 * Single source of truth for playback (iOS `PlaybackEngine`). Owns one ExoPlayer; resolves each
 * track's URL lazily via [SourceManager] on switch (URLs expire), records history, loads lyrics,
 * and reuses the same player for MV video.
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

    private val audioLevelProcessor = AudioLevelProcessor { level ->
        _audioLevel.value = level
    }

    /** Built-in gain (~+6 dB, soft-limited). Runs in the sink's processor chain after
     *  [audioLevelProcessor]; toggled from settings, default on. */
    private val gainProcessor = GainAudioProcessor()

    /** Settings-driven switch for [gainProcessor]; wired in AppContainer.bootstrap. */
    @Volatile var builtinGainEnabled: Boolean = true
        set(value) {
            field = value
            gainProcessor.enabled = value
        }

    /** 强制软件解码：只用系统自带的软件解码器（c2.android.* / OMX.google.*），绕开部分电视、盒子
     *  厂商硬件解码器的闪退或无声问题。解码器在每次准备新歌时挑选，所以切换后从下一首开始生效。 */
    @Volatile var forceSoftwareDecoding: Boolean = false

    val player: ExoPlayer = buildPlayer(context.applicationContext, audioLevelProcessor, gainProcessor, { forceSoftwareDecoding })

    private val appContext = context.applicationContext

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

        /**
         * Build the ExoPlayer with settings tuned for music playback on Android TV:
         *
         * - Larger LoadControl buffers (60s max / 30s min target) so a Hi-Res FLAC at
         *   ~10 Mbps doesn't constantly underrun on slower connections; the start
         *   threshold (3s) keeps perceived play latency low.
         * - DefaultAudioSink with audio offload enabled — on Android 10+ the OS will
         *   route bit-perfect PCM/FLAC to dedicated DSP/HDMI passthrough when the
         *   downstream device can decode it, preserving 24-bit / 96-192 kHz tracks
         *   without resampling.
         * - DefaultRenderersFactory in PREFER_EXTENSION mode so the bundled FLAC /
         *   OPUS / VORBIS extension renderers (when present) win over the slower
         *   MediaCodec path.
         */
        fun buildPlayer(
            appContext: android.content.Context,
            levelProcessor: AudioLevelProcessor,
            gainProcessor: GainAudioProcessor,
            softwareOnly: () -> Boolean,
        ): ExoPlayer {
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
                    context: android.content.Context,
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
            }
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setMediaCodecSelector { mimeType, secure, tunneling ->
                    val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                    // 只管音频；MV 视频仍走硬解，软解 1080p 在盒子上会卡。没有可用的软件解码器时退回
                    // 全部，宁可走硬解也别直接放不了。
                    if (!softwareOnly() || !androidx.media3.common.MimeTypes.isAudio(mimeType)) all
                    else all.filter { it.softwareOnly }.ifEmpty { all }
                }
                .setEnableAudioFloatOutput(true)
                .setEnableAudioTrackPlaybackParams(true)

            val audioAttrs = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            // Use the 1-arg constructor + setRenderersFactory(): the 2-arg Builder(Context,
            // RenderersFactory) overload is ambiguous with Builder(Context, MediaSource.Factory).
            val player = ExoPlayer.Builder(appContext)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setAudioAttributes(audioAttrs, /* handleAudioFocus = */ true)
                .setHandleAudioBecomingNoisy(true)
                .build()

            // Ask the OS for audio offload when the downstream sink (DSP / HDMI passthrough /
            // Bluetooth A2DP offload) can decode it directly — preserves bit-perfect PCM/FLAC.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setAudioOffloadPreferences(
                    TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(
                            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
                        )
                        .build(),
                )
                .build()
            return player
        }
    }
    private var serviceStarted = false
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

    init {
        player.addListener(object : Player.Listener {
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
                    _state.value = _state.value.copy(durationMs = player.duration.coerceAtLeast(0))
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
        })
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
        if (s.wasPlaying) playAt(s.currentIndex)
    }

    fun playTrack(track: Track) = setQueue(listOf(track), 0, true)

    fun playAt(index: Int, qualityCap: Quality? = null) {
        val track = _state.value.queue.getOrNull(index) ?: return
        // Fresh attempt (no cap) clears the last-attempted memory + the network retry counter;
        // cascade retries (cap != null) keep them so [onPlayerError] can keep stepping down or
        // continue counting retries within the same logical attempt.
        if (qualityCap == null) {
            lastAttemptedQuality = null
            networkRetryCount = 0
            crossSourceTriedFor = null
            // A new track is starting — the old preload (if any) is for a different next.
            preloadJob?.cancel()
            preloadedForIndex = -1
            // Silence the previous song right away. Resolving can take a long time with a slow or
            // dead source (every quality tier × 15s script timeout, then other-platform lookups),
            // and the old track playing on under the new title looked like "切歌没成功".
            runCatching { player.stop() }
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
                runCatching { sources.resolveMusicURL(track, effectivePreferred) }
            }
            runCatching { playResolvedOrFail(track, resolved) }
                .onFailure { e ->
                    android.util.Log.e("PlaybackController", "playAt crashed", e)
                    failAndAdvance(e.message ?: e::class.simpleName ?: "播放出错")
                }
        }
    }

    private fun playResolvedOrFail(track: Track, resolved: Result<ResolvedTrack>) {
        resolved.onSuccess { r ->
                // 脏地址兜底：解析器偶尔会吐出空地址或非音频 scheme（例如把报错页当成音频
                // 文件）。直接交给 ExoPlayer 会撞死电视原生解码器（SIGSEGV，Kotlin 层抓不
                // 住、无回调）。先拦下来，按失败处理自动跳下一首，不交给播放器。
                val scheme = runCatching { android.net.Uri.parse(r.url).scheme?.lowercase() }.getOrNull()
                if (r.url.isBlank() || scheme !in setOf("http", "https", "file", "content")) {
                    failAndAdvance("播放地址无效")
                    return@onSuccess
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
                // Successful resolve + setMediaItem → reset the network retry counter so the
                // next track gets a fresh budget.
                networkRetryCount = 0
                player.setMediaItem(item)
                player.prepare()
                // Fire the file-header probe so the badge can later clamp the claimed tier
                // down if the bytes turn out to be lower-grade than the script reported.
                // Spec §5.4: clear current spec first, run async, validate URL is still
                // current before publishing.
                _audioSpec.value = null
                probingUrl = r.url
                probeJob?.cancel()
                if (audioSpecProbe != null) {
                    probeJob = scope.launch {
                        val targetUrl = r.url
                        val spec = audioSpecProbe.probe(targetUrl)
                        if (spec != null && probingUrl == targetUrl) {
                            _audioSpec.value = spec
                        }
                    }
                }
                // NOTE: MediaSessionService startup is currently disabled — Android 12+ kills
                // the process via ForegroundServiceDidNotStartInTimeException if the service
                // doesn't post a media notification within 5s, which races with our URL-resolve
                // path. Re-enable once a proper MediaNotification.Provider is in place.
                // ensureService()
                player.playWhenReady = true
                _state.value = _state.value.copy(
                    resolving = false,
                    warning = r.warning,
                    originLabel = r.origin.label,
                    quality = r.quality,
                )
                onTrackStarted?.invoke(track)
                // (lyrics already loaded in playAt when the track switched)
            }.onFailure { e ->
                // Couldn't get a play URL for this track — auto-skip (or stop after too many).
                failAndAdvance(e.message ?: "无法播放")
            }
    }

    /**
     * A track has definitively failed (couldn't resolve a URL, or an unrecoverable player error).
     * Auto-skip to the next track so the queue keeps going; only stop once
     * [MAX_CONSECUTIVE_FAILURES] tracks fail back-to-back. The streak resets when any track
     * reaches READY (see the player listener). Guarded so a user skipping mid-delay wins.
     */
    private fun failAndAdvance(reason: String) {
        // Stop whatever is still loaded (e.g. a cascade retry's half-played attempt) so a failed
        // track actually goes silent.
        runCatching { player.stop() }
        networkRetryCount = 0
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            consecutiveFailures = 0
            _state.value = _state.value.copy(
                error = "连续 $MAX_CONSECUTIVE_FAILURES 首播放失败，已停止（$reason）",
                resolving = false,
                warning = null,
            )
            return
        }
        _state.value = _state.value.copy(
            warning = "播放失败，自动播放下一首（$reason）",
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
     */
    private fun tryCrossSourceOrAdvance(track: Track, reason: String) {
        if (crossSourceTriedFor == track.id) {
            failAndAdvance(reason)
            return
        }
        crossSourceTriedFor = track.id
        _state.value = _state.value.copy(
            warning = "这首歌当前源播不了，正在换其它源…",
            resolving = true,
            error = null,
        )
        val trackId = track.id
        scope.launch {
            val res = runCatching { sources.resolveOtherSource(track, preferredQuality) }.getOrNull()
            // 用户切歌了就丢弃换好的地址，别塞给新歌。
            if (_state.value.currentTrack?.id != trackId) return@launch
            if (res != null) {
                networkRetryCount = 0
                playResolvedOrFail(track, Result.success(res))
            } else {
                failAndAdvance(reason)
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
        val mode = if (enabled) {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        } else {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
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
        if (player.isPlaying) {
            player.pause()
            return
        }
        // Cold-start path: restoreSnapshot() stages the queue + index in [_state] but only
        // calls playAt() when the previous session was actively playing (wasPlaying=true).
        // If the user left paused, ExoPlayer is empty on relaunch — `player.play()` is a
        // no-op. Lazy-resolve the current track now so the play button actually starts the
        // song that the UI is already showing.
        val s = _state.value
        if (player.mediaItemCount == 0 && s.queue.isNotEmpty() && s.index in s.queue.indices) {
            playAt(s.index)
        } else {
            player.play()
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
            else -> { player.pause(); return }
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
        if (player.currentPosition > 3000) { player.seekTo(0); return }
        val prevIdx = when {
            s.shuffle -> if (s.queue.size <= 1) s.index else randomOther(s.index, s.queue.size)
            s.index - 1 >= 0 -> s.index - 1
            else -> s.queue.size - 1
        }
        playAt(prevIdx)
    }

    fun seekTo(ms: Long) = player.seekTo(ms.coerceAtLeast(0))

    fun setRepeatMode(mode: RepeatMode) { _state.value = _state.value.copy(repeatMode = mode) }

    fun toggleShuffle() { _state.value = _state.value.copy(shuffle = !_state.value.shuffle) }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        preloadJob?.cancel()
        preloadCache.clear()
        preloadedForIndex = -1
        _state.value = PlaybackState()
        _lyrics.value = emptyList()
    }

    // MARK: - MV

    /** Play an MV video url on the same player (UI attaches a PlayerView for output). */
    fun playMvUrl(url: String) {
        resolveJob?.cancel()
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
        _state.value = _state.value.copy(isMv = true, resolving = false, error = null)
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
            player.seekTo(0); player.play(); return
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

    /** Start the MediaSessionService once, so background audio + remote controls work. */
    private fun ensureService() {
        if (serviceStarted) return
        serviceStarted = true
        runCatching {
            ContextCompat.startForegroundService(appContext, Intent(appContext, PlaybackService::class.java))
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
                if (player.isPlaying || _state.value.isMv) {
                    _state.value = _state.value.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0),
                        durationMs = player.duration.coerceAtLeast(0),
                    )
                }
                delay(1000)
            }
        }
    }

    fun release() {
        resolveJob?.cancel()
        player.release()
    }

    /** Current ExoPlayer audio session id — what an `Equalizer` etc must bind to. */
    val audioSessionId: Int get() = player.audioSessionId
}
