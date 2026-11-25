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
import com.walkman.tv.data.model.Track
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.fetchOr
import com.walkman.tv.ui.components.EmptyHint
import com.walkman.tv.ui.components.LoadingState
import com.walkman.tv.ui.components.TrackList
import com.walkman.tv.ui.components.TvFocusable
import com.walkman.tv.ui.playList
import com.walkman.tv.ui.theme.AppColors

@Composable
fun LeaderboardScreen(onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    // 不显示平台来源：四个平台的榜单合并成一份列表，源的选择在后台（按榜拉歌时按榜所属平台取）。
    var boards by remember { mutableStateOf<List<BoardInfo>>(emptyList()) }
    var selected by remember { mutableStateOf<BoardInfo?>(null) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loadingTracks by remember { mutableStateOf(false) }

    // 切榜慢的原因：每次换平台/换榜都重新拉网（榜单列表、榜单歌曲都是现调接口，一次
    // 1-3 秒），没有任何缓存。榜单内容几分钟内不会变，缓存起来：有缓存先直接显示，
    // 后台再悄悄拉一遍新的，内容真变了才更新，回来还是这张榜就不用再转圈。
    val boardsCache = remember { mutableStateOf<List<BoardInfo>?>(null) }
    val tracksCache = remember { mutableStateMapOf<String, List<Track>>() }
    // 上次选中的榜，重进还停在原来那张榜上。
    var lastSelectedId by remember { mutableStateOf<String?>(null) }

    // fetchOr (not runCatching): a cancelled effect must die at the fetch, not swallow the
    // CancellationException and stomp state its restarted replacement already wrote.
    // 四个平台的榜单合并：逐个拉榜单列表，拼起来去重（按榜 id）。
    suspend fun fetchAllBoards(): List<BoardInfo> {
        val all = mutableListOf<BoardInfo>()
        for (svc in appContainer.boards.servicesAll()) {
            all += fetchOr(emptyList()) { svc.fetchBoards() }
        }
        return all.distinctBy { it.id }
    }

    LaunchedEffect(Unit) {
        val cached = boardsCache.value
        if (cached != null) {
            boards = cached
            selected = cached.firstOrNull { it.id == lastSelectedId }
                ?: cached.firstOrNull()
            // 后台刷新：榜单名字/封面可能变，内容真变了才更新。
            val fresh = fetchAllBoards()
            if (fresh.isNotEmpty() && fresh != cached) {
                boardsCache.value = fresh
                boards = fresh
                val selId = selected?.id
                if (selId == null || fresh.none { it.id == selId }) {
                    selected = fresh.firstOrNull { it.id == lastSelectedId }
                        ?: fresh.firstOrNull()
                }
            }
        } else {
            boards = fetchAllBoards()
            if (boards.isNotEmpty()) boardsCache.value = boards
            selected = boards.firstOrNull { it.id == lastSelectedId }
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
            Spacer(Modifier.padding(top = 6.dp))
            val uniqueBoards = remember(boards) { boards.distinctBy { it.id } }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(uniqueBoards, key = { it.id }) { b ->
                    TvFocusable(onClick = { selected = b; lastSelectedId = b.id }, modifier = Modifier.fillMaxWidth()) {
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
