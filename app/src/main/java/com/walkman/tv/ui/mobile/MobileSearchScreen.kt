@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.walkman.tv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.source.catalog.AlbumInfo
import com.walkman.tv.source.catalog.ArtistDetail
import com.walkman.tv.source.catalog.ArtistInfo
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.search.AllAlbumsOverlay
import com.walkman.tv.ui.components.AlbumGroup
import com.walkman.tv.ui.components.groupAlbums
import com.walkman.tv.ui.playList
import com.walkman.tv.ui.search.SearchTab
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 手机搜索：输入框 + 分类 tab + 歌曲/歌单/歌手结果；没搜时显示搜索历史和热搜词。
 * 搜索接口和电视版同一套（catalogs.searchAll / songlists.searchAll）。
 */
@Composable
fun MobileSearchScreen(
    onOpenSonglist: (SonglistInfo) -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
    var hotColumns by remember { mutableStateOf<List<com.walkman.tv.source.catalog.HotSearchColumn>>(emptyList()) }
    val history by appContainer.searchHistoryStore.items.collectAsState()

    LaunchedEffect(Unit) {
        hotColumns = runCatching { appContainer.hotSearch.fetchAll() }.getOrDefault(emptyList())
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        scope.launch {
            loading = true
            searched = true
            appContainer.searchHistoryStore.record(q)
            val t = tab
            // 每个 tab 只拉自己要的那类结果，别混在一起；平台不筛，四个平台一起搜。
            val result = runCatching {
                coroutineScope {
                    val tracksJob = async {
                        if (t == SearchTab.ALL || t == SearchTab.SONGS || t == SearchTab.ALBUMS) {
                            appContainer.catalogs.searchAll(q)
                        } else emptyList()
                    }
                    val listsJob = async {
                        if (t == SearchTab.ALL || t == SearchTab.PLAYLISTS) {
                            appContainer.songlists.searchAll(q)
                        } else emptyList()
                    }
                    val artistsJob = async {
                        if (t == SearchTab.ALL || t == SearchTab.ARTISTS) {
                            appContainer.artists.searchArtists(q, null)
                        } else emptyList()
                    }
                    Triple(tracksJob.await(), listsJob.await(), artistsJob.await())
                }
            }.getOrDefault(Triple(emptyList<Track>(), emptyList<SonglistInfo>(), emptyList<ArtistInfo>()))
            tracks = result.first
            songlists = result.second
            artists = result.third
            loading = false
        }
    }

    LaunchedEffect(tab) {
        if (searched) runSearch()
    }

    val playingId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id
    // 搜歌手 → 专辑：搜索结果里每首歌都带专辑名，按专辑聚合（见 AlbumGroups）。
    val albums = remember(tracks) { groupAlbums(tracks) }
    // 热搜：四个平台的热搜词合并去重，不显示平台名。
    val hotWords = remember(hotColumns) { hotColumns.flatMap { it.words }.distinct().take(20) }
    var openAlbum by remember { mutableStateOf<AlbumGroup?>(null) }
    var openAlbumInfo by remember { mutableStateOf<AlbumInfo?>(null) }
    var openArtist by remember { mutableStateOf<ArtistInfo?>(null) }
    LaunchedEffect(tracks) { openAlbum = null }
    LaunchedEffect(artists) { openArtist = null }

    Box(modifier.fillMaxSize()) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索歌曲、歌手、歌单", color = AppColors.TextMuted) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AppColors.BrandPrimary) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "清空", modifier = Modifier.size(20.dp))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        // 分类 tab（横着能滑）：全部 / 单曲 / 专辑 / 歌单 / 歌手。
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SearchTab.entries) { t ->
                PlatformChip(t.label, tab == t) { tab = t }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.BrandPrimary)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (!searched) {
                if (history.isNotEmpty()) {
                    item {
                        Text("最近搜索", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.padding(vertical = 6.dp))
                        FlowRow {
                            history.forEach { h ->
                                AssistChip(
                                    onClick = { query = h; runSearch() },
                                    label = { Text(h, fontSize = 13.sp) },
                                    modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                                )
                            }
                        }
                    }
                }
                if (hotWords.isNotEmpty()) {
                    item {
                        Text("热搜", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.padding(vertical = 6.dp))
                        FlowRow {
                            hotWords.forEach { w ->
                                AssistChip(
                                    onClick = { query = w; runSearch() },
                                    label = { Text(w, fontSize = 13.sp) },
                                    modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                if (tracks.isEmpty() && songlists.isEmpty() && artists.isEmpty()) {
                    item {
                        Text("没搜到相关结果，换个关键词试试。", color = AppColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
                    }
                }
                if ((tab == SearchTab.ALL || tab == SearchTab.ARTISTS) && artists.isNotEmpty()) {
                    item {
                        Text("歌手", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    items(artists, key = { "${it.source.key}_${it.id}" }) { artist ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { openArtist = artist }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverImage(
                                artist.picURL,
                                modifier = Modifier.size(52.dp).clip(CircleShape),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(artist.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val stats = listOfNotNull(
                                    artist.songCount?.let { "${it} 首" },
                                    artist.albumCount?.let { "${it} 张专辑" },
                                ).joinToString(" · ")
                                // 不显示平台来源：没有统计数时不写平台名，留空。
                                if (stats.isNotEmpty()) {
                                    Text(stats, fontSize = 12.sp, color = AppColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AppColors.TextMuted)
                        }
                    }
                }
                if ((tab == SearchTab.ALL || tab == SearchTab.SONGS) && tracks.isNotEmpty()) {
                    item {
                        Text("歌曲", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    items(tracks) { track ->
                        val idx = tracks.indexOf(track)
                        TrackRowMobile(
                            track = track,
                            isPlaying = track.id == playingId,
                            onClick = {
                                playList(tracks, idx)
                                onOpenPlayer()
                            },
                        )
                    }
                }
                if ((tab == SearchTab.ALL || tab == SearchTab.ALBUMS) && albums.isNotEmpty()) {
                    item {
                        Text("专辑", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    items(albums) { album ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { openAlbum = album }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverImage(album.picURL, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(album.name, fontSize = 15.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${album.singer} · ${album.tracks.size} 首", fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1)
                            }
                        }
                    }
                }
                if ((tab == SearchTab.ALL || tab == SearchTab.PLAYLISTS) && songlists.isNotEmpty()) {
                    item {
                        Text("歌单", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    items(songlists) { info ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenSonglist(info) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverImage(info.picURL, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(info.name, fontSize = 15.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val songlistSub = info.author.takeIf { it.isNotEmpty() }
                                if (songlistSub != null) {
                                    Text(songlistSub, fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
    openAlbum?.let { album ->
        AlbumDetailView(
            album = album,
            playingId = playingId,
            onBack = { openAlbum = null },
            onOpenPlayer = onOpenPlayer,
        )
    }
    openArtist?.let { artist ->
        ArtistDetailView(
            artist = artist,
            playingId = playingId,
            onBack = { openArtist = null },
            onOpenPlayer = onOpenPlayer,
            onOpenAlbum = { info -> openAlbumInfo = info },
        )
    }
    openAlbumInfo?.let { info ->
        AlbumDetailView(
            info = info,
            playingId = playingId,
            onBack = { openAlbumInfo = null },
            onOpenPlayer = onOpenPlayer,
        )
    }
    }
}

/** 专辑详情：点专辑进来，列出这张专辑的歌，点歌直接播。 */
@Composable
fun AlbumDetailView(
    album: AlbumGroup,
    playingId: String?,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    AlbumDetailView(
        info = album.toAlbumInfo(),
        playingId = playingId,
        onBack = onBack,
        onOpenPlayer = onOpenPlayer,
    )
}

/** 搜索结果里的专辑转成详情接口要用的 AlbumInfo（QQ 用 albumMid，酷狗用 albumId）。 */
fun AlbumGroup.toAlbumInfo(): AlbumInfo {
    val first = tracks.firstOrNull()
    val detailKey = when (source) {
        SourceID.TX -> first?.extras?.get("albumMid") ?: first?.albumId ?: name
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
 * 专辑详情（按 AlbumInfo）：点开先拉完整曲目单（QQ / 酷狗走详情接口），拉到了就显示完整的，
 * 拉不到（酷我 / 网易）就显示搜索结果里聚合出来的那几首，不留空白。
 */
@Composable
fun AlbumDetailView(
    info: AlbumInfo,
    playingId: String?,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    androidx.activity.compose.BackHandler { onBack() }
    var fullTracks by remember { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(info) {
        fullTracks = runCatching { appContainer.artists.fetchAlbumDetail(info) }.getOrDefault(emptyList())
    }
    val tracks = fullTracks?.takeIf { it.isNotEmpty() } ?: info.fallbackTracks
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.BgDeep)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = AppColors.TextPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(info.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${info.singer} · ${tracks.size} 首", fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (fullTracks == null) {
                    Text("正在加载完整曲目…", fontSize = 12.sp, color = AppColors.TextMuted)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (tracks.isEmpty()) {
            Text("这张专辑暂时拉不到曲目单", color = AppColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(tracks) { track ->
                    val idx = tracks.indexOf(track)
                    TrackRowMobile(
                        track = track,
                        isPlaying = track.id == playingId,
                        onClick = {
                            playList(tracks, idx)
                            onOpenPlayer()
                        },
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
}

/** 歌手页：头像 + 名字 + 统计，热门歌曲列表，下面是专辑列表，点专辑进专辑详情。 */
@Composable
fun ArtistDetailView(
    artist: ArtistInfo,
    playingId: String?,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenAlbum: (AlbumInfo) -> Unit,
) {
    androidx.activity.compose.BackHandler { onBack() }
    var detail by remember { mutableStateOf<ArtistDetail?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var showAllAlbums by remember { mutableStateOf(false) }
    LaunchedEffect(artist) {
        detail = runCatching { appContainer.artists.fetchArtistDetail(artist) }.getOrNull()
        loaded = true
    }
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.BgDeep)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = AppColors.TextPrimary)
            }
            CoverImage(
                artist.picURL,
                modifier = Modifier.size(64.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(artist.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val stats = listOfNotNull(
                    artist.songCount?.let { "${it} 首" },
                    artist.albumCount?.let { "${it} 张专辑" },
                ).joinToString(" · ")
                if (stats.isNotEmpty()) {
                    Text(stats, fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            !loaded -> {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppColors.BrandPrimary)
                }
            }
            detail == null -> {
                Text("歌手信息拉不到，换个平台试试", color = AppColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
            }
            else -> {
                val d = detail!!
                LazyColumn(Modifier.fillMaxSize()) {
                    if (d.albums.isNotEmpty()) {
                        item {
                            // “查看全部专辑”：点进去是全部专辑网格（分页拉），关掉回这里。
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                                Text("专辑 (${d.artist.albumCount ?: d.albums.size})", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "查看全部 >",
                                    fontSize = 13.sp,
                                    color = AppColors.BrandPrimary,
                                    modifier = Modifier.clickable { showAllAlbums = true },
                                )
                            }
                        }
                        items(d.albums, key = { "${it.source.key}_${it.id}" }) { album ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onOpenAlbum(album) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CoverImage(album.picURL, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(album.name, fontSize = 15.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${album.singer} · ${album.songCount?.let { "$it 首" } ?: ""}", fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1)
                                }
                                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AppColors.TextMuted)
                            }
                        }
                    }
                    if (d.hotSongs.isNotEmpty()) {
                        item {
                            Text("热门歌曲 (${d.hotSongs.size})", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.padding(vertical = 6.dp))
                        }
                        items(d.hotSongs) { track ->
                            val idx = d.hotSongs.indexOf(track)
                            TrackRowMobile(
                                track = track,
                                isPlaying = track.id == playingId,
                                onClick = {
                                    playList(d.hotSongs, idx)
                                    onOpenPlayer()
                                },
                            )
                        }
                    }
                    if (d.hotSongs.isEmpty() && d.albums.isEmpty()) {
                        item {
                            Text("这个歌手暂时没有可播的内容", color = AppColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
                        }
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }
    // “查看全部专辑”浮层：点专辑走手机自己的专辑详情页（onOpenAlbum），关掉回这里。
    if (showAllAlbums) {
        val d = detail
        if (d != null) {
            AllAlbumsOverlay(
                artist = artist,
                initialAlbums = d.albums,
                initialNextPage = d.albumsNextPage,
                initialHasMore = d.albumsHasMore,
                allSongs = d.hotSongs,
                totalCount = d.artist.albumCount,
                onBack = { showAllAlbums = false },
                onOpenPlayer = onOpenPlayer,
                onOpenAlbum = onOpenAlbum,
            )
        }
    }
    }
}

@Composable
private fun PlatformChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 13.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AppColors.BrandPrimary,
            selectedLabelColor = androidx.compose.ui.graphics.Color.White,
        ),
    )
}
