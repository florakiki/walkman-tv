package com.walkman.tv.ui.mobile

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkman.tv.data.model.BoardInfo
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SonglistTag
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.fetchOr
import com.walkman.tv.ui.playList
import com.walkman.tv.ui.recommend.buildGuessYouLike
import com.walkman.tv.ui.theme.AppColors

/**
 * 手机首页（QQ风设计图）：顶部 KITV音乐 + 搜索条、推荐 / 歌单 / 排行榜三个 tab、
 * 推荐歌单两列白卡片、为你推荐（最近播放）。
 * 设计图里的「电台 / 新歌」暂无数据源，先不上 tab，免得点进去是空的。
 */
@Composable
fun MobileHomeScreen(
    onOpenSearch: () -> Unit,
    onOpenMine: () -> Unit,
    onOpenSonglist: (SonglistInfo) -> Unit,
    onOpenBoard: (BoardInfo) -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by appContainer.settingsStore.settings.collectAsState()
    val home by appContainer.homeStore.state.collectAsState()
    val history by appContainer.libraryStore.history.collectAsState()
    // 猜你喜欢：本机播放历史 + 收藏推（见 GuessYouLike.kt），没历史时热门榜兜底。
    // 只读 libraryStore 的 StateFlow，不改它；历史 / 收藏 / 启用的音源一变就重算（电视端同款接法）。
    val loved by appContainer.libraryStore.love.collectAsState()
    var guessTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    // 新区“查看全部”的完整列表（每日30首 / 猜你喜欢共用，曲目是已拉到的，不再联网拉）。
    var openTrackList by remember { mutableStateOf<TrackListDetail?>(null) }
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("推荐", "歌单", "排行榜")
    // 歌手入口：首页“歌手”区只放前 10 个热门歌手头像，点“查看全部”进歌手浏览页。
    var hotSingers by remember { mutableStateOf<List<com.walkman.tv.source.catalog.ArtistInfo>>(emptyList()) }
    var showSingers by remember { mutableStateOf(false) }
    var openArtist by remember { mutableStateOf<com.walkman.tv.source.catalog.ArtistInfo?>(null) }
    var openAlbumInfo by remember { mutableStateOf<com.walkman.tv.source.catalog.AlbumInfo?>(null) }
    val playingId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id

    LaunchedEffect(settings.homeSources) {
        appContainer.homeStore.loadIfNeeded(settings.homeSources)
    }
    LaunchedEffect(history, loved, settings.homeSources) {
        guessTracks = runCatching {
            buildGuessYouLike(appContainer.catalogs, appContainer.boards, settings.homeSources, history.tracks, loved.tracks)
        }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) {
        hotSingers = runCatching { appContainer.artists.hotSingers(com.walkman.tv.source.catalog.SingerRegion.ALL, 10) }.getOrDefault(emptyList())
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // 平板横屏一排只有 2 张、海报占半屏的原因：以前写死 chunked(2)。按实际宽度算列数，
        // 一张卡约 170dp 宽：手机竖屏 2 列，平板横屏 4-6 列，海报不糊也不占半屏。
        val columns = ((maxWidth - 32.dp) / 170.dp).toInt().coerceIn(2, 6)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.BrandPrimary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("K", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Text("KITV音乐", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    Toast.makeText(context, "暂无新通知", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.Notifications, contentDescription = "通知", tint = AppColors.TextPrimary)
                }
                IconButton(onClick = onOpenMine) {
                    Icon(Icons.Filled.AccountCircle, contentDescription = "我的", tint = AppColors.TextSecondary, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            // 搜索条（点一下进搜索页）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .clickable(onClick = onOpenSearch)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = AppColors.BrandPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("搜索歌曲、歌手、歌单", color = AppColors.TextMuted, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            TabRow(
                selectedTabIndex = tab,
                containerColor = Color.Transparent,
                contentColor = AppColors.TextPrimary,
                indicator = { positions ->
                    TabRowDefaults.Indicator(
                        Modifier.tabIndicatorOffset(positions[tab]),
                        color = AppColors.BrandPrimary,
                    )
                },
                divider = {},
            ) {
                tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = {
                            Text(
                                label,
                                fontSize = 15.sp,
                                fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal,
                                color = if (tab == i) AppColors.TextPrimary else AppColors.TextSecondary,
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        when (tab) {
            0 -> {
                // 新区放最前面，跟电视端一致：每日30首 → 猜你喜欢 → 原来的顺序。
                // 空的时候整段不画，不留空白标题。
                if (home.daily30.isNotEmpty()) {
                    item {
                        SectionHeader("每日30首") { openTrackList = TrackListDetail("每日30首", "每天更新", home.daily30) }
                        Spacer(Modifier.height(4.dp))
                    }
                    item {
                        TrackCardRowMobile(home.daily30) { index ->
                            playList(home.daily30, index)
                            onOpenPlayer()
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (guessTracks.isNotEmpty()) {
                    item {
                        SectionHeader("猜你喜欢") { openTrackList = TrackListDetail("猜你喜欢", null, guessTracks) }
                        Spacer(Modifier.height(4.dp))
                    }
                    item {
                        TrackCardRowMobile(guessTracks) { index ->
                            playList(guessTracks, index)
                            onOpenPlayer()
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                item {
                    SectionHeader("推荐歌单") { tab = 1 }
                }
                if (home.isLoading && home.recommendations.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AppColors.BrandPrimary)
                        }
                    }
                } else if (home.recommendations.isEmpty()) {
                    item {
                        Text("推荐歌单拉取失败，下拉不了——点右上角刷新试试，或检查网络。", color = AppColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                    }
                } else {
                    items(home.recommendations.chunked(columns)) { row ->
                        SonglistRow(row, columns, onOpenSonglist)
                        Spacer(Modifier.height(12.dp))
                    }
                }
                // 歌手入口：圆形头像横排预览，点“查看全部”进歌手浏览页（地区 tab + 头像网格）。
                if (hotSingers.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        SectionHeader("歌手") { showSingers = true }
                        Spacer(Modifier.height(4.dp))
                    }
                    item {
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(hotSingers, key = { "${it.source.key}_${it.id}" }) { artist ->
                                Column(
                                    modifier = Modifier.clickable { openArtist = artist },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    CoverImage(
                                        artist.picURL,
                                        modifier = Modifier.size(64.dp).clip(CircleShape),
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        artist.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.width(64.dp),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader("为你推荐", actionLabel = null) {}
                    Spacer(Modifier.height(4.dp))
                    val recent = history.tracks.take(5)
                    if (recent.isEmpty()) {
                        Text("听过的歌会出现在这里", color = AppColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
                items(history.tracks.take(5)) { track ->
                    val playingId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id
                    TrackRowMobile(
                        track = track,
                        isPlaying = track.id == playingId,
                        onClick = {
                            playList(history.tracks, history.tracks.indexOf(track))
                            onOpenPlayer()
                        },
                    )
                }
            }
            1 -> {
                item { SectionHeader("全部歌单", actionLabel = null) {} }
                item { AllSonglistsGrid(columns, onOpenSonglist) }
            }
            2 -> {
                item { SectionHeader("排行榜", actionLabel = null) {} }
                if (home.boards.isEmpty()) {
                    item {
                        Text(
                            if (home.isLoading) "排行榜加载中…" else "排行榜拉取失败，检查网络后重进试试。",
                            color = AppColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                items(home.boards) { board ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenBoard(board) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverImage(board.picURL, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(board.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
        }
        // 歌手浏览页（地区 tab + 头像网格）与歌手页、专辑详情，盖在首页内容上。
        if (showSingers) {
            SingerBrowseView(
                onBack = { showSingers = false },
                onOpenArtist = { openArtist = it },
            )
        }
        openArtist?.let { artist ->
            ArtistDetailView(
                artist = artist,
                playingId = playingId,
                onBack = { openArtist = null },
                onOpenPlayer = onOpenPlayer,
                onOpenAlbum = { openAlbumInfo = it },
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
        // 新区“查看全部”的完整列表，盖在首页内容上（跟歌手浏览页 / 歌手页同一层）。
        openTrackList?.let { detail ->
            TrackListDetailView(
                detail = detail,
                playingId = playingId,
                onBack = { openTrackList = null },
                onOpenPlayer = onOpenPlayer,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, actionLabel: String? = "查看全部 >", onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
        Spacer(Modifier.weight(1f))
        if (actionLabel != null) {
            Text(
                actionLabel,
                fontSize = 13.sp,
                color = AppColors.BrandPrimary,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

/** 推荐歌单白卡片：封面 + 绿播放键 + 名字 + 作者/首数。 */
@Composable
private fun SonglistCard(info: SonglistInfo, modifier: Modifier = Modifier, onOpen: (SonglistInfo) -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .clickable { onOpen(info) }
            .padding(10.dp),
    ) {
        Box {
            CoverImage(
                info.picURL,
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AppColors.BrandPrimary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(info.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        val sub = listOfNotNull(
            info.author.takeIf { it.isNotEmpty() },
            info.trackCount?.let { "$it 首" },
            info.playCount?.let { "$it 播放" },
        ).joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(sub, fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 歌单卡片行：按列数排，一行没排满用空白补齐，卡片等宽。 */
@Composable
private fun SonglistRow(cards: List<SonglistInfo>, columns: Int, onOpen: (SonglistInfo) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        for (slot in 0 until columns) {
            if (slot > 0) Spacer(Modifier.width(12.dp))
            if (slot < cards.size) {
                SonglistCard(cards[slot], Modifier.weight(1f), onOpen)
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 歌单 tab：每平台取推荐歌单前 8 个拼成网格。 */
@Composable
private fun AllSonglistsGrid(columns: Int, onOpen: (SonglistInfo) -> Unit) {
    val settings by appContainer.settingsStore.settings.collectAsState()
    var lists by remember { mutableStateOf<List<SonglistInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(settings.homeSources) {
        loading = true
        lists = fetchOr(emptyList()) {
            val out = mutableListOf<SonglistInfo>()
            for (src in SourceID.homePageOrder.filter { it in settings.homeSources }) {
                val svc = appContainer.songlists.serviceFor(src) ?: continue
                val order = svc.orders.firstOrNull() ?: continue
                val got = runCatching { svc.fetchRecommended(order, SonglistTag.ALL, 1) }.getOrDefault(emptyList())
                out += got.take(8)
            }
            out
        }
        loading = false
    }
    if (loading && lists.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AppColors.BrandPrimary)
        }
        return
    }
    if (lists.isEmpty()) {
        Text("歌单拉取失败，检查网络后重进试试。", color = AppColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    Column {
        lists.chunked(columns).forEach { row ->
            SonglistRow(row, columns, onOpen)
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 新区完整列表的入参：标题 + 副标题 + 已拉到的曲目（不再联网拉）。 */
private data class TrackListDetail(val title: String, val subtitle: String?, val tracks: List<Track>)

/**
 * 新区（每日30首 / 猜你喜欢）的横排歌曲卡片行：前 12 首，点卡片直接播整段队列。
 * 手机触屏版，不带电视那套焦点逻辑；只显示歌名 + 歌手，不显示平台来源。
 */
@Composable
private fun TrackCardRowMobile(
    tracks: List<Track>,
    onPlay: (Int) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        itemsIndexed(tracks.take(12), key = { _, it -> it.id }) { index, track ->
            Column(
                modifier = Modifier
                    .width(128.dp)
                    .clickable { onPlay(index) },
            ) {
                CoverImage(
                    track.picURL,
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    track.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (track.singer.isNotBlank()) {
                    Text(
                        track.singer,
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 新区“查看全部”的完整列表：照手机歌单详情（MobileSonglistScreen）的样式——
 * 返回头 + 副标题 / 首数 + 全部播放 + 曲目列表（TrackRowMobile，点一首就播整段队列）。
 * 曲目是调用方已经拉到的，不再联网拉；不显示平台来源。
 */
@Composable
private fun TrackListDetailView(
    detail: TrackListDetail,
    playingId: String?,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.BgDeep),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(detail.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val sub = listOfNotNull(
                    detail.subtitle,
                    "${detail.tracks.size} 首",
                ).joinToString(" · ")
                Text(sub, fontSize = 13.sp, color = AppColors.TextSecondary)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Button(
                onClick = {
                    if (detail.tracks.isNotEmpty()) {
                        playList(detail.tracks, 0)
                        onOpenPlayer()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.BrandPrimary),
                enabled = detail.tracks.isNotEmpty(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("全部播放", fontSize = 14.sp)
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            itemsIndexed(detail.tracks) { i, track ->
                TrackRowMobile(
                    track = track,
                    index = i,
                    showCover = false,
                    isPlaying = track.id == playingId,
                    onClick = {
                        playList(detail.tracks, i)
                        onOpenPlayer()
                    },
                )
            }
        }
    }
}
