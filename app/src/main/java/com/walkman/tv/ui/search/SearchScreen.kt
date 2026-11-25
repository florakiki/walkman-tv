package com.walkman.tv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.source.catalog.AlbumInfo
import com.walkman.tv.source.catalog.ArtistDetail
import com.walkman.tv.source.catalog.ArtistInfo
import com.walkman.tv.di.getLanIp
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.components.AlbumGroup
import com.walkman.tv.ui.components.EmptyHint
import com.walkman.tv.ui.components.LoadingState
import com.walkman.tv.ui.components.MediaCard
import com.walkman.tv.ui.components.QrDialog
import com.walkman.tv.ui.components.TrackList
import com.walkman.tv.ui.components.TracksDetailOverlay
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.components.groupAlbums
import com.walkman.tv.ui.playList
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    // 不显示平台来源：搜索不分平台筛，四个平台一起搜，源的选择全在后台自动做。
    // QQ风搜索页只有分类 tab（全部 / 单曲 / 专辑 / 歌单 / 歌手），没有平台筛选项。
    var tab by remember { mutableStateOf(SearchTab.ALL) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var songlists by remember { mutableStateOf<List<SonglistInfo>>(emptyList()) }
    var artists by remember { mutableStateOf<List<ArtistInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var hotColumns by remember { mutableStateOf<List<com.walkman.tv.source.catalog.HotSearchColumn>>(emptyList()) }

    // Load hot-search words once when the screen first appears (before any search).
    LaunchedEffect(Unit) {
        hotColumns = runCatching { appContainer.hotSearch.fetchAll() }.getOrDefault(emptyList())
    }

    fun runSearch() {
        if (query.isBlank()) return
        scope.launch {
            loading = true
            searched = true
            val q = query
            val t = tab
            // Record into history before kicking off the request so it shows up on the next
            // empty-query render even if the result list ends up empty.
            appContainer.searchHistoryStore.record(q)
            // 每个 tab 只拉自己要的那类结果，别混在一起；平台不筛，四个平台一起搜。
            val result = runCatching {
                coroutineScope {
                    val tracksJob = async {
                        if (t == SearchTab.ALL || t == SearchTab.SONGS || t == SearchTab.ALBUMS) {
                            appContainer.catalogs.searchAll(q)
                        } else emptyList()
                    }
                    val songlistsJob = async {
                        if (t == SearchTab.ALL || t == SearchTab.PLAYLISTS) {
                            appContainer.songlists.searchAll(q)
                        } else emptyList()
                    }
                    val artistsJob = async {
                        if (t == SearchTab.ALL || t == SearchTab.ARTISTS) {
                            appContainer.artists.searchArtists(q, null)
                        } else emptyList()
                    }
                    Triple(tracksJob.await(), songlistsJob.await(), artistsJob.await())
                }
            }.getOrDefault(Triple(emptyList<Track>(), emptyList<SonglistInfo>(), emptyList<ArtistInfo>()))
            tracks = result.first
            songlists = result.second
            artists = result.third
            loading = false
        }
    }

    // Re-run search when the category tab changes (only after the first manual search).
    LaunchedEffect(tab) {
        if (searched) runSearch()
    }

    // Phone-to-TV: incoming search keywords from the QR-served form.
    LaunchedEffect(Unit) {
        appContainer.events.qrSearchKeyword.collect { k ->
            query = k
            showQr = false
            runSearch()
        }
    }

    Row(modifier = modifier.fillMaxSize().padding(top = 8.dp)) {
        // LEFT: search input + virtual keypad
        Column(modifier = Modifier.width(360.dp).fillMaxHeight().padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) { SearchInputBox(query) }
                Spacer(Modifier.width(8.dp))
                TvPill(
                    onClick = { showQr = true },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    contentPadding = PaddingValues(10.dp),
                ) {
                    Icon(Icons.Filled.QrCode2, contentDescription = "扫码搜索", modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.padding(top = 12.dp))
            com.walkman.tv.ui.components.TvKeypad(
                onAppend = { ch -> query += ch },
                onBackspace = { if (query.isNotEmpty()) query = query.dropLast(1) },
                onClear = { query = "" },
                onConfirm = { runSearch() },
                confirmLabel = "搜索",
            )
        }

        // RIGHT: category tabs + results
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            SearchTabRow(tab, onSelect = { tab = it })
            Spacer(Modifier.padding(top = 8.dp))
            ResultsPane(
                loading = loading,
                searched = searched,
                tab = tab,
                songlists = songlists,
                albums = remember(tracks) { groupAlbums(tracks) },
                tracks = tracks,
                artists = artists,
                hotColumns = hotColumns,
                onOpenPlayer = onOpenPlayer,
                onPickHistory = { kw ->
                    query = kw
                    runSearch()
                },
                onPickHot = { kw ->
                    query = kw
                    runSearch()
                },
                onPickSonglist = { info ->
                    scope.launch {
                        val svc = appContainer.songlists.serviceFor(info.source) ?: return@launch
                        val detail = runCatching { svc.fetchDetail(info) }.getOrNull()
                        val ts = detail?.tracks.orEmpty()
                        if (ts.isNotEmpty()) {
                            playList(ts, 0); onOpenPlayer()
                        }
                    }
                },
            )
        }
    }

    if (showQr) {
        val ip = remember { getLanIp() }
        val port = appContainer.localServer?.boundPort
        if (ip != null && port != null) {
            QrDialog(
                url = "http://$ip:$port/search",
                title = "扫码搜索",
                subtitle = "手机扫码、电脑直接打开地址都可以，输入关键词后会自动回到电视搜索",
                onDismiss = { showQr = false },
            )
        } else {
            QrUnavailableDialog(onDismiss = { showQr = false })
        }
    }
}

@Composable
private fun QrUnavailableDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("无法启动扫码") },
        text = { Text("请确认电视已连接 Wi-Fi 且本地服务已启动后再试。") },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("好", color = AppColors.AccentGreen)
            }
        },
        containerColor = AppColors.BgPanel,
        titleContentColor = AppColors.TextPrimary,
        textContentColor = AppColors.TextSecondary,
    )
}

// MARK: - Left panel ---------------------------------------------------------------------------

@Composable
private fun SearchInputBox(query: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.BgPanel)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Text(
            text = query.ifEmpty { "输入关键词" },
            color = if (query.isEmpty()) AppColors.TextMuted else AppColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - Right panel --------------------------------------------------------------------------

@Composable
private fun ResultsPane(
    loading: Boolean,
    searched: Boolean,
    tab: SearchTab,
    songlists: List<SonglistInfo>,
    albums: List<AlbumGroup>,
    tracks: List<Track>,
    artists: List<ArtistInfo>,
    hotColumns: List<com.walkman.tv.source.catalog.HotSearchColumn>,
    onOpenPlayer: () -> Unit,
    onPickSonglist: (SonglistInfo) -> Unit,
    onPickHistory: (String) -> Unit = {},
    onPickHot: (String) -> Unit = {},
) {
    val history by appContainer.searchHistoryStore.items.collectAsState()
    // 打开的专辑详情（浮层盖在结果区上，点里面的歌直接播）。换一批搜索结果就关掉，别留着旧专辑。
    var albumDetail by remember { mutableStateOf<AlbumGroup?>(null) }
    // 打开的歌手页（浮层：头像 + 热门歌 + 专辑）。
    var artistDetail by remember { mutableStateOf<ArtistInfo?>(null) }
    LaunchedEffect(tracks) { albumDetail = null }
    LaunchedEffect(artists) { artistDetail = null }
    when {
        loading -> LoadingState(Modifier.fillMaxSize())
        !searched -> {
            // Before searching: recent-search chips (if any) on top, then the 4-column hot lists.
            Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
                if (history.isNotEmpty()) {
                    SearchHistoryPane(history = history, onPick = onPickHistory)
                    Spacer(Modifier.padding(top = 10.dp))
                }
                if (hotColumns.any { it.words.isNotEmpty() }) {
                    HotSearchGrid(columns = hotColumns, onPick = onPickHot)
                } else if (history.isEmpty()) {
                    EmptyHint("输入关键词开始搜索", Modifier.fillMaxSize())
                }
            }
        }
        songlists.isEmpty() && tracks.isEmpty() && artists.isEmpty() ->
            EmptyHint("没有找到结果", Modifier.fillMaxSize())
        else -> Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
            if ((tab == SearchTab.ALL || tab == SearchTab.ARTISTS) && artists.isNotEmpty()) {
                SectionHeader("歌手 (${artists.size})")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(artists, key = { "${it.source.key}_${it.id}" }) { artist ->
                        ArtistCard(artist = artist, onClick = { artistDetail = artist })
                    }
                }
                Spacer(Modifier.padding(top = 8.dp))
            }
            if ((tab == SearchTab.ALL || tab == SearchTab.PLAYLISTS) && songlists.isNotEmpty()) {
                SectionHeader("歌单 (${songlists.size})")
                val uniqueSonglists = remember(songlists) { songlists.distinctBy { it.id + "_" + it.source.key } }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(uniqueSonglists, key = { it.id + "_" + it.source.key }) { sl ->
                        Box(modifier = Modifier.width(140.dp)) {
                            MediaCard(
                                title = sl.name,
                                picURL = sl.picURL,
                                subtitle = sl.playCount?.let { "▶ $it" },
                                onClick = { onPickSonglist(sl) },
                            )
                        }
                    }
                }
                Spacer(Modifier.padding(top = 8.dp))
            }
            if ((tab == SearchTab.ALL || tab == SearchTab.ALBUMS) && albums.isNotEmpty()) {
                SectionHeader("专辑 (${albums.size})")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(albums, key = { "${it.source.key}_${it.name}" }) { album ->
                        Box(modifier = Modifier.width(140.dp)) {
                            MediaCard(
                                title = album.name,
                                picURL = album.picURL,
                                subtitle = "${album.singer} · ${album.tracks.size} 首",
                                onClick = { albumDetail = album },
                            )
                        }
                    }
                }
                Spacer(Modifier.padding(top = 8.dp))
            }
            if ((tab == SearchTab.ALL || tab == SearchTab.SONGS) && tracks.isNotEmpty()) {
                SectionHeader("歌曲 (${tracks.size})")
                val nowId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id
                TrackList(
                    tracks = tracks,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    nowPlayingId = nowId,
                    onPlay = { idx -> playList(tracks, idx); onOpenPlayer() },
                )
            }
            }
            albumDetail?.let { album ->
                AlbumDetailOverlay(
                    album = album,
                    onBack = { albumDetail = null },
                    onOpenPlayer = onOpenPlayer,
                )
            }
            artistDetail?.let { artist ->
                ArtistDetailOverlay(
                    artist = artist,
                    onBack = { artistDetail = null },
                    onOpenPlayer = onOpenPlayer,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = AppColors.AccentGreen,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/**
 * 热搜：四个平台的热搜词合并去重，放进一排药丸里（不按平台分列、不显示平台名）。
 * 点一个词就填进搜索框、立刻搜。
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun HotSearchGrid(
    columns: List<com.walkman.tv.source.catalog.HotSearchColumn>,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("热门搜索")
        val words = remember(columns) { columns.flatMap { it.words }.distinct().take(24) }
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            words.forEach { word ->
                TvPill(
                    onClick = { onPick(word) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(word, fontSize = 13.sp)
                }
            }
        }
    }
}

/** Recent-search chips. Rendered when the user hasn't searched yet but the store has entries.
 *  Wraps onto multiple lines via FlowRow so we don't horizontally clip long histories. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SearchHistoryPane(history: List<String>, onPick: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    // fillMaxWidth (not fillMaxSize): this pane now stacks above the hot-search grid, so it must
    // wrap its content height instead of eating the whole column.
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "最近搜索",
                color = AppColors.AccentGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            TvPill(
                onClick = { scope.launch { appContainer.searchHistoryStore.clear() } },
                accent = AppColors.Danger,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("清空", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.padding(top = 8.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            history.forEach { kw ->
                TvPill(
                    onClick = { onPick(kw) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(kw, fontSize = 13.sp)
                }
            }
        }
    }
}

// MARK: - 分类 tab + 歌手 / 专辑详情（QQ音乐搜索页布局） -----------------------------------------

/** 搜索分类 tab：全部 / 单曲 / 专辑 / 歌单 / 歌手。 */
enum class SearchTab(val label: String) {
    ALL("全部"),
    SONGS("单曲"),
    ALBUMS("专辑"),
    PLAYLISTS("歌单"),
    ARTISTS("歌手"),
}

@Composable
private fun SearchTabRow(tab: SearchTab, onSelect: (SearchTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchTab.entries.forEach { t ->
            TvPill(
                onClick = { onSelect(t) },
                selected = tab == t,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
            ) {
                Text(t.label, fontSize = 14.sp, fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

/** 歌手卡片：圆形头像 + 名字 + 歌曲数 · 专辑数。 */
@Composable
fun ArtistCard(artist: ArtistInfo, onClick: () -> Unit) {
    com.walkman.tv.ui.components.TvFocusable(onClick = onClick, modifier = Modifier.width(140.dp)) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            com.walkman.tv.ui.components.Artwork(
                artist.picURL,
                modifier = Modifier.size(96.dp),
                shape = CircleShape,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                artist.name,
                color = AppColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val stats = listOfNotNull(
                artist.songCount?.let { "${it} 首" },
                artist.albumCount?.let { "${it} 张专辑" },
            ).joinToString(" · ")
            // 不显示平台来源：没有统计数时不写平台名，留空。
            if (stats.isNotEmpty()) {
                Text(
                    stats,
                    color = AppColors.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 把搜索结果里聚合的专辑转成详情接口要用的 AlbumInfo（QQ 用 albumMid，酷狗用 albumId）。 */
private fun AlbumGroup.toAlbumInfo(): AlbumInfo {
    val first = tracks.firstOrNull()
    val detailKey = when (source) {
        com.walkman.tv.data.model.SourceID.TX -> first?.extras?.get("albumMid") ?: first?.albumId ?: name
        else -> first?.albumId ?: name
    }
    return AlbumInfo(
        source = source,
        id = first?.albumId ?: name,
        name = name,
        singer = singer,
        picURL = picURL,
        songCount = tracks.size,
        detailKey = detailKey,
        fallbackTracks = tracks,
    )
}

/**
 * 专辑详情浮层：点开先拉完整曲目单（QQ / 酷狗走详情接口），拉到了就显示完整的，
 * 拉不到（酷我 / 网易）就显示搜索结果里聚合出来的那几首，不留空白。
 */
@Composable
fun AlbumDetailOverlay(
    album: AlbumGroup,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val info = remember(album) { album.toAlbumInfo() }
    AlbumDetailOverlay(info = info, onBack = onBack, onOpenPlayer = onOpenPlayer)
}

@Composable
fun AlbumDetailOverlay(
    info: AlbumInfo,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    var fullTracks by remember { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(info) {
        fullTracks = runCatching { appContainer.artists.fetchAlbumDetail(info) }.getOrDefault(emptyList())
    }
    val tracks = fullTracks?.takeIf { it.isNotEmpty() } ?: info.fallbackTracks
    Box(modifier = Modifier.fillMaxSize().background(AppColors.BgDeep).focusGroup()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    info.name,
                    color = AppColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (fullTracks == null) {
                    Text("加载完整曲目…", color = AppColors.TextMuted, fontSize = 12.sp)
                }
            }
            Text(
                "${info.singer} · ${tracks.size} 首",
                color = AppColors.TextMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            if (tracks.isEmpty()) {
                EmptyHint("这张专辑暂时拉不到曲目单", Modifier.fillMaxSize())
            } else {
                val nowId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id
                TrackList(
                    tracks = tracks,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    nowPlayingId = nowId,
                    initialFocus = true,
                    onPlay = { idx -> playList(tracks, idx); onOpenPlayer() },
                )
            }
        }
    }
    androidx.activity.compose.BackHandler { onBack() }
}

/** 歌手页浮层：头像 + 名字 + 统计，热门歌曲列表，下面是专辑横排卡片，点专辑进专辑详情。 */
@Composable
fun ArtistDetailOverlay(
    artist: ArtistInfo,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    var detail by remember { mutableStateOf<ArtistDetail?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var openAlbum by remember { mutableStateOf<AlbumInfo?>(null) }
    LaunchedEffect(artist) {
        detail = runCatching { appContainer.artists.fetchArtistDetail(artist) }.getOrNull()
        loaded = true
    }
    Box(modifier = Modifier.fillMaxSize().background(AppColors.BgDeep).focusGroup()) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.walkman.tv.ui.components.Artwork(
                    artist.picURL,
                    modifier = Modifier.size(96.dp),
                    shape = CircleShape,
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        artist.name,
                        color = AppColors.TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val stats = listOfNotNull(
                        artist.songCount?.let { "${it} 首" },
                        artist.albumCount?.let { "${it} 张专辑" },
                    ).joinToString(" · ")
                    if (stats.isNotEmpty()) {
                        Text(stats, color = AppColors.TextMuted, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                !loaded -> LoadingState(Modifier.fillMaxSize())
                detail == null -> EmptyHint("歌手信息拉不到，换个平台试试", Modifier.fillMaxSize())
                else -> {
                    val d = detail!!
                    if (d.albums.isNotEmpty()) {
                        SectionHeader("专辑 (${d.albums.size})")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(d.albums, key = { "${it.source.key}_${it.id}" }) { album ->
                                Box(modifier = Modifier.width(140.dp)) {
                                    MediaCard(
                                        title = album.name,
                                        picURL = album.picURL,
                                        subtitle = "${album.singer} · ${album.songCount?.let { "$it 首" } ?: ""}",
                                        onClick = { openAlbum = album },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.padding(top = 8.dp))
                    }
                    if (d.hotSongs.isNotEmpty()) {
                        SectionHeader("热门歌曲 (${d.hotSongs.size})")
                        val nowId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id
                        TrackList(
                            tracks = d.hotSongs,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            nowPlayingId = nowId,
                            initialFocus = true,
                            onPlay = { idx -> playList(d.hotSongs, idx); onOpenPlayer() },
                        )
                    } else if (d.albums.isEmpty()) {
                        EmptyHint("这个歌手暂时没有可播的内容", Modifier.fillMaxSize())
                    }
                }
            }
        }
        openAlbum?.let { album ->
            AlbumDetailOverlay(info = album, onBack = { openAlbum = null }, onOpenPlayer = onOpenPlayer)
        }
    }
    androidx.activity.compose.BackHandler { onBack() }
}
