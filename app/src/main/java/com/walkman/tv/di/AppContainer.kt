package com.walkman.tv.di

import android.content.Context
import com.walkman.tv.data.store.LibraryStore
import com.walkman.tv.data.store.PlaybackSnapshotStore
import com.walkman.tv.data.store.ScriptStore
import com.walkman.tv.data.store.SearchHistoryStore
import com.walkman.tv.data.store.SettingsStore
import com.walkman.tv.playback.EqualizerManager
import com.walkman.tv.playback.LyricsFetcher
import com.walkman.tv.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.walkman.tv.source.OtherSourceFinder
import com.walkman.tv.source.SourceManager
import com.walkman.tv.source.builtin.BuiltInLyricResolver
import com.walkman.tv.source.builtin.BuiltInResolver
import com.walkman.tv.source.catalog.Artists
import com.walkman.tv.source.catalog.Boards
import com.walkman.tv.source.catalog.CatalogHttp
import com.walkman.tv.source.catalog.Catalogs
import com.walkman.tv.source.catalog.MvResolver
import com.walkman.tv.source.catalog.Songlists
import com.walkman.tv.source.js.ScriptHttpClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled dependency container (no Hilt). Holds app-wide singletons; created once in [App].
 */
class AppContainer(val appContext: Context) {

    /** Process-wide event bus for hardware-key events (e.g. KEYCODE_MENU). */
    val events: AppEvents = AppEvents()

    /** In-app HTTP server backing the phone-to-TV QR flow. null when start failed. */
    @Volatile var localServer: LocalServer? = null
        private set

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val preload: String by lazy {
        appContext.assets.open("script/user-api-preload.js").use { it.readBytes().toString(Charsets.UTF_8) }
    }

    val catalogHttp by lazy { CatalogHttp(httpClient) }

    val catalogs by lazy { Catalogs(catalogHttp) }
    /** 歌手 + 专辑详情（ArtistCatalog：QQ/KG/KW/WY 直连接口，Ki.js 里没有）。 */
    val artists by lazy { Artists(catalogHttp, catalogs) }
    val hotSearch by lazy { com.walkman.tv.source.catalog.HotSearch(catalogHttp) }
    val boards by lazy { Boards(catalogHttp) }
    val songlists by lazy { Songlists(catalogHttp) }
    /** Backs the recommend (discover) right column — heroes / recommendations / boards. */
    val homeStore by lazy { com.walkman.tv.ui.recommend.HomeStore(songlists, boards) }
    val mvResolver by lazy { MvResolver(catalogHttp) }

    private val scriptHttp by lazy { ScriptHttpClient(httpClient) }
    private val builtInResolver by lazy { BuiltInResolver(httpClient) }
    private val builtInLyricResolver by lazy { BuiltInLyricResolver(catalogHttp) }
    private val otherSourceFinder by lazy { OtherSourceFinder(catalogs) }

    val sourceManager: SourceManager by lazy {
        SourceManager(preload, scriptHttp, otherSourceFinder, builtInResolver)
    }

    private val lyricsFetcher by lazy {
        LyricsFetcher(sourceManager, builtInLyricResolver, otherSourceFinder)
    }

    /** Created eagerly on the main thread in [App] (ExoPlayer needs a consistent looper). */
    lateinit var playbackController: PlaybackController
        private set

    val equalizerManager: EqualizerManager by lazy {
        EqualizerManager { playbackController.audioSessionId }
    }

    /** In-app updater — checks GitHub Releases, downloads the ABI-matched APK, launches install. */
    val updateManager: com.walkman.tv.playback.update.UpdateManager by lazy {
        com.walkman.tv.playback.update.UpdateManager(appContext, httpClient)
    }

    val scriptStore: ScriptStore by lazy { ScriptStore(appContext, sourceManager) }
    val libraryStore: LibraryStore by lazy { LibraryStore(appContext) }
    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }
    val playbackSnapshotStore: PlaybackSnapshotStore by lazy { PlaybackSnapshotStore(appContext) }
    val searchHistoryStore: SearchHistoryStore by lazy { SearchHistoryStore(appContext) }

    /** 局域网同步身份 + 配对设备（sync.json）。 */
    val syncPeerStore: com.walkman.tv.data.sync.SyncPeerStore by lazy {
        com.walkman.tv.data.sync.SyncPeerStore(appContext)
    }
    /** mDNS 广播 / 发现（电视和手机互相看到对方）。 */
    val syncDiscovery: com.walkman.tv.data.sync.NsdSyncDiscovery by lazy {
        com.walkman.tv.data.sync.NsdSyncDiscovery(appContext)
    }
    /** LocalServer 同步接口的回调：导出 / 合并本机歌单、验同步码。 */
    val syncHandler: SyncHandler by lazy {
        object : SyncHandler {
            override fun infoJson(): String {
                val data = syncPeerStore.data.value
                return kotlinx.serialization.json.Json.encodeToString(
                    com.walkman.tv.data.sync.SyncInfo.serializer(),
                    com.walkman.tv.data.sync.SyncInfo(
                        name = data.deviceName,
                        deviceId = data.deviceId,
                        version = updateManager.currentVersion,
                    ),
                )
            }

            override fun exportJson(): String = libraryStore.exportBackupJson()

            override fun importJson(raw: String): com.walkman.tv.data.sync.MergeReport =
                kotlinx.coroutines.runBlocking { libraryStore.mergeBackupJson(raw) }.getOrThrow()

            override fun checkToken(token: String?): Boolean = syncPeerStore.checkToken(token)
        }
    }
    val coverCache: com.walkman.tv.data.store.CoverCache by lazy {
        com.walkman.tv.data.store.CoverCache(appContext)
    }
    val downloadStore: com.walkman.tv.data.store.DownloadStore by lazy {
        com.walkman.tv.data.store.DownloadStore(appContext)
    }
    val localFolderStore: com.walkman.tv.data.store.LocalFolderStore by lazy {
        com.walkman.tv.data.store.LocalFolderStore(appContext)
    }
    val downloadCoordinator: com.walkman.tv.playback.download.DownloadCoordinator by lazy {
        com.walkman.tv.playback.download.DownloadCoordinator(
            store = downloadStore,
            sources = sourceManager,
            coverCache = coverCache,
            http = httpClient,
            lyricsFetcher = lyricsFetcher,
            catalogHttp = catalogHttp,
        )
    }
    val localMusicStore: com.walkman.tv.playback.local.LocalMusicStore by lazy {
        com.walkman.tv.playback.local.LocalMusicStore(
            context = appContext,
            localFolderStore = localFolderStore,
            coverCache = coverCache,
        )
    }

    /** Process-lived scope used for operations that mustn't be cancelled by a UI navigation
     *  (e.g. settings → script delete). Public so screens can opt into it explicitly. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun initPlayback() {
        if (!::playbackController.isInitialized) {
            playbackController = PlaybackController(
                appContext,
                sourceManager,
                lyricsFetcher,
                com.walkman.tv.playback.AudioSpecProbe(httpClient),
            )
            playbackController.onTrackStarted = { track ->
                appScope.launch { libraryStore.recordHistory(track) }
            }
            // Local-first URL resolver — downloads + SAF imports skip the online cascade.
            // Doubles as the play-time file check: if a COMPLETED download's file is gone we
            // flag it missing (badge disappears, 已下载 tab shows 文件缺失) and fall through to
            // the online cascade; if it's there we clear any stale missing flag. The file also
            // gets a size/header sanity check first — a 0-byte or HTML-error-page file must
            // never reach the native decoder (SIGSEGV, uncatchable); it falls through to the
            // online cascade instead.
            playbackController.localUrlResolver = { track ->
                // Resolves both File downloads and SAF-exported downloads to a playable Uri.
                val playable = downloadStore.localPlayableUri(track.id)
                when {
                    playable != null && isSaneLocalAudio(playable) -> {
                        downloadStore.markPresent(track.id)
                        playable
                    }
                    else -> {
                        if (downloadStore.isDownloaded(track.id)) downloadStore.markMissing(track.id)
                        localMusicStore.fileUri(track)?.toString()?.takeIf { isSaneLocalAudio(it) }
                    }
                }
            }
        }
    }

    /** Play-time sanity check for a local audio file (file:// or content://). Returns false
     *  for a 0-byte file, an unreadable file, or a file whose header is an error page / text
     *  instead of audio — those must fall back to the online cascade, never to the decoder.
     *  Unknown *binary* headers are allowed through so exotic-but-valid codecs aren't
     *  false-rejected; only known-bad (HTML/JSON/text) is blocked. */
    private fun isSaneLocalAudio(uriString: String): Boolean {
        val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull() ?: return false
        val header: ByteArray? = when (uri.scheme?.lowercase()) {
            "file" -> runCatching {
                val f = java.io.File(uri.path ?: return false)
                if (!f.isFile || f.length() == 0L) return false
                f.inputStream().use { readHeader(it) }
            }.getOrNull()
            "content" -> runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { readHeader(it) }
            }.getOrNull()
            else -> return false
        }
        if (header == null || header.isEmpty()) return false
        return isAudioHeader(header)
    }

    /** Read up to 16 leading bytes (manual loop — InputStream.readNBytes needs API 33). */
    private fun readHeader(input: java.io.InputStream): ByteArray? {
        val buf = ByteArray(16)
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        return if (read == 0) null else buf.copyOf(read)
    }

    private fun isAudioHeader(h: ByteArray): Boolean {
        fun ascii(off: Int, s: String): Boolean {
            if (h.size < off + s.length) return false
            return s.indices.all { h[off + it] == s[it].code.toByte() }
        }
        // Error pages / text are never audio.
        if (h[0] == '<'.code.toByte() || h[0] == '{'.code.toByte() || h[0] == '['.code.toByte()) return false
        // Known audio magic: ID3/FLAC/Ogg/RIFF-WAVE/AIFF/APE/WavPack/DSF, MP4 ftyp.
        if (ascii(0, "ID3") || ascii(0, "fLaC") || ascii(0, "OggS") || ascii(0, "RIFF") ||
            ascii(0, "FORM") || ascii(0, "MAC ") || ascii(0, "wvpk") || ascii(0, "DSD ") ||
            ascii(4, "ftyp") || ascii(8, "WAVE")
        ) return true
        // MP3 / AAC-ADTS frame sync (11 leading 1-bits).
        if (h.size >= 2 && h[0] == 0xFF.toByte() && (h[1].toInt() and 0xE0) == 0xE0) return true
        // ASF / WMA GUID.
        if (h.size >= 4 && h[0] == 0x30.toByte() && h[1] == 0x26.toByte() &&
            h[2] == 0xB2.toByte() && h[3] == 0x75.toByte()
        ) return true
        // Unknown binary: allow (don't false-reject exotic codecs). Pure printable ASCII
        // text: treat as an error page and block.
        val printableAscii = h.all {
            val v = it.toInt() and 0xFF
            v in 0x09..0x0D || v in 0x20..0x7E
        }
        return !printableAscii
    }

    // Hot-search JSON cache for the phone page (/api/hotsearch). Fetched at most once per 5 min;
    // called from NanoHTTPD's worker thread, so blocking here is fine.
    @Volatile private var hotSearchCache: String = "[]"
    @Volatile private var hotSearchCachedAt: Long = 0L

    /** Blocking hot-search JSON: [{"source":"kw","name":"酷我","words":[...]}, ...]. */
    private fun hotSearchJsonBlocking(): String {
        val now = System.currentTimeMillis()
        if (now - hotSearchCachedAt < 5 * 60_000L && hotSearchCache != "[]") return hotSearchCache
        val json = runCatching {
            kotlinx.coroutines.runBlocking {
                val cols = hotSearch.fetchAll()
                buildString {
                    append('[')
                    cols.forEachIndexed { i, col ->
                        if (i > 0) append(',')
                        append("{\"source\":\"").append(col.source.key)
                        append("\",\"name\":\"").append(col.source.displayName)
                        append("\",\"words\":[")
                        col.words.forEachIndexed { j, w ->
                            if (j > 0) append(',')
                            append('"').append(jsonEscape(w)).append('"')
                        }
                        append("]}")
                    }
                    append(']')
                }
            }
        }.getOrDefault("[]")
        if (json != "[]") { hotSearchCache = json; hotSearchCachedAt = now }
        return json
    }

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    /** Fetch a remote text resource (used to import a custom-source script from a URL). */
    suspend fun fetchText(url: String): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        okhttp3.Request.Builder().url(url).build().let { req ->
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                resp.body?.string() ?: throw IllegalStateException("空响应")
            }
        }
    }

    /** Load persisted data and wire settings → playback. Called once at startup. */
    fun bootstrap() {
        // Bring up the LAN HTTP server (best-effort; QR features just won't work if it fails).
        if (localServer == null) {
            localServer = LocalServer.start(
                events,
                hotSearchJson = { hotSearchJsonBlocking() },
                sync = syncHandler,
            )
        }
        appScope.launch {
            settingsStore.loadAll()
            libraryStore.loadAll()
            syncPeerStore.loadAll()
            // 广播本机同步服务，让同一 WiFi 下的另一台设备在同步页里直接看到本机。
            localServer?.let { srv ->
                syncDiscovery.advertise(srv.boundPort, syncPeerStore.data.value.deviceName)
            }
            settingsStore.settings.onEach { s ->
                playbackController.preferredQuality = s.preferredQuality
                playbackController.builtinGainEnabled = s.builtinGainEnabled
                // 硬件直通会把未解码的数据直接交给音频硬件，绕过解码器；强制软解时一并关掉。
                playbackController.setAudioOffloadEnabled(s.audioOffloadEnabled && !s.forceSoftwareDecoding)
                playbackController.forceSoftwareDecoding = s.forceSoftwareDecoding
                sourceManager.fallbackEnabled = s.fallbackEnabled
                downloadStore.configuredRoot = s.customDownloadDir?.let { java.io.File(it) }
                downloadStore.downloadTreeUri = s.customDownloadTreeUri?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
                downloadCoordinator.setMaxConcurrent(s.maxConcurrentDownloads)
            }.launchIn(appScope)
            scriptStore.loadAll()
            searchHistoryStore.load()
            coverCache.loadAll()
            downloadStore.loadAll()
            localFolderStore.loadAll()

            // Restore last session's queue + index + playing state. Run after scripts load so
            // any custom-source needed to resolve URLs is ready.
            val snapshot = playbackSnapshotStore.load()
            playbackController.restoreSnapshot(snapshot)

            // Auto-save the snapshot whenever queue/index/isPlaying changes. distinctUntilChanged
            // already coalesces the high-frequency position ticks since we only project the three
            // fields that matter here.
            playbackController.state
                .map { Triple(it.queue.map { t -> t.id }, it.index, it.isPlaying) }
                .distinctUntilChanged()
                .onEach { playbackSnapshotStore.save(playbackController.snapshot()) }
                .launchIn(appScope)
        }
    }

    /** Synchronous final save — called right before the process is killed on user-confirmed exit. */
    fun saveSnapshotNow() {
        if (::playbackController.isInitialized) {
            runCatching { playbackSnapshotStore.saveBlocking(playbackController.snapshot()) }
        }
    }
}
