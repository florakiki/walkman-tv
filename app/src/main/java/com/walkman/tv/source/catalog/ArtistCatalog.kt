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
    /**
     * 酷狗歌手页的单曲条目（singer/album 里 songcount==1）：酷狗常把单曲和母专辑挂在
     * 同一个 albumid 下，详情接口回的是母专辑整张或无关现场版；点进去时只保留与专辑
     * 同名的那一首，歌数才和卡片上的 1 首对得上。搜索页聚合出来的 AlbumInfo 不置这
     * 个位（聚合的歌数不是“单曲”的证据，不能拿来过滤）。
     */
    val isSingle: Boolean = false,
)

/** 歌手页：热门歌曲 + 专辑列表。Yang 定：热门歌曲那一栏是这个歌手的所有歌曲
 *  （全部显示、能翻到底），专辑也一样要他全部专辑——所以首屏之外还有翻页：
 *  songsNextPage / albumsNextPage 是下一页的页码（第 0 页起），
 *  songsHasMore / albumsHasMore 是后面还有没有。 */
data class ArtistDetail(
    val artist: ArtistInfo,
    val hotSongs: List<Track>,
    val albums: List<AlbumInfo>,
    val songsNextPage: Int = 1,
    val albumsNextPage: Int = 1,
    val songsHasMore: Boolean = true,
    val albumsHasMore: Boolean = true,
)

/** 翻页返回：一页条目 + 后面还有没有。 */
data class CatalogPage<T>(val items: List<T>, val hasMore: Boolean)

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
    /** 地区种子歌手名（真实歌手，按地区分；查不到的跳过）。每地区 30 个，浏览页每页查 10 个。 */
    private val regionSeeds: Map<SingerRegion, List<String>> = mapOf(
        SingerRegion.MAINLAND to listOf("薛之谦", "许嵩", "汪苏泷", "李荣浩", "毛不易", "周深", "张杰", "华晨宇", "李健", "朴树", "汪峰", "许巍", "郑钧", "老狼", "羽泉", "凤凰传奇", "筷子兄弟", "韩红", "那英", "孙楠", "韩磊", "沙宝亮", "杨坤", "刀郎", "海来阿木", "王靖雯", "单依纯", "希林娜依高", "黄霄雲", "刘宇宁"),
        SingerRegion.HK_TW to listOf("周杰伦", "林俊杰", "陈奕迅", "蔡依林", "五月天", "张惠妹", "王力宏", "邓紫棋", "梁静茹", "孙燕姿", "刘德华", "张学友", "郭富城", "王菲", "莫文蔚", "杨千嬅", "容祖儿", "谢霆锋", "罗志祥", "潘玮柏", "林宥嘉", "萧敬腾", "田馥甄", "徐佳莹", "蔡健雅", "张国荣", "梅艳芳", "谭咏麟", "黎明", "吴克群"),
        SingerRegion.WESTERN to listOf("Taylor Swift", "Ed Sheeran", "Adele", "Justin Bieber", "Billie Eilish", "Maroon 5", "Alan Walker", "Avicii", "Bruno Mars", "Lady Gaga", "Rihanna", "Beyoncé", "Katy Perry", "Ariana Grande", "Dua Lipa", "The Weeknd", "Drake", "Eminem", "Coldplay", "Imagine Dragons", "OneRepublic", "Shawn Mendes", "Selena Gomez", "Sia", "Sam Smith", "Charlie Puth", "Post Malone", "Camila Cabello", "Miley Cyrus", "Halsey"),
        SingerRegion.JAPAN to listOf("米津玄師", "YOASOBI", "宇多田光", "King Gnu", "Official髭男dism", "LiSA", "Ado", "RADWIMPS", "星野源", "菅田将暉", "あいみょん", "優里", "back number", "SEKAI NO OWARI", "ONE OK ROCK", "BUMP OF CHICKEN", "Mr.Children", "嵐", "乃木坂46", "滨崎步", "安室奈美惠", "中岛美嘉", "平井坚", "福山雅治", "椎名林檎", "久石譲", "藤井風", "Vaundy", "緑黄色社会", "高橋優"),
        SingerRegion.KOREA to listOf("BTS", "BLACKPINK", "IU", "NewJeans", "SEVENTEEN", "TWICE", "BIGBANG", "G-DRAGON", "EXO", "Red Velvet", "aespa", "ITZY", "LE SSERAFIM", "IVE", "(G)I-DLE", "NCT DREAM", "Stray Kids", "TXT", "ENHYPEN", "SHINee", "Super Junior", "Girls' Generation", "PSY", "ZICO", "Crush", "Heize", "AKMU", "DAY6", "WINNER", "iKON"),
    )
    private val hotCache = mutableMapOf<SingerRegion, List<ArtistInfo>>()
    private val hotPageCache = mutableMapOf<Pair<SingerRegion, Int>, HotSingersPage>()

    /** 翻页返回：一页歌手 + 后面还有没有。 */
    data class HotSingersPage(val singers: List<ArtistInfo>, val hasMore: Boolean)

    /**
     * 歌手浏览页翻页拉取（全部 tab 每页 24，地区 tab 每页 10 个种子）。
     * 注意不要复用 [hotSingers] 的缓存：推荐页先调 hotSingers(ALL, 12) 只存
     * 15–20 个就进缓存，浏览页再调 take(48) 也只能拿到那几个——这就是之前
     * “全部 tab 也只显示几个”的原因。翻页用自己按 (地区, 页) 分开的缓存。
     */
    suspend fun hotSingersPage(region: SingerRegion, page: Int): HotSingersPage {
        hotPageCache[region to page]?.let { return it }
        val result: HotSingersPage = if (region == SingerRegion.ALL) {
            val pageSize = 24
            val (kg, wy) = coroutineScope {
                val kgJob = async { runCatching { KugouArtist(http).hotSingersPaged(page, pageSize) }.getOrDefault(emptyList()) }
                val wyJob = async { runCatching { NetEaseArtist(http).hotSingersPaged(page, pageSize) }.getOrDefault(emptyList()) }
                kgJob.await() to wyJob.await()
            }
            val merged = (kg + wy)
                .groupBy { it.name }
                .values
                .map { group -> group.firstOrNull { !it.picURL.isNullOrBlank() } ?: group.first() }
            HotSingersPage(merged, hasMore = (kg.size == pageSize || wy.size == pageSize) && page < 7)
        } else {
            val seeds = regionSeeds[region] ?: emptyList()
            val chunks = seeds.chunked(10)
            val names = chunks.getOrNull(page) ?: emptyList()
            val found = coroutineScope {
                names.map { name ->
                    async {
                        val results = runCatching { searchArtists(name, null) }.getOrNull() ?: emptyList()
                        results.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: results.firstOrNull()
                    }
                }.awaitAll().filterNotNull().distinctBy { it.name }
            }
            HotSingersPage(found, hasMore = page < chunks.size - 1)
        }
        hotPageCache[region to page] = result
        return result
    }

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

    /**
     * 歌手页歌曲翻页（page 从 0 起，每页 30 首）。首屏之外的页，翻到底自动拉下一页。
     * 各平台取法（2026-11-26，M 上逐个接口验过）：
     * - 酷狗 singer/song 按 page 翻页；网易 search type=1 按 offset 翻页、按歌手 id 过滤；
     * - 酷我歌搜 ft=music 按 pn 翻页、按 ARTISTID 过滤；
     * - QQ musicu 搜歌现在回 0 首，改走 search_for_qq_cp（t=0），按 singerMID 过滤。
     * picURL 只给酷狗用（歌的封面就是歌手头像；其它平台歌自带专辑封面或没有）。
     */
    suspend fun fetchArtistSongsPage(artist: ArtistInfo, page: Int, picURL: String? = null): CatalogPage<Track> = runCatching {
        when (artist.source) {
            SourceID.TX -> QqArtist(http).fetchSongsPage(artist, page)
            SourceID.KG -> KugouArtist(http).fetchSongsPage(artist, page, picURL)
            SourceID.KW -> KuwoArtist(http, catalogs).fetchSongsPage(artist, page)
            SourceID.WY -> NetEaseArtist(http).fetchSongsPage(artist, page)
            else -> CatalogPage(emptyList(), false)
        }
    }.getOrDefault(CatalogPage(emptyList(), false))

    /**
     * 歌手页专辑翻页（page 从 0 起，每页 30 张）。allSongs 是已加载的全部歌曲：
     * 酷我没有专辑列表接口，专辑从这些歌里聚合（按首次出现顺序，翻页追加不錯位）；
     * 网易靠它给专辑做兜底曲目。酷狗 / QQ 走专辑列表接口。
     */
    suspend fun fetchArtistAlbumsPage(artist: ArtistInfo, page: Int, allSongs: List<Track> = emptyList()): CatalogPage<AlbumInfo> = runCatching {
        when (artist.source) {
            SourceID.TX -> QqArtist(http).fetchAlbumsPage(artist, page)
            SourceID.KG -> KugouArtist(http).fetchAlbumsPage(artist, page)
            SourceID.KW -> KuwoArtist(http, catalogs).fetchAlbumsPage(artist, page, allSongs)
            SourceID.WY -> NetEaseArtist(http).fetchAlbumsPage(artist, page, allSongs)
            else -> CatalogPage(emptyList(), false)
        }
    }.getOrDefault(CatalogPage(emptyList(), false))

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
     * 专辑完整曲目单。QQ / 酷狗走详情接口；酷我走专辑名搜歌（www 500 / csrf，详情接口
     * 拉不到）；网易走 /api/album（常回验证码挑战，拉不到时调用方用 fallbackTracks 兜底）。
     */
    suspend fun fetchAlbumDetail(album: AlbumInfo): List<Track> = runCatching {
        when (album.source) {
            SourceID.TX -> QqArtist(http).fetchAlbumDetail(album)
            SourceID.KG -> KugouArtist(http).fetchAlbumDetail(album)
            SourceID.KW -> KuwoArtist(http, catalogs).fetchAlbumDetail(album)
            SourceID.WY -> NetEaseArtist(http).fetchAlbumDetail(album)
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
        val hotAlbums = zhida.optJSONArray("hotalbum")?.let { arr ->
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
        // 首屏：zhida 的 10 首热门歌 + 搜歌第 0 页（30 首）去重；专辑：zhida 的 10 张热门
        // 专辑 + 专辑列表第 0 页（30 张）去重。往下翻到底再拉下一页。
        val songsPage = fetchSongsPage(artist, 0)
        val songs = (hotSongs + songsPage.items).distinctBy { it.id }
        val albumsPage = fetchAlbumsPage(artist, 0)
        val albums = (hotAlbums + albumsPage.items).distinctBy { it.id }
        return ArtistDetail(full, songs, albums, 1, 1, songsPage.hasMore, albumsPage.hasMore)
    }

    /**
     * QQ 全部歌曲：search_for_qq_cp（t=0）按页翻，每页 30 首，按 singerMID 过滤
     * （搜歌手名会混进同名歌手的歌）。注意别用 client_search_cp 的 t=0——它现在
     * totalnum 回 0；musicu 搜歌也回 0 首。实测：陈奕迅 totalnum=600。
     */
    suspend fun fetchSongsPage(artist: ArtistInfo, page: Int): CatalogPage<Track> {
        val url = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?ct=24&qqmusic_ver=1298&new_json=1" +
            "&remoteplace=txt.yqq.song&searchid=0&t=0&aggr=1&cr=1&catZhida=1&lossless=0&flag_qc=0" +
            "&p=${page + 1}&n=30&w=${urlEncode(artist.name)}&format=json"
        val list = runCatching {
            JSONObject(http.getText(url, referer)).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
        }.getOrNull() ?: return CatalogPage(emptyList(), false)
        val out = mutableListOf<Track>()
        for (i in 0 until list.length()) {
            val d = list.optJSONObject(i) ?: continue
            val singers = d.optJSONArray("singer")
            val mine = (0 until (singers?.length() ?: 0)).any { singers?.optJSONObject(it)?.optString("mid") == artist.id }
            if (!mine) continue
            buildSearchTrack(d)?.let { out += it }
        }
        return CatalogPage(out, list.length() >= 30)
    }

    /**
     * QQ 全部专辑：fcg_v8_singer_album 按 begin 翻页，每页 30 张，total 是专辑总数
     * （陈奕迅 141 张）。列表不给专辑歌数（QQ 专辑卡片本来就不标歌数），点进去详情接口拉全。
     */
    suspend fun fetchAlbumsPage(artist: ArtistInfo, page: Int): CatalogPage<AlbumInfo> {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_singer_album.fcg?singermid=${artist.id}" +
            "&begin=${page * 30}&num=30&order=time&format=json"
        val list = runCatching {
            JSONObject(http.getText(url, referer)).optJSONObject("data")?.optJSONArray("list")
        }.getOrNull() ?: return CatalogPage(emptyList(), false)
        val albums = (0 until list.length()).mapNotNull { i ->
            val d = list.optJSONObject(i) ?: return@mapNotNull null
            val mid = d.optString("albumMID").ifEmpty { return@mapNotNull null }
            val name = d.optString("albumName").ifEmpty { return@mapNotNull null }
            AlbumInfo(
                source = SourceID.TX,
                id = mid,
                name = name,
                singer = artist.name,
                picURL = "https://y.gtimg.cn/music/photo_new/T002R300x300M000$mid.jpg",
                detailKey = mid,
            )
        }
        return CatalogPage(albums, albums.size >= 30)
    }

    /**
     * search_for_qq_cp 的条目是老格式（平铺字段，没有 album/file 对象）：
     * songmid / songname / singer[{mid,name}] / albummid / albumname / interval /
     * size128 / size320 / sizeflac / sizeape / vid。别复用 musicu 那套 buildTrack。
     */
    private fun buildSearchTrack(d: JSONObject?): Track? {
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
    suspend fun hotSingers(limit: Int): List<ArtistInfo> = hotSingersPaged(0, limit)

    suspend fun hotSingersPaged(page: Int, pageSize: Int): List<ArtistInfo> {
        val root = runCatching { JSONObject(http.getText("http://mobilecdn.kugou.com/api/v3/singer/list?plat=0&page=${page + 1}&pagesize=$pageSize")) }.getOrNull()
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
        val songsPage = fetchSongsPage(artist, 0, pic)
        val albumsPage = fetchAlbumsPage(artist, 0)
        return ArtistDetail(full, songsPage.items, albumsPage.items, 1, 1, songsPage.hasMore, albumsPage.hasMore)
    }

    /** 酷狗全部歌曲：singer/song 按 page 翻页，每页 30 首（陈奕迅 2431 首）。 */
    suspend fun fetchSongsPage(artist: ArtistInfo, page: Int, picURL: String? = null): CatalogPage<Track> {
        val arr = runCatching {
            JSONObject(http.getText("$base/singer/song?singerid=${artist.id}&page=${page + 1}&pagesize=30"))
                .optJSONObject("data")?.optJSONArray("info")
        }.getOrNull() ?: return CatalogPage(emptyList(), false)
        val tracks = (0 until arr.length()).mapNotNull { buildTrack(arr.optJSONObject(it), artist.name, picURL) }
        return CatalogPage(tracks, tracks.size >= 30)
    }

    /** 酷狗全部专辑：singer/album 按 page 翻页，每页 30 张（陈奕迅 162 张）。 */
    suspend fun fetchAlbumsPage(artist: ArtistInfo, page: Int): CatalogPage<AlbumInfo> {
        val arr = runCatching {
            JSONObject(http.getText("$base/singer/album?singerid=${artist.id}&page=${page + 1}&pagesize=30"))
                .optJSONObject("data")?.optJSONArray("info")
        }.getOrNull() ?: return CatalogPage(emptyList(), false)
        val albums = (0 until arr.length()).mapNotNull { i ->
            val d = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = d.opt("albumid")?.toString()?.ifEmpty { null } ?: return@mapNotNull null
            val count = d.optInt("songcount").takeIf { it > 0 }
            AlbumInfo(
                source = SourceID.KG,
                id = id,
                name = d.optString("albumname").ifEmpty { return@mapNotNull null },
                singer = artist.name,
                picURL = d.optString("imgurl").ifEmpty { null }?.replace("{size}", "240"),
                songCount = count,
                detailKey = id,
                // songcount==1 的是单曲条目：酷狗常把单曲和母专辑挂在同一个 albumid 下，
                // 点进去时详情接口回的是母专辑整张，fetchAlbumDetail 里只保留同名那一首。
                isSingle = count == 1,
            )
        }
        return CatalogPage(albums, albums.size >= 30)
    }

    suspend fun fetchAlbumDetail(album: AlbumInfo): List<Track> {
        val url = "$base/album/song?albumid=${album.detailKey}&page=1&pagesize=100"
        val info = runCatching {
            JSONObject(http.getText(url)).optJSONObject("data")?.optJSONArray("info")
        }.getOrNull() ?: return emptyList()
        val tracks = (0 until info.length()).mapNotNull { buildTrack(info.optJSONObject(it), album.singer, album.picURL) }
        if (tracks.isEmpty()) return emptyList()
        // 单曲条目（卡片上标 1 首）：详情接口常回母专辑整张（英雄 → 周杰伦的床边故事
        // 10 首）或无关现场版（说好不哭 → 25 首 Live），和卡片上的 1 首对不上。
        // 只保留与专辑同名的那一首；一首都对不上时再按单曲名搜一次，搜不到就返回空
        // （调用方显示“暂时拉不到曲目单”，不拿错的歌充数）。
        if (album.isSingle) {
            val title = tracks.filter { sameTitle(it.name, album.name) }
            if (title.isNotEmpty()) return title
            return searchSingle(album)
        }
        return tracks
    }

    /** 歌名和专辑名是否同一首：去空格、不分大小写，括号里的 feat/with 后缀不计较。 */
    private fun sameTitle(trackName: String, albumName: String): Boolean {
        fun norm(s: String) = s.lowercase().filterNot { it.isWhitespace() }
        fun base(s: String) = norm(s).replace(Regex("\\(.*\\)$"), "")
        val t = norm(trackName)
        val a = norm(albumName)
        return t == a || base(t) == base(a) || t == base(a) || base(t) == a
    }

    /** 按单曲名搜酷狗，取歌名完全对得上的那一首（说好不哭这种详情接口给错的情况）。 */
    private suspend fun searchSingle(album: AlbumInfo): List<Track> {
        val url = "$base/search/song?keyword=${urlEncode(album.name)}&page=1&pagesize=10"
        val info = runCatching {
            JSONObject(http.getText(url)).optJSONObject("data")?.optJSONArray("info")
        }.getOrNull() ?: return emptyList()
        val tracks = (0 until info.length()).mapNotNull { buildTrack(info.optJSONObject(it), album.singer, album.picURL) }
        return tracks.filter { sameTitle(it.name, album.name) }.take(1)
    }

    /** singer/song、album/song 的条目都没有 songname 字段，歌名在 filename“歌手 - 歌名”里。
     * search/song 的条目两者都有，filename 为空时退到 songname / singername。 */
    private fun buildTrack(d: JSONObject?, singerFallback: String, picURL: String?): Track? {
        d ?: return null
        val hash = d.optString("hash").ifEmpty { return null }
        val audioId = d.opt("audio_id")?.toString()?.ifEmpty { hash } ?: hash
        val filename = d.optString("filename")
        val (singer, name) = if (" - " in filename) {
            val parts = filename.split(" - ", limit = 2)
            parts[0].ifEmpty { singerFallback } to parts[1].ifEmpty { d.optString("songname").ifEmpty { filename } }
        } else {
            d.optString("singername").ifEmpty { singerFallback } to
                d.optString("songname").ifEmpty { filename.ifEmpty { "未知" } }
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
        // 酷我没有歌手详情接口：热门歌 = 按歌手名搜歌再按 ARTISTID 过滤，专辑 = 这些歌按专辑聚合。
        // 一页 30 首里每张专辑往往只出现一次，聚合出来的歌数全是 1 首——首屏多拉 3 页（90 首）
        // 再聚合；往下翻到底继续拉歌，专辑随歌一起长出来。
        val raws = (0..2).map { page ->
            runCatching { searchKwSongs(artist.name, page + 1) }.getOrDefault(emptyList())
        }
        val songs = raws.flatten().filter { it.extras["artistId"] == artist.id }.distinctBy { it.id }
        val albumsPage = fetchAlbumsPage(artist, 0, songs)
        val songsHasMore = (raws.getOrNull(2)?.size ?: 0) >= 30
        return ArtistDetail(artist, songs, albumsPage.items, 3, 1, songsHasMore, albumsPage.hasMore)
    }

    /** 酷我全部歌曲：歌搜 ft=music 按 pn 翻页，每页 30 首，按 ARTISTID 过滤
     *  （搜歌手名会混进同名歌手的歌）。 */
    suspend fun fetchSongsPage(artist: ArtistInfo, page: Int): CatalogPage<Track> {
        val raw = runCatching { searchKwSongs(artist.name, page + 1) }.getOrDefault(emptyList())
        val mine = raw.filter { it.extras["artistId"] == artist.id }
        return CatalogPage(mine, raw.size >= 30)
    }

    /**
     * 酷我全部专辑：没有专辑列表接口，从已加载的全部歌曲里按专辑聚合，按首次出现
     * 顺序分页（新歌只会把新专辑追加在后面，翻页追加不会错位）。每张专辑再按专辑名
     * 搜一次拿完整曲目单：卡片上的歌数和点进去的曲目单都以搜到的为准，搜不到就保留
     * 聚合的结果，不做假数据。搜歌不走 catalogs.search（那会给每首歌补封面，
     * 每专辑一次就要多 30 次封面请求），直接调 search.kuwo.cn，封面用专辑卡片现成的。
     */
    suspend fun fetchAlbumsPage(artist: ArtistInfo, page: Int, allSongs: List<Track>): CatalogPage<AlbumInfo> {
        val groups = groupAlbums(allSongs.distinctBy { it.id })
        val slice = groups.drop(page * 30).take(30)
        val albums = coroutineScope {
            slice.map { g ->
                async {
                    val full = searchAlbumTracks(g.name, artist)
                    val tracks = full.ifEmpty { g.tracks }
                    AlbumInfo(
                        source = SourceID.KW,
                        id = g.tracks.firstOrNull()?.albumId ?: g.name,
                        name = g.name,
                        singer = artist.name,
                        picURL = g.picURL,
                        songCount = tracks.size,
                        detailKey = g.tracks.firstOrNull()?.albumId ?: g.name,
                        fallbackTracks = tracks,
                    )
                }
            }.awaitAll()
        }
        return CatalogPage(albums, (page + 1) * 30 < groups.size)
    }

    /**
     * 酷我专辑详情：酷我没有专辑详情接口（www 500 / csrf），按专辑名搜歌，
     * 只留专辑名和歌手都对得上的；一页 30 首回满了再翻一页。
     */
    suspend fun fetchAlbumDetail(album: AlbumInfo): List<Track> {
        val p1 = runCatching { searchKwSongs(album.name, 1) }.getOrDefault(emptyList())
        val all = if (p1.size >= 30) {
            p1 + runCatching { searchKwSongs(album.name, 2) }.getOrDefault(emptyList())
        } else p1
        return all.filter {
            it.albumName?.trim().equals(album.name, ignoreCase = true) &&
                (it.singer.equals(album.singer, ignoreCase = true) ||
                    it.singer.contains(album.singer, ignoreCase = true) ||
                    album.singer.contains(it.singer, ignoreCase = true))
        }.distinctBy { it.id }
    }

    /** 按专辑名搜歌拿完整曲目单（给歌手页专辑卡片的歌数和兜底曲目用）。 */
    private suspend fun searchAlbumTracks(albumName: String, artist: ArtistInfo): List<Track> {
        val p1 = runCatching { searchKwSongs(albumName, 1) }.getOrDefault(emptyList())
        val all = if (p1.size >= 30) {
            p1 + runCatching { searchKwSongs(albumName, 2) }.getOrDefault(emptyList())
        } else p1
        return all.filter {
            it.albumName?.trim().equals(albumName, ignoreCase = true) &&
                (it.extras["artistId"] == artist.id || it.singer.equals(artist.name, ignoreCase = true))
        }.distinctBy { it.id }
    }

    /**
     * 不带封面补全的酷我歌搜（专辑聚合/专辑详情内部用）。catalogs.search 会给每首歌补
     * 封面（每首一次请求），聚合和补全时每专辑调一次就够了，不走那条路。
     * 解析和 SearchCatalog.KuwoSearch.build 同字段（MUSICRID / SONGNAME / ARTIST /
     * ALBUM / ALBUMID / DURATION / N_MINFO / ARTISTID / MVFLAG）。
     */
    private val minfoRegex = Regex("""level:(\w+),bitrate:(\d+),format:(\w+),size:([\w.]+)""")

    private suspend fun searchKwSongs(keyword: String, page: Int): List<Track> {
        val enc = urlEncode(keyword)
        val url = "http://search.kuwo.cn/r.s?client=kt&all=$enc&pn=${page - 1}&rn=30&uid=794762570" +
            "&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0" +
            "&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"
        val text = runCatching {
            http.getText(url, mapOf("User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15"))
        }.getOrNull() ?: return emptyList()
        val abslist = runCatching { JSONObject(text).optJSONArray("abslist") }.getOrNull() ?: return emptyList()
        return (0 until abslist.length()).mapNotNull { buildKwTrack(abslist.optJSONObject(it)) }
    }

    private fun buildKwTrack(d: JSONObject?): Track? {
        d ?: return null
        val songmid = d.optString("MUSICRID").replace("MUSIC_", "")
        if (songmid.isEmpty()) return null
        val qs = mutableListOf<Quality>()
        d.optString("N_MINFO").split(";").forEach { chunk ->
            minfoRegex.find(chunk)?.groupValues?.getOrNull(2)?.let { br ->
                when (br) {
                    "4000" -> qs.add(Quality.FLAC24)
                    "2000" -> qs.add(Quality.FLAC)
                    "320" -> qs.add(Quality.K320)
                    "128" -> qs.add(Quality.K128)
                }
            }
        }
        if (qs.isEmpty()) qs.add(Quality.K128)
        val albumId = decodeKw(d.optString("ALBUMID")).ifEmpty { null }
        val extras = mutableMapOf<String, String>()
        // 歌手页热门歌按 ARTISTID 过滤用。
        d.optString("ARTISTID").ifEmpty { null }?.let { extras["artistId"] = it }
        if (d.optString("MVFLAG") == "1") extras["mvId"] = songmid
        return Track(
            id = Track.makeID(SourceID.KW, songmid),
            name = decodeKw(d.optString("SONGNAME", "未知")),
            singer = decodeKw(d.optString("ARTIST")).replace("&", "、").ifEmpty { "未知歌手" },
            albumName = decodeKw(d.optString("ALBUM")).ifEmpty { null },
            albumId = albumId,
            source = SourceID.KW,
            songmid = songmid,
            duration = d.optString("DURATION").toIntOrNull(),
            picURL = null,
            qualities = qs,
            extras = extras,
        )
    }

    private fun decodeKw(s: String) = s.replace("&nbsp;", " ").replace("&amp;", "&")
}

// MARK: - NetEase -------------------------------------------------------------------------

private class NetEaseArtist(private val http: CatalogHttp) {
    private val headers = mapOf("Referer" to "https://music.163.com/")

    /** type=100 目前回的都是无关结果，只留名字精确匹配的（接口恢复后自然生效）。 */
    /** 热门歌手列表：/api/artist/list（area/type 参数会被忽略，只有一份热榜）。 */
    suspend fun hotSingers(limit: Int): List<ArtistInfo> = hotSingersPaged(0, limit)

    suspend fun hotSingersPaged(page: Int, pageSize: Int): List<ArtistInfo> {
        val root = runCatching { JSONObject(http.getText("https://music.163.com/api/artist/list?type=1&area=7&initial=-1&offset=${page * pageSize}&limit=$pageSize")) }.getOrNull()
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
        // 首屏：/api/artist 的 50 首热门歌 + 搜歌第 0 页（30 首）去重；往下翻到底再拉下一页。
        val songsPage = fetchSongsPage(artist, 0)
        val songs = (hotSongs + songsPage.items).distinctBy { it.id }
        val albumsPage = fetchAlbumsPage(artist, 0, songs)
        return ArtistDetail(full, songs, albumsPage.items, 1, 1, songsPage.hasMore, albumsPage.hasMore)
    }

    /**
     * 网易全部歌曲：/api/artist 的 hotSongs 只给 50 首，翻页走 search type=1
     * （s=歌手名，limit=30，offset 翻页），按歌手 id 过滤（搜歌手名会混进同名歌手的歌）。
     * 实测：陈奕迅每页 30 首全是他的。
     */
    suspend fun fetchSongsPage(artist: ArtistInfo, page: Int): CatalogPage<Track> {
        val body = "s=${urlEncode(artist.name)}&type=1&limit=30&offset=${page * 30}"
        val songs = runCatching {
            JSONObject(http.postForm("https://music.163.com/api/search/get", body, headers))
                .optJSONObject("result")?.optJSONArray("songs")
        }.getOrNull() ?: return CatalogPage(emptyList(), false)
        val out = mutableListOf<Track>()
        for (i in 0 until songs.length()) {
            val d = songs.optJSONObject(i) ?: continue
            val artists = d.optJSONArray("artists")
            val mine = (0 until (artists?.length() ?: 0)).any { artists?.optJSONObject(it)?.opt("id")?.toString() == artist.id }
            if (!mine) continue
            buildTrack(d)?.let { out += it }
        }
        return CatalogPage(out, songs.length() >= 30)
    }

    /** 网易全部专辑：/api/artist/albums 按 limit/offset 翻页，每页 30 张。 */
    suspend fun fetchAlbumsPage(artist: ArtistInfo, page: Int, allSongs: List<Track>): CatalogPage<AlbumInfo> {
        val arr = runCatching {
            JSONObject(http.getText("https://music.163.com/api/artist/albums/${artist.id}?limit=30&offset=${page * 30}", headers))
                .optJSONArray("hotAlbums")
        }.getOrNull() ?: return CatalogPage(emptyList(), false)
        val albums = (0 until arr.length()).mapNotNull { i ->
            val d = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = d.opt("id")?.toString()?.ifEmpty { null } ?: return@mapNotNull null
            val name = d.optString("name").ifEmpty { return@mapNotNull null }
            // /api/album 常回验证码挑战，点专辑拉不到完整曲目单；把已加载的全部歌里属于
            // 这张专辑的歌当兜底带上，点进去至少能播这几首，不留空白（卡片上的歌数是 size 真数，不改）。
            // 歌全量之后兜底也跟着全——这就是“显示所有歌曲，专辑才会显示专辑的所有歌曲”。
            val fb = groupAlbums(allSongs).firstOrNull { it.name.equals(name, ignoreCase = true) }?.tracks
                ?: emptyList()
            AlbumInfo(
                source = SourceID.WY,
                id = id,
                name = name,
                singer = artist.name,
                picURL = d.optString("picUrl").ifEmpty { null },
                songCount = d.optInt("size").takeIf { it > 0 },
                detailKey = id,
                fallbackTracks = fb,
            )
        }
        return CatalogPage(albums, albums.size >= 30)
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
