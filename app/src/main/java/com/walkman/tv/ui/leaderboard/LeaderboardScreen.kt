package com.walkman.tv.ui.leaderboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.walkman.tv.data.model.BoardInfo
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.fetchOr
import com.walkman.tv.ui.components.EmptyHint
import com.walkman.tv.ui.components.LoadingState
import com.walkman.tv.ui.components.TrackList
import com.walkman.tv.ui.components.TvFocusable
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.playList
import com.walkman.tv.ui.theme.AppColors

private val boardSources = listOf(SourceID.KW, SourceID.WY, SourceID.KG, SourceID.TX)

@Composable
fun LeaderboardScreen(onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    var source by remember { mutableStateOf(SourceID.KW) }
    var boards by remember { mutableStateOf<List<BoardInfo>>(emptyList()) }
    var selected by remember { mutableStateOf<BoardInfo?>(null) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loadingTracks by remember { mutableStateOf(false) }

    // 切榜慢的原因：每次换平台/换榜都重新拉网（榜单列表、榜单歌曲都是现调接口，一次
    // 1-3 秒），没有任何缓存。榜单内容几分钟内不会变，缓存起来：有缓存先直接显示，
    // 后台再悄悄拉一遍新的，内容真变了才更新，回来还是这张榜就不用再转圈。
    val boardsCache = remember { mutableStateMapOf<SourceID, List<BoardInfo>>() }
    val tracksCache = remember { mutableStateMapOf<String, List<Track>>() }
    // 每个平台上次选中的榜，切换平台再回来还停在原来那张榜上。
    val lastSelectedBySource = remember { mutableStateMapOf<SourceID, String>() }

    // fetchOr (not runCatching): a cancelled effect must die at the fetch, not swallow the
    // CancellationException and stomp state its restarted replacement already wrote.
    LaunchedEffect(source) {
        val cached = boardsCache[source]
        if (cached != null) {
            boards = cached
            selected = cached.firstOrNull { it.id == lastSelectedBySource[source] }
                ?: cached.firstOrNull()
            // 后台刷新：榜单名字/封面可能变，回来还是这个平台、内容真变了才更新。
            val fresh = fetchOr(emptyList()) { appContainer.boards.serviceFor(source)?.fetchBoards() ?: emptyList() }
            if (fresh.isNotEmpty() && fresh != cached) {
                boardsCache[source] = fresh
                boards = fresh
                val selId = selected?.id
                if (selId == null || fresh.none { it.id == selId }) {
                    selected = fresh.firstOrNull { it.id == lastSelectedBySource[source] }
                        ?: fresh.firstOrNull()
                }
            }
        } else {
            boards = fetchOr(emptyList()) { appContainer.boards.serviceFor(source)?.fetchBoards() ?: emptyList() }
            if (boards.isNotEmpty()) boardsCache[source] = boards
            selected = boards.firstOrNull { it.id == lastSelectedBySource[source] }
                ?: boards.firstOrNull()
        }
    }
    LaunchedEffect(selected) {
        val b = selected ?: return@LaunchedEffect
        val key = "${b.source.key}_${b.id}"
        val cached = tracksCache[key]
        if (cached != null) {
            tracks = cached
            loadingTracks = false
            // 后台刷新榜单歌曲，回来还是这张榜、内容真变了才更新。
            val fresh = fetchOr(emptyList()) { appContainer.boards.serviceFor(b.source)?.fetchTracks(b.bangid, 1) ?: emptyList() }
            if (fresh.isNotEmpty() && fresh != cached && selected?.id == b.id) {
                tracksCache[key] = fresh
                tracks = fresh
            }
        } else {
            loadingTracks = true
            tracks = fetchOr(emptyList()) { appContainer.boards.serviceFor(b.source)?.fetchTracks(b.bangid, 1) ?: emptyList() }
            loadingTracks = false
            if (tracks.isNotEmpty()) tracksCache[key] = tracks
        }
    }

    Row(modifier = modifier.fillMaxSize().padding(top = 8.dp)) {
        Column(modifier = Modifier.width(150.dp).fillMaxHeight()) {
            // Wrap to 2x2 — 4 platform chips can't fit horizontally in a 150dp column
            // (QQ音乐 alone is ~52dp; the row total ~175dp would clip the last chip).
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                boardSources.chunked(2).forEach { rowChips ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowChips.forEach { s ->
                            TvPill(
                                onClick = { source = s },
                                selected = source == s,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            ) { Text(s.displayName, fontSize = 11.sp) }
                        }
                    }
                }
            }
            Spacer(Modifier.padding(top = 6.dp))
            val uniqueBoards = remember(boards) { boards.distinctBy { it.id } }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(uniqueBoards, key = { it.id }) { b ->
                    TvFocusable(onClick = { selected = b; lastSelectedBySource[b.source] = b.id }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            b.name,
                            color = if (selected?.id == b.id) AppColors.AccentGreen else AppColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text(selected?.name ?: "排行榜", color = AppColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            when {
                loadingTracks -> LoadingState(Modifier.fillMaxSize())
                tracks.isNotEmpty() -> {
                    val nowId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id
                    TrackList(tracks, modifier = Modifier.fillMaxWidth().weight(1f), nowPlayingId = nowId) { idx ->
                        playList(tracks, idx); onOpenPlayer()
                    }
                }
                else -> EmptyHint("暂无内容", Modifier.fillMaxSize())
            }
        }
    }
}
