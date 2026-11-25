package com.walkman.tv.ui.components

import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track

/**
 * 搜索结果里按专辑聚合的一组：同一平台、同一专辑名的歌归到一起。
 *
 * Ki.js / lx-music v4 脚本只声明了 musicUrl、lyric 两个动作，没有歌手专辑接口，
 * 各平台搜索接口也不提供“搜歌手 → 专辑列表”的调用，所以专辑直接从搜索结果里聚合：
 * 每首歌都带 albumName / albumId / 封面，搜歌手出来的歌按专辑一分组就是他的专辑。
 * 点专辑进详情播里面的歌（详情里就是这次搜到的、属于这张专辑的歌）。
 */
data class AlbumGroup(
    val name: String,
    val singer: String,
    val picURL: String?,
    val source: SourceID,
    val tracks: List<Track>,
)

/** 把搜索结果按专辑聚合。没有专辑名的歌不进专辑区（仍在歌曲区能搜到）。按歌数从多到少排。 */
fun groupAlbums(tracks: List<Track>): List<AlbumGroup> {
    val groups = linkedMapOf<String, MutableList<Track>>()
    for (t in tracks) {
        val album = t.albumName?.trim().orEmpty()
        if (album.isEmpty()) continue
        val key = "${t.source.key}|${album.lowercase()}"
        groups.getOrPut(key) { mutableListOf() }.add(t)
    }
    return groups.values.map { list ->
        val first = list.first()
        AlbumGroup(
            name = first.albumName?.trim() ?: "",
            singer = first.singer,
            picURL = list.firstNotNullOfOrNull { it.picURL },
            source = first.source,
            tracks = list,
        )
    }.sortedByDescending { it.tracks.size }
}
