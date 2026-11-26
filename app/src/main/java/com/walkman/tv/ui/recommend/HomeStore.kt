package com.walkman.tv.ui.recommend

import com.walkman.tv.data.model.BoardInfo
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SonglistTag
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.data.store.JsonStore
import com.walkman.tv.source.catalog.Boards
import com.walkman.tv.source.catalog.Songlists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** A hero banner card — top of the discover page. */
data class HeroItem(val songlist: SonglistInfo, val source: SourceID)

data class HomeState(
    val isLoading: Boolean = false,
    val heroes: List<HeroItem> = emptyList(),
    val recommendations: List<SonglistInfo> = emptyList(),
    val boards: List<BoardInfo> = emptyList(),
    val sources: List<SourceID> = emptyList(),
    /** 每日30首：QQ 巅峰榜日榜（流行指数榜）前 30 首为主，不够时其他平台日榜补齐。每天变，不进磁盘缓存。 */
    val daily30: List<Track> = emptyList(),
)

/**
 * 磁盘缓存的 DTO：SonglistInfo / BoardInfo 是普通 data class（没加 @Serializable），
 * 不动共享模型，缓存层自己转一道。source 只存 key，读回时对不上已知平台的丢掉。
 */
@Serializable
private data class SonglistDto(
    val id: String,
    val source: String,
    val name: String,
    val author: String = "",
    val picURL: String? = null,
    val trackCount: Int? = null,
    val playCount: String? = null,
)

@Serializable
private data class BoardDto(
    val id: String,
    val source: String,
    val bangid: String,
    val name: String,
    val picURL: String? = null,
)

@Serializable
private data class HeroDto(val songlist: SonglistDto, val source: String)

@Serializable
private data class HomeCache(
    val sources: List<String> = emptyList(),
    val heroes: List<HeroDto> = emptyList(),
    val recommendations: List<SonglistDto> = emptyList(),
    val boards: List<BoardDto> = emptyList(),
)

private fun SonglistInfo.toDto() = SonglistDto(id, source.key, name, author, picURL, trackCount, playCount)

private fun SonglistDto.toSonglistInfo(): SonglistInfo? {
    val src = SourceID.fromKey(source) ?: return null
    return SonglistInfo(id, src, name, author, picURL, trackCount, playCount)
}

private fun BoardInfo.toDto() = BoardDto(id, source.key, bangid, name, picURL)

private fun BoardDto.toBoardInfo(): BoardInfo? {
    val src = SourceID.fromKey(source) ?: return null
    return BoardInfo(id, src, bangid, name, picURL)
}

private fun HomeCache.toState(): HomeState = HomeState(
    isLoading = false,
    heroes = heroes.mapNotNull { h ->
        val info = h.songlist.toSonglistInfo() ?: return@mapNotNull null
        val src = SourceID.fromKey(h.source) ?: return@mapNotNull null
        HeroItem(info, src)
    },
    recommendations = recommendations.mapNotNull { it.toSonglistInfo() },
    boards = boards.mapNotNull { it.toBoardInfo() },
    sources = sources.mapNotNull { SourceID.fromKey(it) },
)

/**
 * Backs the discover (home) page right column. Implements the data flow in spec
 * docs/discover-page-spec-android-tv.md §3:
 *   1. Parallel fetch each enabled source's top recommended songlists (1st order, ALL tag, page 1).
 *   2. Hero pool = first songlist of each source.
 *   3. Recommendations row = drop-first interleave across sources, take 12.
 *   4. Boards row = quota-balanced across sources, target total 12.
 *
 * Re-runs only when the set of enabled sources actually changed (sorted-joined keys), so flipping
 * an unrelated setting doesn't refetch.
 *
 * 启动空白修法：构造时同步读磁盘缓存（home-cache.json，小文件）给 _state 垫底，
 * 第一帧就按上次的段落画，不等网络；后台仍走 loadIfNeeded 拉新数据刷新，拉到了
 * 再写盘。缓存里 sources 变了也不怕——垫底的只是第一帧，真实加载一到就按新源重画。
 */
class HomeStore(
    private val songlists: Songlists,
    private val boards: Boards,
    cacheFile: File? = null,
) {
    private val cacheStore: JsonStore<HomeCache>? =
        cacheFile?.let { JsonStore(it, HomeCache.serializer(), HomeCache()) }

    private val _state = MutableStateFlow(cacheStore?.load()?.toState() ?: HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null

    /**
     * Identity of the last load — sorted joined source keys. Re-loading the same set is a no-op.
     * 注意：故意不拿缓存里的 sources 来初始化它——初始必须为空，这样缓存垫底后首次
     * 真实 loadIfNeeded 一定会触发（key != ""），不会被缓存“骗”过去而跳过首刷。
     */
    private var lastSourcesKey: String = ""

    fun loadIfNeeded(homeSources: Set<SourceID>, force: Boolean = false) {
        val srcs = SourceID.homePageOrder.filter { it in homeSources }
        val key = srcs.joinToString(",") { it.key }
        if (!force && key == lastSourcesKey && !_state.value.isLoading) return
        lastSourcesKey = key
        load(srcs)
    }

    private fun load(srcs: List<SourceID>) {
        loadJob?.cancel()
        if (srcs.isEmpty()) {
            _state.update {
                it.copy(
                    isLoading = false, heroes = emptyList(), recommendations = emptyList(),
                    boards = emptyList(), sources = emptyList(), daily30 = emptyList(),
                )
            }
            return
        }
        loadJob = scope.launch {
            _state.update { it.copy(isLoading = true, sources = srcs) }

            // 推荐歌单切 QQ（2026-11-26 Yang 定）：勾了 QQ 时只走 QQSonglist（最热，ALL tag，
            // 第 1 页 36 张，取 13 张：1 张 hero + 12 张推荐）；QQ 拉空了（接口挂了）或没勾 QQ
            // 时才回落到原来的四平台混拼，不给半空的首页。
            val qqSonglists: List<SonglistInfo> = if (SourceID.TX in srcs) {
                val svc = songlists.serviceFor(SourceID.TX)
                val order = svc?.orders?.firstOrNull()
                if (svc == null || order == null) emptyList()
                else runCatching { svc.fetchRecommended(order, SonglistTag.ALL, 1) }
                    .getOrDefault(emptyList())
            } else emptyList()
            val heroes: List<HeroItem>
            val recommendations: List<SonglistInfo>
            if (qqSonglists.isNotEmpty()) {
                heroes = listOf(HeroItem(qqSonglists.first(), SourceID.TX))
                recommendations = qqSonglists.drop(1).take(12)
            } else {
                // Section 3 step 1: parallel songlist fetch per source.
                val groups: Map<SourceID, List<SonglistInfo>> = coroutineScope {
                    srcs.map { s ->
                        async {
                            val svc = songlists.serviceFor(s)
                            if (svc == null) return@async s to emptyList()
                            val order = svc.orders.firstOrNull() ?: return@async s to emptyList()
                            val list = runCatching { svc.fetchRecommended(order, SonglistTag.ALL, 1) }
                                .getOrDefault(emptyList())
                            s to list
                        }
                    }.awaitAll().toMap()
                }

                // Step 2: hero pool — 1 per enabled source (the first of its recommendations).
                heroes = srcs.mapNotNull { s -> groups[s]?.firstOrNull()?.let { HeroItem(it, s) } }

                // Step 3: recommendation tail interleave — never two in a row from the same source.
                val tails = srcs.associateWith { groups[it].orEmpty().drop(1).take(8) }
                val recs = mutableListOf<SonglistInfo>()
                val maxSize = tails.values.maxOfOrNull { it.size } ?: 0
                for (i in 0 until maxSize) {
                    for (s in srcs) tails[s]?.getOrNull(i)?.let { recs += it }
                }
                recommendations = recs.take(12)
            }

            // 排行榜切 QQ（同上）：勾了 QQ 时只走 QQBoard 的 15 张榜，取前 12；
            // QQ 拉空 / 没勾时回落到原来的 quota-balanced 四平台混拼。
            val boardList: List<BoardInfo> = run {
                val qqBoards: List<BoardInfo> = if (SourceID.TX in srcs) {
                    runCatching { boards.serviceFor(SourceID.TX)?.fetchBoards() }
                        .getOrNull().orEmpty()
                } else emptyList()
                if (qqBoards.isNotEmpty()) {
                    qqBoards.take(12)
                } else {
                    // Step 4: boards — parallel fetch, then quota-balance across enabled sources.
                    val boardsBySource: Map<SourceID, List<BoardInfo>> = coroutineScope {
                        srcs.map { s ->
                            async {
                                val svc = boards.serviceFor(s)
                                if (svc == null) return@async s to emptyList()
                                s to runCatching { svc.fetchBoards() }.getOrDefault(emptyList())
                            }
                        }.awaitAll().toMap()
                    }
                    balanceBoards(srcs, boardsBySource, totalTarget = 12)
                }
            }

            _state.update {
                it.copy(
                    isLoading = false,
                    heroes = heroes,
                    recommendations = recommendations,
                    boards = boardList,
                    sources = srcs,
                )
            }
            saveCache(heroes, recommendations, boardList, srcs)

            // 每日30首单独拉：先把歌单/排行榜刷出来，daily30 到了再单独更新一次 state。
            // 酷狗/网易的日榜接口奇慢（M 实测 14–30 秒），不等它们拖住上面那次刷新。
            // daily30 不进 home-cache.json：每天变，缓存会放旧歌；首帧这区先空着，拉到再出。
            val daily = fetchDaily30(srcs)
            _state.update { it.copy(daily30 = daily) }
        }
    }

    /**
     * 各平台“日榜”榜单 id：QQ 流行指数榜（topid=4）每天更新，是每日30首的主力；
     * 其余平台没有日榜概念，用各自的热歌榜当同义日榜补齐。
     */
    private fun dailyBoardId(source: SourceID): String? = when (source) {
        SourceID.TX -> "4"
        SourceID.KG -> "8888"
        SourceID.WY -> "3778678"
        SourceID.KW -> "16"
        else -> null
    }

    /**
     * 每日30首：QQ 流行指数榜前 30 首为主。QQ 官方没有能用的每日歌单接口
     * （musicu GetDayRecommend 回 500003 要鉴权；搜到的“每日30首”歌单是用户自建的，
     * 一个 280 首、一个 13 首，都不是每天更新的 30 首），所以按任务要求走 QQ 巅峰榜日榜拼 30 首。
     * QQ 够 30 首直接回，不等酷狗/网易那两路慢接口；QQ 不够 / 没勾时其他平台的日榜并行补齐。
     */
    private suspend fun fetchDaily30(srcs: List<SourceID>): List<Track> {
        val out = mutableListOf<Track>()
        val seen = HashSet<String>()
        fun add(tracks: List<Track>) {
            for (t in tracks) {
                if (out.size >= 30) return
                if (seen.add(t.id)) out += t
            }
        }
        if (SourceID.TX in srcs) {
            val qqTracks = runCatching { boards.serviceFor(SourceID.TX)?.fetchTracks("4", 1) }
                .getOrNull().orEmpty()
            add(qqTracks)
            if (out.size >= 30) return out
        }
        val rest = srcs.filter { it != SourceID.TX }
        if (rest.isNotEmpty() && out.size < 30) {
            val got: List<List<Track>> = coroutineScope {
                rest.map { s ->
                    async {
                        val bangid = dailyBoardId(s) ?: return@async emptyList()
                        val svc = boards.serviceFor(s) ?: return@async emptyList()
                        runCatching { svc.fetchTracks(bangid, 1) }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
            got.forEach { add(it) }
        }
        return out
    }

    /**
     * 真实加载成功后把段落写盘，供下次冷启动垫底。全拉空了不写：断网 / 接口挂了时
     * 别把好缓存顶掉，下次启动还能拿旧段落先画出来。
     */
    private suspend fun saveCache(
        heroes: List<HeroItem>,
        recommendations: List<SonglistInfo>,
        boards: List<BoardInfo>,
        srcs: List<SourceID>,
    ) {
        val store = cacheStore ?: return
        if (heroes.isEmpty() && recommendations.isEmpty() && boards.isEmpty()) return
        withContext(Dispatchers.IO) {
            store.save(
                HomeCache(
                    sources = srcs.map { it.key },
                    heroes = heroes.map { HeroDto(it.songlist.toDto(), it.source.key) },
                    recommendations = recommendations.map { it.toDto() },
                    boards = boards.map { it.toDto() },
                ),
            )
        }
    }

    /**
     * Quota algorithm (spec §3): each source gets `totalTarget / n` slots; remaining slots are
     * round-robin'd to whichever source still has more to offer. Bail after 2n cursor laps to
     * avoid infinite loops when every source is exhausted.
     */
    private fun balanceBoards(
        srcs: List<SourceID>,
        bySource: Map<SourceID, List<BoardInfo>>,
        totalTarget: Int,
    ): List<BoardInfo> {
        val n = srcs.size
        if (n == 0) return emptyList()
        val perSource = (totalTarget / n).coerceAtLeast(1)
        val picked: Map<SourceID, MutableList<BoardInfo>> = srcs.associateWith { s ->
            bySource[s].orEmpty().take(perSource).toMutableList()
        }
        var remaining = totalTarget - picked.values.sumOf { it.size }
        var cursor = 0
        while (remaining > 0 && cursor <= 2 * n) {
            val s = srcs[cursor % n]
            cursor++
            val current = picked[s]!!.size
            val available = bySource[s]?.size ?: 0
            if (current < available) {
                picked[s]!!.add(bySource[s]!![current])
                remaining--
            }
        }
        // Stable per-source order then flatten.
        return srcs.flatMap { picked[it].orEmpty() }
    }
}
