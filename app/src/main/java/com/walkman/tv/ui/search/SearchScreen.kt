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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.di.OverlayEntry
import com.walkman.tv.source.catalog.AlbumInfo
import com.walkman.tv.source.catalog.ArtistDetail
import com.walkman.tv.source.catalog.ArtistInfo
import com.walkman.tv.source.catalog.CatalogPage
import com.walkman.tv.di.getLanIp
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.components.AlbumGroup
import com.walkman.tv.ui.components.EmptyHint
import com.walkman.tv.ui.components.LoadingState
import com.walkman.tv.ui.components.MediaCard
import com.walkman.tv.ui.components.QrDialog
import com.walkman.tv.ui.components.OverlayFocusRestore
import com.walkman.tv.ui.components.rememberOverlayFocusRestore
import com.walkman.tv.ui.components.TrackList
import com.walkman.tv.ui.components.TracksDetailOverlay
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.components.groupAlbums
import com.walkman.tv.ui.playList
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    // 搜索结果列表在第 0 行按上时的出口：回到上面的分类 tab 行（列表里焦点不外漏）。
    val tabRowFocus = remember { FocusRequester() }
    // 搜索结果区的焦点恢复（歌手/歌单/专辑卡片 + 歌曲第 0 行共用）：提到这一层，
    // 左边键盘按右要送焦点进结果区时、和 ResultsPane 里浮层关掉回卡片时都用同一套。
    val restore = rememberOverlayFocusRestore()
    // 结果区各列表的滚动状态：提到这一层，键盘按右重试时先把结果区滚回顶部
    // （最顶上那张卡片可能被滚出屏幕、没组合，请求焦点落不上），再重试。
    val artistRowState = rememberLazyListState()
    val songlistRowState = rememberLazyListState()
    val albumRowState = rememberLazyListState()
    val tracksListState = rememberLazyListState()
    // 换了一批新搜索结果就把结果区滚回顶部：新结果的第一张卡片要在屏幕上，
    // 不然键盘按右同步试最顶上那张会落不上。
    LaunchedEffect(tracks, artists, songlists) {
        runCatching { artistRowState.scrollToItem(0) }
        runCatching { songlistRowState.scrollToItem(0) }
        runCatching { albumRowState.scrollToItem(0) }
        runCatching { tracksListState.scrollToItem(0) }
    }

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
        Column(
            // 左边键盘按右，一定要进右边搜索结果区：键盘键在下面几行，右边卡片不在同一条
            // 横线上，空间焦点搜索过不去、会漏到下面那层（底部播放条那一带），看着就是
            // “往右还是跑到底层去”。这里显式传焦点，不走空间搜索（和首页左边面板按右同一套）。
            // 落点：有歌手卡片时是第一张歌手卡片，没有时是歌单/专辑第一张、再没有是歌曲第 0 行；
            // 浮层开着（专辑详情/歌手页）时先进浮层顶上内容。按键吃掉的前提是焦点真的送出去了：
            // 先同步试一次，落上就吃掉；同步落不上时后台把结果区滚回顶部再重试到落上为止，
            // 绝不出现“按键吃掉了、焦点原地不动”；只有根本没有目标（还没搜出结果）时才放行。
            // 按左不动：这里只拦方向键右。
            modifier = Modifier.width(360.dp).fillMaxHeight().padding(end = 12.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (e.key != Key.DirectionRight) return@onPreviewKeyEvent false
                    // 1. 浮层开着 → 进浮层里的顶上内容。
                    val entry = appContainer.events.topOverlayEntry
                    if (entry != null) {
                        runCatching { entry.focuser.requestFocus() }
                        if (entry.isFocused()) return@onPreviewKeyEvent true
                        scope.launch { entry.retry?.invoke() }
                        return@onPreviewKeyEvent true
                    }
                    // 2. 没浮层 → 同步试最顶上那张卡片，再试上次那张卡片。
                    if (restore.tryFocusSync(restore.topCardKey)) return@onPreviewKeyEvent true
                    if (restore.tryFocusSync(restore.lastFocusedKey)) return@onPreviewKeyEvent true
                    // 3. 同步都落不上：不是没有目标，是目标卡片还没组合好（结果区滚下去了、
                    //    最顶上那张在屏幕外）。先把结果区滚回顶部再重试到落上为止；
                    //    只有根本没有目标（topCardKey 和 lastFocusedKey 都是空）时才放行。
                    val topKey = restore.topCardKey
                    val key = topKey ?: restore.lastFocusedKey ?: return@onPreviewKeyEvent false
                    scope.launch {
                        runCatching { artistRowState.scrollToItem(0) }
                        runCatching { songlistRowState.scrollToItem(0) }
                        runCatching { albumRowState.scrollToItem(0) }
                        runCatching { tracksListState.scrollToItem(0) }
                        restore.restoreTo(key)
                        if (topKey != null) restore.restoreTo(restore.lastFocusedKey)
                    }
                    true
                },
        ) {
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
            SearchTabRow(tab, onSelect = { tab = it }, firstTabFocus = tabRowFocus)
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
                onSongsExitUp = { runCatching { tabRowFocus.requestFocus() } },
                restore = restore,
                artistRowState = artistRowState,
                songlistRowState = songlistRowState,
                albumRowState = albumRowState,
                tracksListState = tracksListState,
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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    /** 歌曲列表在第 0 行按上时的出口（回到上面的分类 tab 行），不设就停在第 0 行。 */
    onSongsExitUp: (() -> Unit)? = null,
    /** 左边键盘按右和浮层关掉回卡片共用的焦点恢复（SearchScreen 那一层建的）。 */
    restore: OverlayFocusRestore,
    artistRowState: LazyListState,
    songlistRowState: LazyListState,
    albumRowState: LazyListState,
    tracksListState: LazyListState,
) {
    val history by appContainer.searchHistoryStore.items.collectAsState()
    // 打开的专辑详情（浮层盖在结果区上，点里面的歌直接播）。换一批搜索结果就关掉，别留着旧专辑。
    var albumDetail by remember { mutableStateOf<AlbumGroup?>(null) }
    // 打开的歌手页（浮层：头像 + 热门歌 + 专辑）。
    var artistDetail by remember { mutableStateOf<ArtistInfo?>(null) }
    // 进浮层之前记住点的那张卡片，出来时焦点回到那张卡片上：浮层关掉时焦点是被整个
    // 清空的，下面的 focusRestorer 不会在这一下触发，不记就回不到点下去的位置。
    var albumEntryKey by remember { mutableStateOf<String?>(null) }
    var artistEntryKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(albumDetail) { if (albumDetail == null) restore.restoreTo(albumEntryKey) }
    LaunchedEffect(artistDetail) { if (artistDetail == null) restore.restoreTo(artistEntryKey) }
    LaunchedEffect(tracks) { albumDetail = null; albumEntryKey = null }
    LaunchedEffect(artists) { artistDetail = null; artistEntryKey = null }
    // 结果区最顶上那张卡片（左边键盘按右的落点）：按屏幕上从上到下的顺序——
    // 歌手卡片 → 歌单卡片 → 专辑卡片 → 歌曲第 0 行；只算当前 tab 显示的那几类。
    LaunchedEffect(tab, artists, songlists, albums, tracks) {
        restore.topCardKey = when {
            (tab == SearchTab.ALL || tab == SearchTab.ARTISTS) && artists.isNotEmpty() ->
                "${artists.first().source.key}_${artists.first().id}"
            (tab == SearchTab.ALL || tab == SearchTab.PLAYLISTS) && songlists.isNotEmpty() -> {
                val first = songlists.distinctBy { it.id + "_" + it.source.key }.first()
                "${first.id}_${first.source.key}"
            }
            (tab == SearchTab.ALL || tab == SearchTab.ALBUMS) && albums.isNotEmpty() ->
                "${albums.first().source.key}_${albums.first().name}"
            (tab == SearchTab.ALL || tab == SearchTab.SONGS) && tracks.isNotEmpty() ->
                "search_tracks_row0"
            else -> null
        }
    }
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
            // focusRestorer 只管方向键重新进入时回到上次那张卡片；浮层关掉后焦点是被
            // 整个清空的，不会触发它，关掉后回哪张卡片由上面那套 entryKey + restore 管。
            Column(modifier = Modifier.fillMaxSize().focusRestorer()) {
            if ((tab == SearchTab.ALL || tab == SearchTab.ARTISTS) && artists.isNotEmpty()) {
                SectionHeader("歌手 (${artists.size})")
                LazyRow(
                    state = artistRowState,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(artists, key = { "${it.source.key}_${it.id}" }) { artist ->
                        val artistKey = "${artist.source.key}_${artist.id}"
                        ArtistCard(
                            artist = artist,
                            modifier = restore.modifierFor(artistKey),
                            onClick = { artistEntryKey = artistKey; artistDetail = artist },
                        )
                    }
                }
                Spacer(Modifier.padding(top = 8.dp))
            }
            if ((tab == SearchTab.ALL || tab == SearchTab.PLAYLISTS) && songlists.isNotEmpty()) {
                SectionHeader("歌单 (${songlists.size})")
                val uniqueSonglists = remember(songlists) { songlists.distinctBy { it.id + "_" + it.source.key } }
                LazyRow(
                    state = songlistRowState,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(uniqueSonglists, key = { it.id + "_" + it.source.key }) { sl ->
                        val slKey = "${sl.id}_${sl.source.key}"
                        Box(modifier = Modifier.width(140.dp)) {
                            MediaCard(
                                title = sl.name,
                                picURL = sl.picURL,
                                subtitle = sl.playCount?.let { "▶ $it" },
                                modifier = restore.modifierFor(slKey),
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
                    state = albumRowState,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(albums, key = { "${it.source.key}_${it.name}" }) { album ->
                        val albumKey = "${album.source.key}_${album.name}"
                        Box(modifier = Modifier.width(140.dp)) {
                            MediaCard(
                                title = album.name,
                                picURL = album.picURL,
                                subtitle = "${album.singer} · ${album.tracks.size} 首",
                                modifier = restore.modifierFor(albumKey),
                                onClick = { albumEntryKey = albumKey; albumDetail = album },
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
                    trapFocus = true,
                    onExitUp = onSongsExitUp,
                    listState = tracksListState,
                    entryFocuser = restore.focuserFor("search_tracks_row0"),
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
private fun SearchTabRow(tab: SearchTab, onSelect: (SearchTab) -> Unit, firstTabFocus: FocusRequester? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchTab.entries.forEachIndexed { index, t ->
            TvPill(
                onClick = { onSelect(t) },
                selected = tab == t,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
                modifier = if (index == 0 && firstTabFocus != null) Modifier.focusRequester(firstTabFocus) else Modifier,
            ) {
                Text(t.label, fontSize = 14.sp, fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

/** 歌手卡片：圆形头像 + 名字 + 歌曲数 · 专辑数。 */
@Composable
fun ArtistCard(
    artist: ArtistInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    com.walkman.tv.ui.components.TvFocusable(
        onClick = onClick,
        modifier = Modifier.width(140.dp).then(modifier),
    ) {
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
    // 焦点锚点：曲目单拉不到时页面上只有一行提示、没有可聚焦的东西，遥控器按键会落在
    // 被盖住的下层页面上、看着像冻住。空提示下面放一个可聚焦的“返回”，浮层打开就抢焦点。
    val anchorFocus = remember { FocusRequester() }
    var anchorFocused by remember { mutableStateOf(false) }
    // 当前焦点在曲目单的第几行（入口落位检查用：anchorFocus 经 entryFocuser 挂在第 0 行上）。
    var focusedRow by remember { mutableStateOf(-1) }
    val songListState = rememberLazyListState()
    // 浮层入口：左边面板按右 / 顶栏按下时进浮层里的顶上内容（曲目单第 0 行；曲目单为空时是“返回”）。
    // 嵌套在歌手页上面时它后登记、在栈顶；关掉注销后栈顶回到歌手页。
    DisposableEffect(info) {
        val entry = OverlayEntry(
            key = "album_${info.source.key}_${info.id}",
            focuser = anchorFocus,
            isFocused = { anchorFocused || focusedRow == 0 },
            retry = {
                var landed = false
                for (attempt in 0 until 12) {
                    if (anchorFocused || focusedRow == 0) { landed = true; break }
                    // 第 0 行可能还没组合好（列表滚下去了）：先把列表滚回顶部再试。
                    runCatching { songListState.scrollToItem(0) }
                    runCatching { anchorFocus.requestFocus() }
                    delay(70)
                    if (anchorFocused || focusedRow == 0) { landed = true; break }
                }
                landed
            },
        )
        appContainer.events.registerOverlayEntry(entry)
        onDispose { appContainer.events.unregisterOverlayEntry("album_${info.source.key}_${info.id}") }
    }
    LaunchedEffect(tracks.isEmpty()) {
        if (tracks.isNotEmpty()) return@LaunchedEffect
        for (attempt in 0 until 20) {
            if (anchorFocused) break
            runCatching { anchorFocus.requestFocus() }
            delay(100)
        }
    }
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
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("这张专辑暂时拉不到曲目单", color = AppColors.TextSecondary, fontSize = 15.sp)
                    Spacer(Modifier.height(16.dp))
                    TvPill(
                        onClick = onBack,
                        modifier = Modifier
                            .focusRequester(anchorFocus)
                            .onFocusChanged { if (it.isFocused) anchorFocused = true },
                    ) {
                        Text("返回", fontSize = 14.sp)
                    }
                }
            } else {
                val nowId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id
                TrackList(
                    tracks = tracks,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    nowPlayingId = nowId,
                    initialFocus = true,
                    trapFocus = true,
                    onFocusedIndexChange = { focusedRow = it },
                    listState = songListState,
                    entryFocuser = anchorFocus,
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
    // 点专辑卡片进专辑详情之前记住那张卡片，出来时焦点回到那张卡片上（不是回首页）。
    val albumRestore = rememberOverlayFocusRestore()
    var albumEntryKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openAlbum) { if (openAlbum == null) albumRestore.restoreTo(albumEntryKey) }
    val scope = rememberCoroutineScope()
    // 专辑区平时占大半屏，热门歌只剩一行半：进歌单深处（第 2 行起）就把专辑区收起来，
    // 歌单占满剩余高度、一次能看五六行；回第 0 行按上时再展开、焦点落回第一张专辑卡片。
    var albumsCollapsed by remember { mutableStateOf(false) }
    // 全部歌曲 / 全部专辑（Yang 定：热门歌曲那一栏是这个歌手的所有歌曲，能翻到底；
    // 专辑也一样要他全部专辑）。首屏是 fetchArtistDetail 给的第 0 页，
    // 往下翻到底自动拉下一页（和歌手浏览页同一套做法）。
    var songs by remember { mutableStateOf<List<Track>>(emptyList()) }
    var albums by remember { mutableStateOf<List<AlbumInfo>>(emptyList()) }
    var songsHasMore by remember { mutableStateOf(true) }
    var albumsHasMore by remember { mutableStateOf(true) }
    var songsPage by remember { mutableStateOf(1) }
    var albumsPage by remember { mutableStateOf(1) }
    var loadingSongs by remember { mutableStateOf(false) }
    var loadingAlbums by remember { mutableStateOf(false) }
    // 当前焦点在歌单的第几行（入口落位检查 + 收起专辑区用）。
    var focusedRow by remember { mutableStateOf(-1) }
    val songListState = rememberLazyListState()
    // LazyRow 和 LazyColumn 共用 LazyListState（没有 rememberLazyRowState 这个函数）。
    val albumRowState = rememberLazyListState()
    LaunchedEffect(artist) {
        detail = runCatching { appContainer.artists.fetchArtistDetail(artist) }.getOrNull()
        val d = detail
        songs = d?.hotSongs ?: emptyList()
        albums = d?.albums ?: emptyList()
        songsHasMore = d?.songsHasMore ?: false
        albumsHasMore = d?.albumsHasMore ?: false
        songsPage = d?.songsNextPage ?: 1
        albumsPage = d?.albumsNextPage ?: 1
        albumsCollapsed = false
        focusedRow = -1
        loaded = true
    }
    fun loadSongsPage() {
        if (loadingSongs || !songsHasMore) return
        val page = songsPage
        scope.launch {
            loadingSongs = true
            val r = runCatching {
                appContainer.artists.fetchArtistSongsPage(artist, page, detail?.artist?.picURL)
            }.getOrDefault(CatalogPage(emptyList(), false))
            val before = songs.size
            songs = (songs + r.items).distinctBy { it.id }
            // 这一页一个新歌都没加上（过滤把整页都筛掉了）：别再自动拉了，不然翻到底会空转。
            songsHasMore = if (songs.size == before) false else r.hasMore
            songsPage = page + 1
            loadingSongs = false
        }
    }
    fun loadAlbumsPage() {
        if (loadingAlbums || !albumsHasMore) return
        val page = albumsPage
        scope.launch {
            loadingAlbums = true
            val r = runCatching {
                appContainer.artists.fetchArtistAlbumsPage(artist, page, songs)
            }.getOrDefault(CatalogPage(emptyList(), false))
            val before = albums.size
            albums = (albums + r.items).distinctBy { it.id }
            albumsHasMore = if (albums.size == before) false else r.hasMore
            albumsPage = page + 1
            loadingAlbums = false
        }
    }
    // 翻到底自动拉下一页。
    val songsNearEnd by remember {
        derivedStateOf {
            val info = songListState.layoutInfo
            val total = info.totalItemsCount
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && last >= total - 6
        }
    }
    LaunchedEffect(songsNearEnd) { if (songsNearEnd) loadSongsPage() }
    val albumsNearEnd by remember {
        derivedStateOf {
            val info = albumRowState.layoutInfo
            val total = info.totalItemsCount
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && last >= total - 6
        }
    }
    LaunchedEffect(albumsNearEnd) { if (albumsNearEnd) loadAlbumsPage() }
    // 焦点锚点：浮层自己不抢焦点时（加载中 / 拉不到 / 热门歌为空），页面上没有可聚焦的
    // 东西，遥控器按键会落在被盖住的下层页面上、看着像冻住。锚点保证浮层永远有可聚焦项：
    // 加载中和空提示时是“返回” pill；只有专辑没有热门歌时是第一张专辑卡片；
    // 热门歌在时 TrackList 自己抢初始焦点，不用锚点。
    val anchorFocus = remember { FocusRequester() }
    var anchorFocused by remember { mutableStateOf(false) }
    // 入口节点（第一张专辑卡片 / “返回” pill）现在是不是正被聚焦——浮层入口的落位检查用。
    // 和 anchorFocused 分开：那个是只进不出的 latch（打开时抢焦点用），这个是双向的。
    var entryFocused by remember { mutableStateOf(false) }
    val needAnchor = !loaded || detail == null || songs.isEmpty()
    LaunchedEffect(loaded, detail) {
        if (!needAnchor) return@LaunchedEffect
        for (attempt in 0 until 20) {
            if (anchorFocused) break
            runCatching { anchorFocus.requestFocus() }
            delay(100)
        }
    }
    // 浮层入口：左边面板按右 / 顶栏按下时进浮层里的顶上内容。顶上内容是第一张专辑卡片
    // （anchorFocus 挂在它上面）；专辑区收起来时是歌单第 0 行（anchorFocus 经
    // TrackList 的 entryFocuser 挂在第 0 行上）；加载中 / 拉不到时是“返回” pill。
    DisposableEffect(artist) {
        val entry = OverlayEntry(
            key = "artist_${artist.source.key}_${artist.id}",
            focuser = anchorFocus,
            isFocused = { entryFocused || focusedRow == 0 },
            retry = {
                var landed = false
                for (attempt in 0 until 12) {
                    if (entryFocused || focusedRow == 0) { landed = true; break }
                    // 顶上那张卡片可能还没组合好：专辑区开着就把专辑横排滚回头，
                    // 收起来了就把歌单滚回顶部，再试。
                    if (albumsCollapsed) runCatching { songListState.scrollToItem(0) }
                    else runCatching { albumRowState.scrollToItem(0) }
                    runCatching { anchorFocus.requestFocus() }
                    delay(70)
                    if (entryFocused || focusedRow == 0) { landed = true; break }
                }
                landed
            },
        )
        appContainer.events.registerOverlayEntry(entry)
        onDispose { appContainer.events.unregisterOverlayEntry("artist_${artist.source.key}_${artist.id}") }
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
                !loaded -> Box(modifier = Modifier.fillMaxSize()) {
                    LoadingState(Modifier.fillMaxSize())
                    TvPill(
                        onClick = onBack,
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                            .focusRequester(anchorFocus)
                            .onFocusChanged { if (it.isFocused) anchorFocused = true; entryFocused = it.isFocused },
                    ) {
                        Text("返回", fontSize = 14.sp)
                    }
                }
                detail == null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("歌手信息拉不到，换个平台试试", color = AppColors.TextSecondary, fontSize = 15.sp)
                    Spacer(Modifier.height(16.dp))
                    TvPill(
                        onClick = onBack,
                        modifier = Modifier
                            .focusRequester(anchorFocus)
                            .onFocusChanged { if (it.isFocused) anchorFocused = true; entryFocused = it.isFocused },
                    ) {
                        Text("返回", fontSize = 14.sp)
                    }
                }
                else -> {
                    if (!albumsCollapsed && albums.isNotEmpty()) {
                        SectionHeader("专辑 (${detail?.artist?.albumCount ?: artist.albumCount ?: albums.size})")
                        LazyRow(
                            state = albumRowState,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            itemsIndexed(albums, key = { _, it -> "${it.source.key}_${it.id}" }) { index, album ->
                                val albumKey = "${album.source.key}_${album.id}"
                                Box(modifier = Modifier.width(140.dp)) {
                                    MediaCard(
                                        title = album.name,
                                        picURL = album.picURL,
                                        subtitle = "${album.singer} · ${album.songCount?.let { "$it 首" } ?: ""}",
                                        modifier = albumRestore.modifierFor(albumKey)
                                            .then(if (index == 0) Modifier
                                                .focusRequester(anchorFocus)
                                                .onFocusChanged { if (it.isFocused) anchorFocused = true; entryFocused = it.isFocused }
                                            else Modifier),
                                        onClick = { albumEntryKey = albumKey; openAlbum = album },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.padding(top = 8.dp))
                    }
                    if (songs.isNotEmpty()) {
                        SectionHeader("全部歌曲 (${detail?.artist?.songCount ?: artist.songCount ?: songs.size})")
                        val nowId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id
                        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            TrackList(
                                tracks = songs,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                nowPlayingId = nowId,
                                initialFocus = true,
                                trapFocus = true,
                                onFocusedIndexChange = { focusedRow = it; if (it >= 1) albumsCollapsed = true else if (it == 0) albumsCollapsed = false },
                                onExitUp = {
                                    // 回专辑行：先把专辑区展开，等卡片组合好再把焦点落到第一张专辑卡片上。
                                    albumsCollapsed = false
                                    scope.launch {
                                        repeat(10) {
                                            runCatching { anchorFocus.requestFocus() }
                                            if (anchorFocused) return@launch
                                            delay(100)
                                        }
                                    }
                                },
                                listState = songListState,
                                entryFocuser = anchorFocus,
                                onPlay = { idx -> playList(songs, idx); onOpenPlayer() },
                            )
                            val footer = when {
                                loadingSongs -> "正在加载更多…"
                                !songsHasMore -> "已显示全部 ${songs.size} 首"
                                else -> ""
                            }
                            if (footer.isNotEmpty()) {
                                Text(
                                    footer,
                                    color = AppColors.TextMuted,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                )
                            }
                        }
                    } else if (albums.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("这个歌手暂时没有可播的内容", color = AppColors.TextSecondary, fontSize = 15.sp)
                            Spacer(Modifier.height(16.dp))
                            TvPill(
                                onClick = onBack,
                                modifier = Modifier
                                    .focusRequester(anchorFocus)
                                    .onFocusChanged { if (it.isFocused) anchorFocused = true; entryFocused = it.isFocused },
                            ) {
                                Text("返回", fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
        openAlbum?.let { album ->
            AlbumDetailOverlay(info = album, onBack = { openAlbum = null; albumsCollapsed = false }, onOpenPlayer = onOpenPlayer)
        }
    }
    androidx.activity.compose.BackHandler { onBack() }
}
