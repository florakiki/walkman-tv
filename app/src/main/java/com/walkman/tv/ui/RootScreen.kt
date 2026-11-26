package com.walkman.tv.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.walkman.tv.ui.components.TvPill
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Text
import com.walkman.tv.ui.components.TopNav
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.walkman.tv.ui.leaderboard.LeaderboardScreen
import com.walkman.tv.ui.library.LibraryScreen
import com.walkman.tv.ui.player.PlayerScreen
import com.walkman.tv.ui.recommend.RecommendScreen
import com.walkman.tv.ui.search.SearchScreen
import com.walkman.tv.ui.settings.SettingsScreen
import com.walkman.tv.ui.songlist.SonglistScreen
import com.walkman.tv.ui.theme.AppColors
import com.walkman.tv.playback.update.UpdateState

@Composable
fun RootScreen(modifier: Modifier = Modifier) {
    var section by remember { mutableStateOf(NavSection.Recommend) }
    var showPlayer by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    val recommendFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 回到前台时清掉浮层状态、落回首页（2026-11-26，Yang 问“刚进 app 怎么先显示旧的
    // 页面、然后再显示歌手这页，是不是底层没有清理干净”）。查到的结论：导航 / 浮层
    // 状态只存在内存里（plain remember，没存盘、没有恢复逻辑），真冷启动一定是首页；
    // 歌手页能回来，只可能是按 Home 离开、进程还活着，系统把原任务顶回来——浮层
    // 还在内存里，不是什么没清理干净，是系统本来就会恢复到离开时的页面。
    // 按 Yang 的意思改：进 App 直接是首页，不留上次的浮层。只在“从后台回来”时清，
    // 第一次启动不触发；退出框点确定是杀进程，冷启动本来就是首页。播放不受影响
    // （播放器在 AppContainer 里，不跟着界面走）。
    val activity = LocalContext.current as? ComponentActivity
    var resetKey by remember { mutableIntStateOf(0) }
    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose {}
        var wentBackground = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> wentBackground = true
                Lifecycle.Event.ON_START -> if (wentBackground) {
                    wentBackground = false
                    section = NavSection.Recommend
                    showPlayer = false
                    showExitDialog = false
                    resetKey++
                }
                else -> {}
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    // Subscribe only to the bits the TopNav cares about — without distinctUntilChanged the 1Hz
    // position ticker would recompose the whole root and TopNav once per second.
    data class NavPlaybackBits(val title: String?, val picURL: String?, val isPlaying: Boolean)
    val navBits by remember {
        appContainer.playbackController.state
            .map { NavPlaybackBits(it.currentTrack?.name, it.currentTrack?.picURL, it.isPlaying) }
            .distinctUntilChanged()
    }.collectAsState(initial = NavPlaybackBits(null, null, false))

    LaunchedEffect(Unit) { runCatching { recommendFocus.requestFocus() } }

    // In-app update: the moment a background download finishes — on ANY tab — hand the APK to
    // the system installer. Lives at the root so it fires regardless of which screen shows.
    // Map to just the downloaded File + distinctUntilChanged so the per-tick Downloading progress
    // updates don't recompose the whole root (same guard as navBits above). Keyed install runs
    // once per file.
    val downloadedApk by remember {
        appContainer.updateManager.state
            .map { (it as? UpdateState.Downloaded)?.file }
            .distinctUntilChanged()
    }.collectAsState(initial = null)
    LaunchedEffect(downloadedApk) {
        downloadedApk?.let { appContainer.updateManager.install(context, it) }
    }

    // Top-level back handler: non-Recommend sections jump back to Recommend; Recommend asks to exit.
    // Inner overlays (PlayerScreen / songlist-detail) register their own BackHandlers which take
    // precedence over this one.
    BackHandler(enabled = !showPlayer && !showExitDialog) {
        if (section != NavSection.Recommend) {
            section = NavSection.Recommend
        } else {
            showExitDialog = true
        }
    }

    Box(modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏按下：浮层开着时（歌手页等）落到这一页最上面那一行（歌手页第一张专辑卡片），
            // 不是 focusRestorer 记住的最底下那一行（2026-11-26：歌手页往上到顶栏、
            // 再按下来落在热门歌曲列表最底下一行——空间搜索从顶栏下来先挑到满宽的歌单行，
            // focusRestorer 再把它送回上次那行）。没浮层时走原来的空间搜索
            // （首页网格顶行就在顶栏下面，落点本来就是对的）。
            Box(
                Modifier.onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (e.key != Key.DirectionDown) return@onPreviewKeyEvent false
                    val entry = appContainer.events.topOverlayEntry ?: return@onPreviewKeyEvent false
                    runCatching { entry.focuser.requestFocus() }
                    if (entry.isFocused()) return@onPreviewKeyEvent true
                    scope.launch { entry.retry?.invoke() }
                    true
                },
            ) {
                TopNav(
                    active = section,
                    onSelect = { section = it },
                    recommendFocusRequester = recommendFocus,
                    nowPlayingTitle = navBits.title,
                    nowPlayingPicURL = navBits.picURL,
                    nowPlayingIsPlaying = navBits.isPlaying,
                    onOpenPlayer = { showPlayer = true },
                )
            }
            // weight(1f) — NOT fillMaxSize. fillMaxSize on a Column child requests the column's
            // *total* height (ignoring siblings), so the bottom of this Box was being pushed
            // past the screen by ~TopNav height and content there was clipped. weight gives
            // exactly the remaining space after TopNav, which is what we actually want.
            // key(resetKey)：回到前台时整个内容区重建，浮层 / 滚动位置 / 各页内部状态
            // 全部回到初始值，落回首页（上面那个生命周期观察者会把 resetKey 加一）。
            key(resetKey) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp)) {
                    val openPlayer = { showPlayer = true }
                    when (section) {
                        NavSection.Recommend -> RecommendScreen(onNavigate = { section = it }, onOpenPlayer = openPlayer)
                        NavSection.Search -> SearchScreen(onOpenPlayer = openPlayer)
                        NavSection.Leaderboard -> LeaderboardScreen(onOpenPlayer = openPlayer)
                        NavSection.Songlist -> SonglistScreen(onOpenPlayer = openPlayer)
                        NavSection.Library -> LibraryScreen(onOpenPlayer = openPlayer)
                        NavSection.Settings -> SettingsScreen()
                    }
                }
            }
        }

        if (showPlayer) {
            PlayerScreen(
                onClose = { showPlayer = false },
                modifier = Modifier.fillMaxSize().background(AppColors.BgDeep),
            )
        }
        // Broadcast 'player overlay closed' so the underlying TrackList can restore focus
        // on the row that opened it. DisposableEffect runs onDispose when PlayerScreen leaves
        // the composition (showPlayer flipping false), which is exactly the moment we want.
        if (showPlayer) {
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose { appContainer.events.postPlayerClosed() }
            }
        }

        if (showExitDialog) {
            ExitConfirmDialog(
                onCancel = { showExitDialog = false },
                onConfirm = {
                    // Cleanly shut down before the process dies, otherwise ExoPlayer keeps
                    // the audio focus / surfaces and we hear playback after Activity.finish.
                    // Snapshot the playback state to disk *before* killing the process so the
                    // next launch can pick up where we left off.
                    runCatching { appContainer.saveSnapshotNow() }
                    runCatching { appContainer.playbackController.stop() }
                    runCatching { appContainer.playbackController.release() }
                    (context as? Activity)?.finishAndRemoveTask()
                    android.os.Process.killProcess(android.os.Process.myPid())
                },
            )
        }
    }
}

@Composable
private fun ExitConfirmDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    // Initial focus goes to '取消' so an accidental OK doesn't quit the app.
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.BgPanel)
                .padding(horizontal = 24.dp, vertical = 22.dp),
            // Title + subtitle left-aligned (the default Start) so they sit flush with the
            // dialog padding edge. Pairing centred text with right-aligned actions felt
            // unbalanced; this matches the standard "body left, actions right" dialog pattern.
        ) {
            Text(
                text = "退出应用",
                color = AppColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "确定要退出KITV音乐吗？",
                color = AppColors.TextSecondary,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(22.dp))
            // Both buttons right-aligned, 取消 immediately to the left of 确定 with a small gap.
            // Standard TV dialog footer pattern: actions hug the trailing edge so D-pad Right
            // from anywhere in the dialog body lands on the action row predictably.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TvPill(
                    onClick = onCancel,
                    focusRequester = cancelFocus,
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp),
                ) {
                    Text("取消", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                TvPill(
                    onClick = onConfirm,
                    selected = true,
                    accent = AppColors.Danger,  // 红色基调 — 让破坏性动作从「OK 绿」里拽出来
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp),
                ) {
                    Text("确定", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
