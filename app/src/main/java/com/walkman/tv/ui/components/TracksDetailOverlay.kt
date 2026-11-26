package com.walkman.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.walkman.tv.data.model.Track
import com.walkman.tv.di.OverlayEntry
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
    var focusedRow by remember { mutableStateOf(-1) }
    val songListState = rememberLazyListState()
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
        Spacer(Modifier.height(6.dp))
        val nowId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id
        TrackList(
            tracks,
            modifier = Modifier.fillMaxWidth().padding(top = 0.dp),
            nowPlayingId = nowId,
            initialFocus = true,
            trapFocus = true,
            onFocusedIndexChange = { focusedRow = it },
            listState = songListState,
            entryFocuser = entryFocuser,
        ) { idx ->
            playList(tracks, idx); onOpenPlayer()
        }
    }
}
