package com.walkman.tv.ui.mobile

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkman.tv.data.model.BoardInfo
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.Track
import com.walkman.tv.playback.update.UpdateState
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.playList
import com.walkman.tv.ui.theme.AppColors

private enum class MobileTab(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Filled.Home),
    Search("搜索", Icons.Filled.Search),
    Library("曲库", Icons.Filled.LibraryMusic),
    Mine("我的", Icons.Filled.Person),
}

/**
 * 手机版根界面：底部导航（首页 / 搜索 / 曲库 / 我的）+ 迷你播放条；
 * 播放页、歌单详情、同步页盖在上面。平板横屏（宽 ≥600dp）时左边导航栏、
 * 中间内容、右边播放页三栏并排，不只是手机竖屏放大。
 * 播歌 / 音源 / 增益 / 备份 / 接着上次听 / 防闪退全部复用电视版那套核心，只重做了 UI 层。
 */
@Composable
fun MobileRootScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(MobileTab.Home) }
    var showPlayer by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var songlist by remember { mutableStateOf<SonglistInfo?>(null) }
    var board by remember { mutableStateOf<BoardInfo?>(null) }
    val config = LocalConfiguration.current
    val wide = config.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        config.screenWidthDp >= 600

    val openPlayer = { showPlayer = true }
    val playAndOpen: (List<Track>, Int) -> Unit = { tracks, index ->
        playList(tracks, index)
        showPlayer = true
    }

    // 在应用内更新：下载完成后（任何一页）都交给系统安装器，和电视版 RootScreen 一样。
    val downloadedApk by remember {
        appContainer.updateManager.state
    }.collectAsState()
    LaunchedEffect(downloadedApk) {
        (downloadedApk as? UpdateState.Downloaded)?.let {
            appContainer.updateManager.install(context, it.file)
        }
    }

    val closeTop: () -> Boolean = {
        when {
            songlist != null -> { songlist = null; true }
            board != null -> { board = null; true }
            showSync -> { showSync = false; true }
            showPlayer -> { showPlayer = false; true }
            tab != MobileTab.Home -> { tab = MobileTab.Home; true }
            else -> false
        }
    }
    BackHandler(enabled = songlist != null || board != null || showSync || showPlayer || tab != MobileTab.Home) {
        closeTop()
    }

    Box(modifier.fillMaxSize().background(AppColors.BgDeep)) {
        if (wide) {
            // 宽屏以前没有迷你播放条：播放页在右侧栏里，关掉就找不到正在播的歌。
            // 底部常驻一条（播放页开着时不用它，关掉就一直在），点它回播放页。
            Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                NavigationRail(
                    containerColor = Color.White,
                    header = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppColors.BrandPrimary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("K", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(16.dp))
                    },
                ) {
                    MobileTab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label, fontSize = 12.sp) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = AppColors.BrandPrimary,
                                selectedTextColor = AppColors.BrandPrimary,
                                indicatorColor = AppColors.GreenSoft,
                            ),
                        )
                    }
                }
                VerticalDivider()
                Box(Modifier.weight(1f)) {
                    TabContent(tab, { tab = it }, openPlayer, playAndOpen, { songlist = it }, { board = it }, { showSync = true })
                }
                if (showPlayer) {
                    VerticalDivider()
                    Box(Modifier.weight(1f).background(AppColors.BgDeep)) {
                        MobilePlayerScreen(onClose = { showPlayer = false })
                    }
                }
            }
            if (!showPlayer) MiniPlayerBar(onOpenPlayer = openPlayer)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TabContent(tab, { tab = it }, openPlayer, playAndOpen, { songlist = it }, { board = it }, { showSync = true })
                }
                MiniPlayerBar(onOpenPlayer = openPlayer)
                NavigationBar(containerColor = Color.White) {
                    MobileTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label, fontSize = 12.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppColors.BrandPrimary,
                                selectedTextColor = AppColors.BrandPrimary,
                                indicatorColor = AppColors.GreenSoft,
                            ),
                        )
                    }
                }
            }
            if (showPlayer) {
                MobilePlayerScreen(
                    onClose = { showPlayer = false },
                    modifier = Modifier.fillMaxSize().background(AppColors.BgDeep),
                )
            }
        }

        if (showSync) {
            MobileSyncScreen(
                onClose = { showSync = false },
                modifier = Modifier.fillMaxSize().background(AppColors.BgDeep),
            )
        }
        if (songlist != null || board != null) {
            MobileSonglistScreen(
                songlist = songlist,
                board = board,
                onClose = { songlist = null; board = null },
                onPlay = playAndOpen,
                modifier = Modifier.fillMaxSize().background(AppColors.BgDeep),
            )
        }
    }
}

@Composable
private fun TabContent(
    tab: MobileTab,
    onGoTab: (MobileTab) -> Unit,
    onOpenPlayer: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onOpenSonglist: (SonglistInfo) -> Unit,
    onOpenBoard: (BoardInfo) -> Unit,
    onOpenSync: () -> Unit,
) {
    when (tab) {
        MobileTab.Home -> MobileHomeScreen(
            onOpenSearch = { onGoTab(MobileTab.Search) },
            onOpenMine = { onGoTab(MobileTab.Mine) },
            onOpenSonglist = onOpenSonglist,
            onOpenBoard = onOpenBoard,
            onOpenPlayer = onOpenPlayer,
        )
        MobileTab.Search -> MobileSearchScreen(
            onOpenSonglist = onOpenSonglist,
            onOpenPlayer = onOpenPlayer,
        )
        MobileTab.Library -> MobileLibraryScreen(
            onOpenSync = onOpenSync,
            onOpenPlayer = onOpenPlayer,
        )
        MobileTab.Mine -> MobileSettingsScreen()
    }
}

/** 底部迷你播放条：封面 + 歌名/歌手 + 播放暂停 + 下一首；点一下进播放页。没在播就藏起来。 */
@Composable
private fun MiniPlayerBar(onOpenPlayer: () -> Unit) {
    val controller = appContainer.playbackController
    val state by controller.state.collectAsState()
    val track = state.currentTrack ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .clickable(onClick = onOpenPlayer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(track.picURL, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (state.isPlaying) "${track.singer} · 正在播放" else track.singer,
                fontSize = 12.sp,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { controller.togglePlay() }) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "暂停" else "播放",
                tint = AppColors.BrandPrimary,
                modifier = Modifier.size(28.dp),
            )
        }
        IconButton(onClick = { controller.next() }) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = "下一首",
                tint = AppColors.BrandPrimary,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
