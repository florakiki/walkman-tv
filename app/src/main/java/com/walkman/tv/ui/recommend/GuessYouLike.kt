package com.walkman.tv.ui.recommend

import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.source.catalog.Boards
import com.walkman.tv.source.catalog.Catalogs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/**
 * 猜你喜欢：不用 QQ 账号，用本机播放历史 + 收藏推。
 * 常听的歌手（历史 + 收藏里出现次数最多的前 3 个歌手）→ 走 Catalogs.searchAll
 * （四平台并行拼，哪个平台能播就播哪个）搜同歌手的其他歌，排掉已经听过 / 收藏的，
 * 再按“歌名 + 歌手”去重（同一首歌多个平台各有一条），凑 30 首。
 * 搜不到新歌、或本地没历史也没收藏时，用热门榜兜底（各平台热歌榜并行拉，先回来的
 * 有货那家就用），不空着。
 * 界面上不显示平台来源：这里只出 Track 列表，来源只留在 Track.source 里供播放用。
 */
suspend fun buildGuessYouLike(
    catalogs: Catalogs,
    boards: Boards,
    homeSources: Set<SourceID>,
    history: List<Track>,
    loved: List<Track>,
): List<Track> {
    val knownIds = (history.map { it.id } + loved.map { it.id }).toHashSet()
    // 歌手计数：历史取前 100 首 + 全部收藏；“周杰伦 / 林俊杰”这种合唱拆成两个人各记一次。
    val singerCount = LinkedHashMap<String, Int>()
    (history.take(100) + loved).forEach { t ->
        t.singer.split("/", "、", "&", ",", "，").map { it.trim() }
            .filter { it.isNotEmpty() && it != "未知" && it != "未知歌手" }
            .forEach { singerCount[it] = (singerCount[it] ?: 0) + 1 }
    }
    val topSingers = singerCount.entries.sortedByDescending { it.value }.take(3).map { it.key }
    if (topSingers.isNotEmpty()) {
        // 第二十四轮：3 个歌手一起查（以前是串行逐个 searchAll，每个都要等最慢的那个
        // 平台）。结果仍按歌手顺序合并、去重逻辑不变，出来的列表和以前一样。
        val results: List<List<Track>> = coroutineScope {
            topSingers.map { name ->
                async { runCatching { catalogs.searchAll(name, 1) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }
        val out = mutableListOf<Track>()
        val seenIds = HashSet<String>()
        val seenKeys = HashSet<String>()
        for ((index, name) in topSingers.withIndex()) {
            if (out.size >= 30) break
            for (t in results[index]) {
                if (out.size >= 30) break
                if (t.id in knownIds || !seenIds.add(t.id)) continue
                // 只要这个歌手的歌：searchAll 是跨平台拼的，混进来的同名无关结果不要。
                if (!t.singer.contains(name)) continue
                if (!seenKeys.add("${t.name}|${t.singer}")) continue
                out += t
            }
        }
        if (out.isNotEmpty()) return out
    }
    return hotFallback(boards, homeSources)
}

/** 热门兜底：各平台热歌榜并行拉，先回来的有货那家就用；全空才回空。 */
private suspend fun hotFallback(boards: Boards, homeSources: Set<SourceID>): List<Track> {
    val bangid = mapOf(
        SourceID.TX to "26",
        SourceID.KG to "8888",
        SourceID.WY to "3778678",
        SourceID.KW to "16",
    )
    val sources = SourceID.homePageOrder.filter { it in homeSources }
    if (sources.isEmpty()) return emptyList()
    return coroutineScope {
        val pending = sources.map { s ->
            async {
                val id = bangid[s] ?: return@async emptyList<Track>()
                runCatching { boards.serviceFor(s)?.fetchTracks(id, 1) }
                    .getOrNull().orEmpty()
            }
        }.toMutableList()
        // 先回来的有货那家就用；回空的继续等下一家；全空才回空。
        while (pending.isNotEmpty()) {
            val got = select<List<Track>> {
                pending.forEach { d -> d.onAwait { it } }
            }
            if (got.isNotEmpty()) {
                pending.forEach { it.cancel() }
                return@coroutineScope got.take(30)
            }
            pending.removeAll { it.isCompleted }
        }
        emptyList()
    }
}
