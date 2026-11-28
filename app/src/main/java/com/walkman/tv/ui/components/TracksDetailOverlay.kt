package com.walkman.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.walkman.tv.data.model.Track
import com.walkman.tv.di.OverlayEntry
import com.walkman.tv.ui.LocalTopBarFocus
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.playList
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * Generic "songlist / leaderboard / playlist detail" overlay: title + subtitle + TrackList.
 *
 * Used by SonglistScreen (cards in the grid) and RecommendScreen (cards in the discover-page
 * carousels). Both consumers render their picker UI underneath; this overlay paints over it
 * so the picker keeps its scroll/focus state and back-navigation is instantaneous.
 *
 * - Back-key dismisses (via BackHandler).
 * - TrackList grabs initial focus on first composition (initialFocus = true) — the user comes
 *   here to play, focus belongs in the tracklist, not somewhere uncertain.
 * - Tapping a row plays from that index and triggers [onOpenPlayer] so the full-screen player
 *   takes over.
 */
@Composable
fun TracksDetailOverlay(
    title: String,
    tracks: List<Track>,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    subtitle: String? = null,
) {
    // 浮层入口：左边面板按右 / 顶栏按下时进浮层里的顶上内容（曲目单第 0 行）。
    val entryFocuser = remember { FocusRequester() }
    // 第二十六轮：“全部播放”按钮的焦点请求器——歌单第 0 行按上先落到它上面，
    // 再按上才到顶栏；焦点不出详情页。
    val playAllFocuser = remember { FocusRequester() }
    var focusedRow by remember { mutableStateOf(-1) }
    val songListState = rememberLazyListState()
    // 按上回顶栏的入口（RootScreen 提供，当前是推荐 tab 时才有值）。
    val topBarFocus = LocalTopBarFocus.current
    DisposableEffect(title) {
        val entry = OverlayEntry(
            key = "tracksDetail",
            focuser = entryFocuser,
            isFocused = { focusedRow == 0 },
            retry = {
                var landed = false
                for (attempt in 0 until 12) {
                    if (focusedRow == 0) { landed = true; break }
                    // 第 0 行可能还没组合好（列表滚下去了）：先把列表滚回顶部再试。
                    runCatching { songListState.scrollToItem(0) }
                    runCatching { entryFocuser.requestFocus() }
                    delay(70)
                    if (focusedRow == 0) { landed = true; break }
                }
                landed
            },
        )
        appContainer.events.registerOverlayEntry(entry)
        onDispose { appContainer.events.unregisterOverlayEntry("tracksDetail") }
    }
    BackHandler { onBack() }
    Column(
        // Own focus group: while the detail list is open, focus restoration stays inside
        // it instead of dropping back to the grid card underneath (which is painted over
        // and invisible — the old behaviour that left focus wandering).
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.BgDeep)
            .padding(top = 8.dp)
            .focusGroup(),
    ) {
        // 第二十六轮：标题行 + “全部播放”按钮。点它从第一首开始播整个歌单。
        // 焦点链：歌单第 0 行按上 → 落到这个按钮；按钮上按上 → 回顶栏（推荐 tab
        // 才有）、没有就停住吃键；按钮上按下 → 回第 0 行。焦点不出详情页。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = AppColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, color = AppColors.TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TvPill(
                onClick = { if (tracks.isNotEmpty()) { playList(tracks, 0); onOpenPlayer() } },
                modifier = Modifier
                    .padding(start = 12.dp)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            // 按上 → 回顶栏当前 tab（推荐 tab 才有）；没有顶栏就停住吃键，
                            // 别漏到后面的歌单网格页去。
                            Key.DirectionUp -> {
                                topBarFocus?.invoke()
                                true
                            }
                            // 按下 → 回歌单第 0 行。
                            Key.DirectionDown -> {
                                entryFocuser.requestFocus()
                                true
                            }
                            else -> false
                        }
                    },
                focusRequester = playAllFocuser,
            ) {
                Text("全部播放", color = AppColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        val nowId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id
        TrackList(
            tracks,
            modifier = Modifier.fillMaxWidth().padding(top = 0.dp),
            nowPlayingId = nowId,
            initialFocus = true,
            trapFocus = true,
            onFocusedIndexChange = { focusedRow = it },
            onExitUp = {
                // 第二十六轮：歌单第 0 行按上 → 落到“全部播放”按钮，不再往后面的
                // 歌单网格页漏（原来这里回顶栏，歌单 tab 没有顶栏就 moveFocus(Up)
                // 漏到后面页去了）。按钮上再按上才回顶栏、没有就停住。
                playAllFocuser.requestFocus()
            },
            listState = songListState,
            entryFocuser = entryFocuser,
        ) { idx ->
            playList(tracks, idx); onOpenPlayer()
        }
    }
}
