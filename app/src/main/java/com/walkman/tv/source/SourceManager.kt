package com.walkman.tv.source

import android.util.Log
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.ScriptCapabilities
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.data.model.UserScript
import com.walkman.tv.source.builtin.BuiltInResolver
import com.walkman.tv.source.js.JsScriptRuntime
import com.walkman.tv.source.js.ScriptHttpClient
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns loaded user scripts and resolves play URLs, ported from iOS `SourceManager`.
 * Resolve order: user script (one call — the script itself falls back through
 * quality tiers internally) → other-source search (parallel race) → built-in direct.
 */
class SourceManager(
    private val preload: String,
    private val http: ScriptHttpClient,
    private val otherSourceFinder: OtherSourceFinder,
    private val builtIn: BuiltInResolver,
) {
    data class LoadedScript(
        val script: UserScript,
        val runtime: JsScriptRuntime,
        val capabilities: ScriptCapabilities,
    )

    private val _loaded = MutableStateFlow<List<LoadedScript>>(emptyList())
    val loaded: StateFlow<List<LoadedScript>> = _loaded.asStateFlow()

    @Volatile var fallbackEnabled: Boolean = true

    /** 播不起来时抛的异常。attemptedSources：这次解析试过哪些源（只记日志用，
     *  播放失败写进诊断记录时取，见 CrashDiagnostics）。 */
    class SourceException(message: String, val attemptedSources: List<String> = emptyList()) : Exception(message)

    suspend fun load(script: UserScript): Result<ScriptCapabilities> {
        return try {
            val runtime = JsScriptRuntime(script, preload, http)
            val caps = runtime.load()
            _loaded.value = _loaded.value.filter { it.script.id != script.id } +
                LoadedScript(script, runtime, caps)
            Result.success(caps)
        } catch (e: Exception) {
            Log.e(TAG, "load failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun unload(scriptId: String) {
        _loaded.value.firstOrNull { it.script.id == scriptId }?.runtime?.destroy()
        _loaded.value = _loaded.value.filter { it.script.id != scriptId }
    }

    private fun scriptsFor(source: SourceID, action: String): List<LoadedScript> =
        _loaded.value.filter { it.capabilities.sources[source]?.actions?.contains(action) == true }

    private fun scriptFor(source: SourceID, action: String): LoadedScript? =
        scriptsFor(source, action).firstOrNull()

    /** Resolve a play URL — mirrors `resolveMusicURL`. */
    suspend fun resolveMusicURL(track: Track, quality: Quality? = null): ResolvedTrack {
        val preferred = quality ?: Quality.K320

        // 这次解析试过哪些源：只记日志用（真播不了时写进诊断记录），不参与换源决策。
        // 成功挂在 ResolvedTrack 上、失败挂在 SourceException 上，调用方按需取。
        val attempted = mutableListOf<String>()

        val scripts = scriptsFor(track.source, "musicUrl")
        scripts.forEachIndexed { scriptIdx, ls ->
            attempted += "${track.source.displayName}（脚本 ${ls.script.name}）"
            val scriptQs = ls.capabilities.sources[track.source]?.qualities?.toSet() ?: emptySet()
            val first = pickPlayQuality(preferred, track.qualities.toSet(), scriptQs)
            // 每个脚本只调一次：脚本内部 musicUrl 已经按音质从高到低逐档回落
            // （Ki.js 的 FALLBACK 表），一次调用就把 first 以下所有档都试过了。
            // 之前按档逐个调：失败时同一批死接口要重复打 3–4 遍（实测酷狗 flac
            // 一档就要 3 秒 500 失败 + 2 秒才轮到能用的接口），一首歌能静默几十秒。
            // 快失败直接换下一个源/换源；脚本 15 秒无应答就跳过它。
            runCatching { resolveViaScript(track, first, ls) }
                .onSuccess { url ->
                    val warn = if (scriptIdx > 0) "已切换到音源「${ls.script.name}」" else null
                    return ResolvedTrack(url, ResolveOrigin.Script(ls.script.name), first, warn, attempted.distinct())
                }
                .onFailure {
                    Log.d(TAG, "script '${ls.script.name}' @${first.key} failed: ${it.message}")
                }
        }

        tryOtherSources(track, preferred, attempted)?.let { return it }

        if (fallbackEnabled) {
            attempted += "内置直连"
            val qForFallback = if (track.qualities.contains(preferred)) preferred else (track.qualities.firstOrNull() ?: Quality.K128)
            runCatching { builtIn.resolve(track, qForFallback) }.getOrNull()?.let { res ->
                return ResolvedTrack(res.url, ResolveOrigin.DirectFallback, qForFallback, res.warning, attempted.distinct())
            }
        }

        if (scriptFor(track.source, "musicUrl") == null) {
            throw SourceException("没有可用的播放源，请到设置 → 自定义音源 导入脚本", attempted.distinct())
        }
        throw SourceException("这首歌无法获取播放地址，换一首或换个能用的脚本", attempted.distinct())
    }

    /** Play-time cross-source fallback (第 6 条)：解析时拿到的地址播起来才发现是坏的，
     *  换其它平台的同一首歌再试。解析阶段的换源在 resolveMusicURL 里已经做了，这里是
     *  播放器报错后才走的第二道。 */
    suspend fun resolveOtherSource(track: Track, preferred: Quality): ResolvedTrack? {
        // 播时换源单独记一份试过的源（成功时挂在 ResolvedTrack 上带回去）。
        val attempted = mutableListOf<String>()
        return tryOtherSources(track, preferred, attempted)
    }

    private suspend fun tryOtherSources(
        track: Track,
        preferred: Quality,
        attempted: MutableList<String>,
    ): ResolvedTrack? {
        val alternatives = otherSourceFinder.findMatches(track)
        if (alternatives.isEmpty()) return null
        val raced = alternatives.take(5)
        raced.forEach { attempted += it.source.displayName }
        // 候选并行抢答：之前逐个串行解析，每个候选最多等 25 秒脚本超时，
        // 5 个候选最坏 125 秒静默。现在 5 个同时解析，第一个成功的就用，其余取消，
        // 最坏也就一个 25 秒的窗口。JsScriptRuntime 的 requestAction 用
        // ConcurrentHashMap 按 UUID 挂起，ScriptHttpClient 每个请求是独立的
        // OkHttp Call，并行安全。
        return coroutineScope {
            val deferreds = raced.map { alt ->
                async {
                    val altLS = scriptFor(alt.source, "musicUrl") ?: return@async null
                    val q = pickPlayQuality(
                        preferred,
                        alt.qualities.toSet(),
                        altLS.capabilities.sources[alt.source]?.qualities?.toSet() ?: emptySet(),
                    )
                    runCatching { resolveViaScript(alt, q, altLS) }.getOrNull()?.let { url ->
                        ResolvedTrack(
                            url,
                            ResolveOrigin.OtherSource(alt.source),
                            q,
                            "当前源无法获取播放地址，已自动换源",
                            attempted.distinct(),
                        )
                    }
                }
            }
            var remaining = deferreds
            while (remaining.isNotEmpty()) {
                val (result, done) = select<Pair<ResolvedTrack?, Deferred<ResolvedTrack?>>> {
                    remaining.forEach { d -> d.onAwait { it to d } }
                }
                if (result != null) {
                    remaining.forEach { it.cancel() }
                    return@coroutineScope result
                }
                remaining -= done
            }
            null
        }
    }

    private suspend fun resolveViaScript(track: Track, quality: Quality, ls: LoadedScript): String {
        val info = JSONObject()
            .put("type", quality.key)
            .put("musicInfo", makeOldMusicInfo(track))
        val result = ls.runtime.requestAction(track.source, "musicUrl", info)
        val obj = result as? JSONObject
        val url = obj?.optJSONObject("data")?.optString("url")?.ifEmpty { null }
            ?: obj?.optString("url")?.ifEmpty { null }
        return url ?: throw SourceException("脚本返回了无效结果")
    }

    /** Ask a user script for lyric data — raw `{lyric, tlyric, rlyric, lxlyric}` (iOS `requestLyric`). */
    suspend fun requestLyric(track: Track): JSONObject? {
        val ls = scriptFor(track.source, "lyric") ?: throw SourceException("没有可用的歌词源")
        val info = JSONObject().put("type", "").put("musicInfo", makeOldMusicInfo(track))
        return ls.runtime.requestAction(track.source, "lyric", info) as? JSONObject
    }

    fun availableQualities(source: SourceID): List<Quality> {
        val set = mutableSetOf<Quality>()
        _loaded.value.forEach { it.capabilities.sources[source]?.qualities?.forEach(set::add) }
        return Quality.entries.filter { set.contains(it) }
    }

    fun supportedSources(): List<SourceID> {
        val set = mutableSetOf<SourceID>()
        _loaded.value.forEach { ls ->
            ls.capabilities.sources.forEach { (src, cap) -> if (cap.actions.contains("musicUrl")) set.add(src) }
        }
        return SourceID.entries.filter { set.contains(it) }
    }

    /** Build the v4 "old format" musicInfo a script expects (iOS `makeOldMusicInfo`). */
    fun makeOldMusicInfo(track: Track): JSONObject {
        val types = JSONArray()
        val typesObj = JSONObject()
        Quality.orderedHighToLow.filter { track.qualities.contains(it) }.forEach { q ->
            types.put(JSONObject().put("type", q.key).put("size", ""))
            typesObj.put(q.key, JSONObject().put("size", ""))
        }
        val info = JSONObject()
            .put("name", track.name)
            .put("singer", track.singer)
            .put("source", track.source.key)
            .put("songmid", track.songmid)
            .put("interval", track.intervalText)
            .put("albumName", track.albumName ?: "")
            .put("img", track.picURL ?: "")
            .put("typeUrl", JSONObject())
            .put("albumId", track.albumId ?: "")
            .put("types", types)
            .put("_types", typesObj)
        track.extras.forEach { (k, v) -> info.put(k, v) }
        return info
    }

    companion object {
        private const val TAG = "SourceManager"

        /**
         * Pick the effective playback tier — spec §3.
         *
         * Walks [Quality.orderedHighToLow] starting at [preferred], returns the first tier
         * that satisfies the per-tier eligibility rule. Falls through to [Quality.K128] —
         * the universal floor — when nothing higher passes.
         *
         * Eligibility:
         *  - Regular tiers (128k/320k/flac/flac24bit): script declares it AND the search
         *    metadata listed it.
         *  - Extended tiers (hires/atmos/atmos_plus/master): script declares it. Catalog
         *    metadata almost never reports these, so the trackQs check is bypassed.
         */
        fun pickPlayQuality(preferred: Quality, trackQs: Set<Quality>, scriptQs: Set<Quality>): Quality {
            val full = Quality.orderedHighToLow
            val startIdx = full.indexOf(preferred).coerceAtLeast(0)
            for (i in startIdx until full.size) {
                val q = full[i]
                if (q == Quality.K128) return Quality.K128 // always reachable
                if (scriptQs.contains(q) && (q.isExtendedTier || trackQs.contains(q))) return q
            }
            return Quality.K128
        }

        /**
         * Build the descending cascade from [first] to [Quality.K128] — spec §3 last paragraph.
         * Kept for reference; resolveMusicURL no longer loops over it — the script itself
         * falls back through tiers internally, so re-calling per tier only repeats the
         * same dead API calls.
         */
        fun qualityCascade(first: Quality, trackQs: Set<Quality>, scriptQs: Set<Quality>): List<Quality> {
            val full = Quality.orderedHighToLow
            val startIdx = full.indexOf(first).coerceAtLeast(0)
            val out = mutableListOf<Quality>()
            for (i in startIdx until full.size) {
                val q = full[i]
                val eligible = q == Quality.K128 ||
                    (scriptQs.contains(q) && (q.isExtendedTier || trackQs.contains(q)))
                if (eligible) out.add(q)
            }
            return out.ifEmpty { listOf(first) }
        }
    }
}
