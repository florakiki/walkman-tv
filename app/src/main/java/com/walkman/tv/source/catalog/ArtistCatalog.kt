package com.walkman.tv.source.catalog

import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.ui.components.groupAlbums
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/** 歌手（搜索结果里的歌手卡片 / 歌手页用）。 */
data class ArtistInfo(
    val source: SourceID,
    /** 平台侧歌手 id：QQ singermid、酷狗 singerid、酷我 ARTISTID、网易 artist id。 */
    val id: String,
    val name: String,
    val picURL: String? = null,
    val songCount: Int? = null,
    val albumCount: Int? = null,
)

/** 专辑卡片。detailKey 是拉完整曲目单要用的键：QQ 用 albumMid，其它平台用 albumId。 */
data class AlbumInfo(
    val source: SourceID,
    val id: String,
    val name: String,
    val singer: String,
    val picURL: String? = null,
    val songCount: Int? = null,
    val detailKey: String = id,
    /** 拉不到完整曲目单的平台（酷我 / 网易）用搜索结果里聚合出来的歌兜底。 */
    val fallbackTracks: List<Track> = emptyList(),
)

/** 歌手页：热门歌曲 + 专辑列表。 */
data class ArtistDetail(
    val artist: ArtistInfo,
    val hotSongs: List<Track>,
    val albums: List<AlbumInfo>,
)

/**
 * 歌手浏览页的地区 tab：全部 / 内地 / 港台 / 欧美 / 日本 / 韩国。
 *
 * 实测结论（2026-11-26，M 上 python/curl 逐个接口打过）：
 * - QQ：歌手列表接口 fcg_v8_singerlist.fcg 已 404；musicu GetSingerList 返回 500003（要鉴权），都走不通。
 * - 酷狗：mobilecdn singer/list 能拉热门歌手（classid/area 参数会被忽略，只有一份热榜），
 *   每项带 singerid / singername / imgurl / songcount / albumcount。
 * - 酷我：www artistList 返回 "The request is illegal!"（要 csrf），走不通。
 * - 网易：/api/artist/list 能拉热门歌手，但 area/type 参数会被忽略（各地区返回同一份热榜）。
 * 所以“全部”用酷狗 + 网易两份热榜合并去重；五个地区没有平台接口能分，
 * 用知名歌手名当种子、走搜索接口现查真实信息（头像、歌曲数、专辑数）拼出来，名字是真实歌手、
 * 数据是接口实时返回的，不是假数据。种子查不到就跳过，不留空白卡片。
 */
enum class SingerRegion(val label: String) {
    ALL("全部"),
    MAINLAND("内地"),
    HK_TW("港台"),
    WESTERN("欧美"),
    JAPAN("日本"),
    KOREA("韩国"),
}

/**
 * 歌手 + 专辑详情，四平台直连接口（Ki.js / lx-music v4 脚本没有歌手、专辑接口）。
 *
 * 实测结论（2026-11-26，M 上逐个接口验过）：
 * - QQ：歌手搜 c.y.qq.com client_search_cp t=3 的 zhida 直达（singerMID/头像/歌数/专辑数/热门专辑/热门歌一次全有）；
 *   专辑详情走 fcg_v8_album_info_cp（song_num=100 一次拉全）。musicu.fcg 的搜索接口目前回 2001，
 *   所以 QQ 的歌仍走原有搜索，歌手/专辑走这两条。
 * - 酷狗：mobilecdn.kugou.com 必须走 http（https 证书主机名对不上）：search/singer、singer/info、
 *   singer/song、singer/album、album/song 全通。
 * - 酷我：歌手搜 search.kuwo.cn r.s ft=artist 通；www.kuwo.cn 全站 500 + csrf 校验，专辑详情拉不到，
 *   热门歌用歌搜按 ARTISTID 过滤、专辑用搜索结果聚合兜底。
 * - 网易：/api/artist/{id}、/api/artist/albums/{id} 通；歌手搜 type=100 目前回的都是无关结果（“30”），
 *   只留精确匹配；/api/album/{id} 目前回验证码挑战，专辑详情拉不到时用聚合兜底。
 */
class Artists(private val http: CatalogHttp, private val catalogs: Catalogs) {
    /** 地区种子歌手名（真实歌手，按地区分；查不到的跳过）。 */
    private val regionSeeds: Map<SingerRegion, List<String>> = mapOf(
        SingerRegion.MAINLAND to listOf("薛之谦", "许嵩", "汪苏泷", "李荣浩", "毛不易", "周深", "张杰", "华晨宇", "李健", "朴树"),
        SingerRegion.HK_TW to listOf("周杰伦", "林俊杰", "陈奕迅", "蔡依林", "五月天", "张惠妹", "王力宏", "邓紫棋", "梁静茹", "孙燕姿"),
        SingerRegion.WESTERN to listOf("Taylor Swift", "Ed Sheeran", "Adele", "Justin Bieber", "Billie Eilish", "Maroon 5", "Alan Walker", "Avicii"),
        SingerRegion.JAPAN to listOf("米津玄師", "YOASOBI", "宇多田光", "King Gnu", "Official髭男dism", "LiSA", "Ado", "RADWIMPS"),
        SingerRegion.KOREA to listOf("BTS", "BLACKPINK", "IU", "NewJeans", "SEVENTEEN", "TWICE", "BIGBANG", "G-DRAGON"),
    )
    private val hotCache = mutableMapOf<SingerRegion, List<ArtistInfo>>()

    /**
     * 热门歌手列表（歌手浏览页用）。全部：酷狗 + 网易热榜合并去重；地区：种子名走搜索接口现查。
     * 结果按地区缓存在内存里，切 tab 不用重复拉。
     */
    suspend fun hotSingers(region: SingerRegion, limit: Int = 30): List<ArtistInfo> {
        hotCache[region]?.let { return it.take(limit) }
        val list: List<ArtistInfo> = if (region == SingerRegion.ALL) {
            coroutineScope {
                val kg = async { runCatching { KugouArtist(http).hotSingers(limit) }.getOrDefault(emptyList()) }
                val wy = async { runCatching { NetEaseArtist(http).hotSingers(limit) }.getOrDefault(emptyList()) }
                val merged = (kg.await() + wy.await())
                // 按名字去重（同一歌手在两个平台都出现时留第一个，有头像的优先）。
                merged
                    .groupBy { it.name }
                    .values
                    .map { group -> group.firstOrNull { !it.picURL.isNullOrBlank() } ?: group.first() }
            }
        } else {
            val seeds = regionSeeds[region] ?: emptyList()
            coroutineScope {
                seeds.map { name ->
                    async {
                        val results = runCatching { searchArtists(name, null) }.getOrNull() ?: emptyList()
                        results.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: results.firstOrNull()
                    }
                }.awaitAll().filterNotNull().distinctBy { it.name }
            }
        }
        hotCache[region] = list
        return list.take(limit)
    }


    /** 搜歌手：各平台并行，失败的平台静默跳过。onlySource 非空时只查该平台。 */
    suspend fun searchArtists(keyword: String, onlySource: SourceID? = null): List<ArtistInfo> =
        coroutineScope {
            val jobs = listOf(
                SourceID.TX to suspend { QqArtist(http).searchArtists(keyword) },
                SourceID.KG to suspend { KugouArtist(http).searchArtists(keyword) },
                SourceID.KW to suspend { KuwoArtist(http, catalogs).searchArtists(keyword) },
                SourceID.WY to suspend { NetEaseArtist(http).searchArtists(keyword) },
            ).filter { onlySource == null || it.first == onlySource }
                .map { (_, fn) -> async { runCatching { fn() }.getOrDefault(emptyList()) } }
            jobs.awaitAll().flatten()
        }

    /** 歌手页：热门歌 + 专辑列表。 */
    suspend fun fetchArtistDetail(artist: ArtistInfo): ArtistDetail = runCatching {
        when (artist.source) {
            SourceID.TX -> QqArtist(http).fetchArtistDetail(artist)
            SourceID.KG -> KugouArtist(http).fetchArtistDetail(artist)
            SourceID.KW -> KuwoArtist(http, catalogs).fetchArtistDetail(artist)
            SourceID.WY -> NetEaseArtist(http).fetchArtistDetail(artist)
            else -> ArtistDetail(artist, emptyList(), emptyList())
        }
    }.getOrDefault(ArtistDetail(artist, emptyList(), emptyList()))

    /**
     * 专辑完整曲目单。QQ / 酷狗走详情接口；酷我 / 网易目前拉不到（www 500 / 验证码），
     * 返回空列表，调用方用 [AlbumInfo.fallbackTracks]（搜索结果聚合）兜底。
     */
    suspend fun fetchAlbumDetail(album: AlbumInfo): List<Track> = runCatching {
        when (album.source) {
            SourceID.TX -> QqArtist(http).fetchAlbumDetail(album)
            SourceID.KG -> KugouArtist(http).fetchAlbumDetail(album)
            else -> emptyList()
        }
    }.getOrDefault(emptyList())
}

// MARK: - QQ ----------------------------------------------------------------------------

private class QqArtist(private val http: CatalogHttp) {
    private val referer = mapOf("Referer" to "https://y.qq.com/")

    /** client_search_cp t=3：有直达歌手时 data.zhida.type==1，zhida_singer 里一次给全。 */
    suspend fun searchArtists(keyword: String): List<ArtistInfo> {
        val zhida = zhidaSinger(keyword) ?: return emptyList()
        return listOf(zhidaToArtist(zhida))
    }

    suspend fun fetchArtistDetail(artist: ArtistInfo): ArtistDetail {
        val zhida = zhidaSinger(artist.name) ?: return ArtistDetail(artist, emptyList(), emptyList())
        val hotSongs = zhida.optJSONArray("hotsong")?.let { parseHotSongs(it) } ?: emptyList()
        val albums = zhida.optJSONArray("hotalbum")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val d = arr.optJSONObject(i) ?: return@mapNotNull null
                val mid = d.optString("albumMID").ifEmpty { return@mapNotNull null }
                AlbumInfo(
                    source = SourceID.TX,
                    id = mid,
                    name = d.optString("albumName").ifEmpty { return@mapNotNull null },
                    singer = artist.name,
                    picURL = "https://y.gtimg.cn/music/photo_new/T002R300x300M000$mid.jpg",
                    detailKey = mid,
                )
            }
        } ?: emptyList()
        val full = zhidaToArtist(zhida)
        return ArtistDetail(full, hotSongs, albums)
    }

    /** fcg_v8_album_info_cp：song_num=100 一次拉全专辑曲目。 */
    suspend fun fetchAlbumDetail(album: AlbumInfo): List<Track> {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_album_info_cp.fcg?albummid=${album.detailKey}&format=json&song_begin=0&song_num=100"
        val list = runCatching {
            JSONObject(http.getText(url, referer)).optJSONObject("data")?.optJSONArray("list")
        }.getOrNull() ?: return emptyList()
        return (0 until list.length()).mapNotNull { buildTrack(list.optJSONObject(it)) }
    }

    private suspend fun zhidaSinger(keyword: String): JSONObject? {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?ct=24&qqmusic_ver=1298&new_json=1" +
            "&remoteplace=txt.yqq.song&searchid=0&t=3&aggr=1&cr=1&catZhida=1&lossless=0&flag_qc=0&p=1&n=1" +
            "&w=${urlEncode(keyword)}&format=json"
        val zhida = runCatching {
            JSONObject(http.getText(url, referer)).optJSONObject("data")?.optJSONObject("zhida")
        }.getOrNull() ?: return null
        if (zhida.optInt("type") != 1) return null
        return zhida.optJSONObject("zhida_singer")
    }

    private fun zhidaToArtist(zhida: JSONObject): ArtistInfo {
        val mid = zhida.optString("singerMID")
        return ArtistInfo(
            source = SourceID.TX,
            id = mid,
            name = zhida.optString("singerName").ifEmpty { "未知歌手" },
            picURL = zhida.optString("singerPic").ifEmpty { null }
                ?: "https://y.gtimg.cn/music/photo_new/T001R300x300M000$mid.jpg",
            songCount = zhida.optInt("songNum").takeIf { it > 0 },
            albumCount = zhida.optInt("albumNum").takeIf { it > 0 },
        )
    }

    /** hotsong 的 f 字段：0 songID | 1 歌名 | 3 歌手 | 4 albumID | 5 专辑名 | 7 时长秒 | 20 songMID | 21 singerMID | 22 albumMID。 */
    private fun parseHotSongs(arr: JSONArray): List<Track> {
        val out = mutableListOf<Track>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val f = d.optString("f").split("|")
            val songmid = d.optString("songMID").ifEmpty { f.getOrNull(20) } ?: continue
            if (songmid.isEmpty()) continue
            val name = d.optString("songName").ifEmpty { f.getOrNull(1) } ?: continue
            val singer = f.getOrNull(3)?.ifEmpty { null } ?: "未知"
            val albumName = f.getOrNull(5)?.ifEmpty { null }
            val albumMid = f.getOrNull(22)?.ifEmpty { null }
            val qs = mutableListOf<Quality>()
            if ((f.getOrNull(11)?.toLongOrNull() ?: 0L) > 0 || (f.getOrNull(12)?.toLongOrNull() ?: 0L) > 0) {
                qs.add(Quality.K128); qs.add(Quality.K320)
            }
            if ((f.getOrNull(16)?.toLongOrNull() ?: 0L) > 0) qs.add(Quality.FLAC)
            if (qs.isEmpty()) qs.add(Quality.K128)
            out.add(
                Track(
                    id = Track.makeID(SourceID.TX, songmid),
                    name = name,
                    singer = singer,
                    albumName = albumName,
                    albumId = f.getOrNull(4)?.ifEmpty { null },
                    source = SourceID.TX,
                    songmid = songmid,
                    duration = f.getOrNull(7)?.toIntOrNull()?.takeIf { it > 0 },
                    picURL = albumMid?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" },
                    qualities = qs,
                    extras = buildMap {
                        albumMid?.let { put("albumMid", it) }
                    },
                ),
            )
        }
        return out
    }

    private fun buildTrack(d: JSONObject?): Track? {
        d ?: return null
        val mid = d.optString("songmid").ifEmpty { return null }
        val singers = d.optJSONArray("singer")
        val singer = (0 until (singers?.length() ?: 0))
            .mapNotNull { singers?.optJSONObject(it)?.optString("name") }
            .filter { it.isNotEmpty() }.joinToString(" / ")
        val albumMid = d.optString("albummid").ifEmpty { null }
        val qs = mutableListOf<Quality>()
        if (d.optLong("size128") > 0) qs.add(Quality.K128)
        if (d.optLong("size320") > 0) qs.add(Quality.K320)
        if (d.optLong("sizeflac") > 0 || d.optLong("sizeape") > 0) qs.add(Quality.FLAC)
        if (qs.isEmpty()) qs.add(Quality.K128)
        val extras = mutableMapOf<String, String>()
        albumMid?.let { extras["albumMid"] = it }
        d.optString("strMediaMid").ifEmpty { null }?.let { extras["strMediaMid"] = it }
        d.optString("vid").ifEmpty { null }?.let { extras["mvId"] = it }
        return Track(
            id = Track.makeID(SourceID.TX, mid),
            name = d.optString("songname", "未知"),
            singer = singer.ifEmpty { "未知" },
            albumName = d.optString("albumname").ifEmpty { null },
            albumId = d.opt("albumid")?.toString(),
            source = SourceID.TX,
            songmid = mid,
            duration = d.optInt("interval").takeIf { it > 0 },
            picURL = albumMid?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" },
            qualities = qs,
            extras = extras,
        )
    }
}

// MARK: - Kugou (mobilecdn 必须走 http，https 证书主机名对不上) -----------------------------

private class KugouArtist(private val http: CatalogHttp) {
    private val base = "http://mobilecdn.kugou.com/api/v3"

    /** 热门歌手列表：mobilecdn singer/list（classid/area 参数会被忽略，只有一份热榜）。 */
    suspend fun hotSingers(limit: Int): List<ArtistInfo> {
        val root = runCatching { JSONObject(http.getText("http://mobilecdn.kugou.com/api/v3/singer/list?plat=0&page=1&pagesize=$limit")) }.getOrNull()
            ?: return emptyList()
        val arr = root.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        val out = mutableListOf<ArtistInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("singername")
            if (name.isEmpty()) continue
            val pic = o.optString("imgurl").replace("{size}", "240").ifEmpty { null }
            out += ArtistInfo(
                source = SourceID.KG,
                id = o.optString("singerid").ifEmpty { name },
                name = name,
                picURL = pic,
                songCount = o.optInt("songcount").takeIf { it > 0 },
                albumCount = o.optInt("albumcount").takeIf { it > 0 },
            )
        }
        return out
    }

    suspend fun searchArtists(keyword: String): List<ArtistInfo> {
        val url = "$base/search/singer?keyword=${urlEncode(keyword)}&page=1&pagesize=10&showtype=10"
        val info = runCatching {
            JSONObject(http.getText(url)).optJSONObject("data")?.optJSONArray("info")
        }.getOrNull() ?: return emptyList()
        return (0 until info.length()).mapNotNull { i ->
            val d = info.optJSONObject(i) ?: return@mapNotNull null
            val id = d.opt("singerid")?.toString()?.ifEmpty { null } ?: return@mapNotNull null
            ArtistInfo(
                source = SourceID.KG,
                id = id,
                name = d.optString("singername").ifEmpty { return@mapNotNull null },
                picURL = null, // 详情里再取 imgurl
            )
        }
    }

    suspend fun fetchArtistDetail(artist: ArtistInfo): ArtistDetail {
        val info = runCatching {
            JSONObject(http.getText("$base/singer/info?singerid=${artist.id}")).optJSONObject("data")
        }.getOrNull()
        val pic = info?.optString("imgurl")?.ifEmpty { null }?.replace("{size}", "240")
        val full = artist.copy(
            picURL = pic ?: artist.picURL,
            songCount = info?.optInt("songcount")?.takeIf { it > 0 } ?: artist.songCount,
            albumCount = info?.optInt("albumcount")?.takeIf { it > 0 } ?: artist.albumCount,
        )
        val songsUrl = "$base/singer/song?singerid=${artist.id}&page=1&pagesize=30"
        val hotSongs = runCatching {
            JSONObject(http.getText(songsUrl)).optJSONObject("data")?.optJSONArray("info")
        }.getOrNull()?.let { arr ->
            (0 until arr.length()).mapNotNull { buildTrack(arr.optJSONObject(it), artist.name, pic) }
        } ?: emptyList()
        val albumsUrl = "$base/singer/album?singerid=${artist.id}&page=1&pagesize=30"
        val albums = runCatching {
            JSONObject(http.getText(albumsUrl)).optJSONObject("data")?.optJSONArray("info")
        }.getOrNull()?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val d = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = d.opt("albumid")?.toString()?.ifEmpty { null } ?: return@mapNotNull null
                AlbumInfo(
                    source = SourceID.KG,
                    id = id,
                    name = d.optString("albumname").ifEmpty { return@mapNotNull null },
                    singer = artist.name,
                    picURL = d.optString("imgurl").ifEmpty { null }?.replace("{size}", "240"),
                    songCount = d.optInt("songcount").takeIf { it > 0 },
                    detailKey = id,
                )
            }
        } ?: emptyList()
        return ArtistDetail(full, hotSongs, albums)
    }

    suspend fun fetchAlbumDetail(album: AlbumInfo): List<Track> {
        val url = "$base/album/song?albumid=${album.detailKey}&page=1&pagesize=100"
        val info = runCatching {
            JSONObject(http.getText(url)).optJSONObject("data")?.optJSONArray("info")
        }.getOrNull() ?: return emptyList()
        return (0 until info.length()).mapNotNull { buildTrack(info.optJSONObject(it), album.singer, album.picURL) }
    }

    /** singer/song、album/song 的条目都没有 songname 字段，歌名在 filename“歌手 - 歌名”里。 */
    private fun buildTrack(d: JSONObject?, singerFallback: String, picURL: String?): Track? {
        d ?: return null
        val hash = d.optString("hash").ifEmpty { return null }
        val audioId = d.opt("audio_id")?.toString()?.ifEmpty { hash } ?: hash
        val filename = d.optString("filename")
        val (singer, name) = if (" - " in filename) {
            val parts = filename.split(" - ", limit = 2)
            parts[0].ifEmpty { singerFallback } to parts[1].ifEmpty { filename }
        } else {
            singerFallback to filename.ifEmpty { "未知" }
        }
        val qs = mutableListOf<Quality>()
        if (d.optLong("filesize") > 0) qs.add(Quality.K128)
        if (d.optLong("320filesize") > 0) qs.add(Quality.K320)
        if (d.optLong("sqfilesize") > 0) qs.add(Quality.FLAC)
        if (d.optLong("filesize_high") > 0) qs.add(Quality.FLAC24)
        if (qs.isEmpty()) qs.add(Quality.K128)
        val extras = mutableMapOf("hash" to hash)
        d.optString("album_id").ifEmpty { null }?.let { extras["albumId"] = it }
        d.optString("mvhash").ifEmpty { null }?.let { extras["mvId"] = it }
        return Track(
            id = Track.makeID(SourceID.KG, audioId),
            name = name,
            singer = singer.ifEmpty { singerFallback },
            albumName = d.optString("album_name").ifEmpty { null },
            albumId = d.optString("album_id").ifEmpty { null },
            source = SourceID.KG,
            songmid = audioId,
            duration = d.optInt("duration").takeIf { it > 0 },
            picURL = picURL,
            qualities = qs,
            extras = extras,
        )
    }
}

// MARK: - Kuwo ----------------------------------------------------------------------------

private class KuwoArtist(private val http: CatalogHttp, private val catalogs: Catalogs) {
    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15",
    )

    suspend fun searchArtists(keyword: String): List<ArtistInfo> {
        val url = "http://search.kuwo.cn/r.s?client=kt&all=${urlEncode(keyword)}&pn=0&rn=10&uid=794762570" +
            "&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=artist&cluster=0" +
            "&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"
        val json = runCatching { JSONObject(http.getText(url, ua)) }.getOrNull() ?: return emptyList()
        val base = json.optString("BASEPICPATH")
        val abslist = json.optJSONArray("abslist") ?: return emptyList()
        return (0 until abslist.length()).mapNotNull { i ->
            val d = abslist.optJSONObject(i) ?: return@mapNotNull null
            val id = d.optString("ARTISTID").ifEmpty { return@mapNotNull null }
            ArtistInfo(
                source = SourceID.KW,
                id = id,
                name = d.optString("ARTIST").ifEmpty { return@mapNotNull null },
                picURL = d.optString("PICPATH").ifEmpty { null }?.let { base + it },
                songCount = d.optString("SONGNUM").toIntOrNull(),
                albumCount = d.optString("ALBUMNUM").toIntOrNull(),
            )
        }
    }

    suspend fun fetchArtistDetail(artist: ArtistInfo): ArtistDetail {
        // 酷我没有歌手详情接口：热门歌 = 按歌手名搜歌再按 ARTISTID 过滤（SearchCatalog 里已把
        // ARTISTID 写进 Track.extras["artistId"]），专辑 = 这些歌按专辑聚合。
        val hotSongs = runCatching { catalogs.search(SourceID.KW, artist.name, 1) }.getOrDefault(emptyList())
            .filter { it.extras["artistId"] == artist.id }
        val albums = groupAlbums(hotSongs).map { g ->
            AlbumInfo(
                source = SourceID.KW,
                id = g.tracks.firstOrNull()?.albumId ?: g.name,
                name = g.name,
                singer = artist.name,
                picURL = g.picURL,
                songCount = g.tracks.size,
                detailKey = g.tracks.firstOrNull()?.albumId ?: g.name,
                fallbackTracks = g.tracks,
            )
        }
        return ArtistDetail(artist, hotSongs, albums)
    }
}

// MARK: - NetEase -------------------------------------------------------------------------

private class NetEaseArtist(private val http: CatalogHttp) {
    private val headers = mapOf("Referer" to "https://music.163.com/")

    /** type=100 目前回的都是无关结果，只留名字精确匹配的（接口恢复后自然生效）。 */
    /** 热门歌手列表：/api/artist/list（area/type 参数会被忽略，只有一份热榜）。 */
    suspend fun hotSingers(limit: Int): List<ArtistInfo> {
        val root = runCatching { JSONObject(http.getText("https://music.163.com/api/artist/list?type=1&area=7&initial=-1&offset=0&limit=$limit")) }.getOrNull()
            ?: return emptyList()
        val arr = root.optJSONArray("artists") ?: return emptyList()
        val out = mutableListOf<ArtistInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isEmpty()) continue
            out += ArtistInfo(
                source = SourceID.WY,
                id = o.optString("id").ifEmpty { name },
                name = name,
                picURL = o.optString("picUrl").ifEmpty { null },
                songCount = o.optInt("musicSize").takeIf { it > 0 },
                albumCount = o.optInt("albumSize").takeIf { it > 0 },
            )
        }
        return out
    }

    suspend fun searchArtists(keyword: String): List<ArtistInfo> {
        val body = "s=${urlEncode(keyword)}&type=100&limit=10&offset=0"
        val arr = runCatching {
            JSONObject(http.postForm("https://music.163.com/api/search/get", body, headers))
                .optJSONObject("result")?.optJSONArray("artists")
        }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val d = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = d.optString("name").ifEmpty { return@mapNotNull null }
            if (!name.equals(keyword, ignoreCase = true)) return@mapNotNull null
            ArtistInfo(
                source = SourceID.WY,
                id = d.opt("id")?.toString() ?: return@mapNotNull null,
                name = name,
                picURL = d.optString("picUrl").ifEmpty { null },
                songCount = d.optInt("musicSize").takeIf { it > 0 },
                albumCount = d.optInt("albumSize").takeIf { it > 0 },
            )
        }
    }

    suspend fun fetchArtistDetail(artist: ArtistInfo): ArtistDetail {
        val root = runCatching {
            JSONObject(http.getText("https://music.163.com/api/artist/${artist.id}", headers))
        }.getOrNull()
        val a = root?.optJSONObject("artist")
        val full = artist.copy(
            picURL = a?.optString("picUrl")?.ifEmpty { null } ?: artist.picURL,
            songCount = a?.optInt("musicSize")?.takeIf { it > 0 } ?: artist.songCount,
            albumCount = a?.optInt("albumSize")?.takeIf { it > 0 } ?: artist.albumCount,
        )
        val hotSongs = root?.optJSONArray("hotSongs")?.let { arr ->
            (0 until arr.length()).mapNotNull { buildTrack(arr.optJSONObject(it)) }
        } ?: emptyList()
        val albums = runCatching {
            JSONObject(http.getText("https://music.163.com/api/artist/albums/${artist.id}?limit=30&offset=0", headers))
                .optJSONArray("hotAlbums")
        }.getOrNull()?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val d = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = d.opt("id")?.toString()?.ifEmpty { null } ?: return@mapNotNull null
                AlbumInfo(
                    source = SourceID.WY,
                    id = id,
                    name = d.optString("name").ifEmpty { return@mapNotNull null },
                    singer = artist.name,
                    picURL = d.optString("picUrl").ifEmpty { null },
                    songCount = d.optInt("size").takeIf { it > 0 },
                    detailKey = id,
                )
            }
        } ?: emptyList()
        return ArtistDetail(full, hotSongs, albums)
    }

    /** /api/album/{id} 目前回验证码挑战（data 里带 verifyType），拉不到就返回空，调用方用聚合兜底。 */
    suspend fun fetchAlbumDetail(album: AlbumInfo): List<Track> {
        val root = runCatching {
            JSONObject(http.getText("https://music.163.com/api/album/${album.detailKey}", headers))
        }.getOrNull() ?: return emptyList()
        val data = root.optJSONObject("data")
        if (data != null && data.has("verifyType")) return emptyList()
        val songs = data?.optJSONArray("songs") ?: root.optJSONArray("songs") ?: return emptyList()
        return (0 until songs.length()).mapNotNull { buildTrack(songs.optJSONObject(it)) }
    }

    private fun buildTrack(d: JSONObject?): Track? {
        d ?: return null
        val id = d.opt("id")?.toString() ?: return null
        val artists = d.optJSONArray("artists")
        val singer = (0 until (artists?.length() ?: 0))
            .mapNotNull { artists?.optJSONObject(it)?.optString("name") }
            .filter { it.isNotEmpty() }.joinToString(" / ")
        val album = d.optJSONObject("album")
        val qs = mutableListOf(Quality.K128)
        if (d.optJSONObject("hMusic") != null) qs.add(Quality.K320)
        if (d.optJSONObject("sqMusic") != null) qs.add(Quality.FLAC)
        if (d.optJSONObject("hrMusic") != null) qs.add(Quality.HIRES)
        val extras = mutableMapOf<String, String>()
        d.optLong("mvid").takeIf { it > 0 }?.toString()?.let { extras["mvId"] = it }
        return Track(
            id = Track.makeID(SourceID.WY, id),
            name = d.optString("name", "未知"),
            singer = singer.ifEmpty { "未知歌手" },
            albumName = album?.optString("name")?.ifEmpty { null },
            albumId = album?.opt("id")?.toString(),
            source = SourceID.WY,
            songmid = id,
            duration = d.optInt("duration").takeIf { it > 0 }?.div(1000),
            picURL = album?.optString("picUrl")?.ifEmpty { null },
            qualities = qs,
            extras = extras,
        )
    }
}
