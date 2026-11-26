package com.walkman.tv.ui.songlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SonglistTag
import com.walkman.tv.data.model.Track
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.fetchOr
import com.walkman.tv.ui.components.EmptyHint
import com.walkman.tv.ui.components.LoadingState
import com.walkman.tv.ui.components.MediaCard
import com.walkman.tv.ui.components.rememberOverlayFocusRestore
import com.walkman.tv.ui.components.TvPill
import kotlinx.coroutines.launch

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SonglistScreen(onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    // 不显示平台来源：四个平台的歌单合并成一份网格，源的选择在后台（点歌单进详情时按歌单所属平台取）。
    val services = remember { appContainer.songlists.servicesAll() }
    var tags by remember { mutableStateOf<List<SonglistTag>>(listOf(SonglistTag.ALL)) }
    var tag by remember { mutableStateOf(SonglistTag.ALL) }
    var lists by remember { mutableStateOf<List<SonglistInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Pair<SonglistInfo, List<Track>>?>(null) }
    var loadingDetail by remember { mutableStateOf(false) }
    // 进歌单详情之前记住点的那张卡片，出来时焦点回到那张卡片上：浮层关掉时焦点是被
    // 整个清空的，网格虽然还留在组合里、focusRestorer 也不会在这一刻触发，不记就回
    // 不到点下去的位置。
    val restore = rememberOverlayFocusRestore()
    var detailEntryKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(detail) { if (detail == null) restore.restoreTo(detailEntryKey) }

    // 四个平台的推荐歌单合并：每个平台取自己的第一种排序 + 当前标签，拼起来去重（按歌单 id）。
    suspend fun fetchAllLists(t: SonglistTag): List<SonglistInfo> {
        val all = mutableListOf<SonglistInfo>()
        for (svc in services) {
            val o = svc.orders?.firstOrNull() ?: continue
            all += fetchOr(emptyList()) { svc.fetchRecommended(o, t, 1) }
        }
        return all.distinctBy { it.id }
    }

    LaunchedEffect(Unit) {
        val allTags = mutableListOf<SonglistTag>()
        for (svc in services) {
            val groups = fetchOr(emptyList()) { svc.fetchTags() }
            allTags += groups.flatMap { it.tags }
        }
        tags = listOf(SonglistTag.ALL) + allTags.distinctBy { it.id }
    }
    LaunchedEffect(tag) {
        loading = true
        lists = fetchAllLists(tag)
        loading = false
    }

    // IMPORTANT: keep the grid UI in the composition even when detail is open. If we early-return
    // here, the LaunchedEffect(tag) above leaves the composition; on the way back
    // (detail=null) it re-fires as a fresh effect and re-fetches lists, blowing away the user's
    // grid scroll position and the previously-focused card. Rendering detail as an overlay keeps
    // the grid alive (state, focus, fetched lists), so back-navigation is instant and preserves
    // the selection.
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(tags) { t ->
                    TvPill(onClick = { tag = t }, selected = tag.id == t.id && tag.name == t.name) { Text(t.name, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.padding(top = 6.dp))
            val uniqueLists = remember(lists) { lists.distinctBy { it.id } }
            when {
                loading -> LoadingState(Modifier.fillMaxSize())
                lists.isNotEmpty() -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .focusRestorer(), // restore the previously-focused card on back-nav
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uniqueLists, key = { it.id }) { sl ->
                        val cardKey = "${sl.source.key}_${sl.id}"
                        MediaCard(
                            title = sl.name,
                            picURL = sl.picURL,
                            subtitle = sl.playCount?.let { "▶ $it" },
                            modifier = restore.modifierFor(cardKey),
                        ) {
                            detailEntryKey = cardKey
                            scope.launch {
                                loadingDetail = true
                                val d = fetchOr(null) { appContainer.songlists.serviceFor(sl.source)?.fetchDetail(sl) }
                                detail = sl to (d?.tracks ?: emptyList())
                                loadingDetail = false
                            }
                        }
                    }
                }
                else -> EmptyHint("暂无歌单", Modifier.fillMaxSize())
            }
        }

        // Detail overlay — visually replaces the grid but the grid remains in composition,
        // preserving scroll position, fetched lists, and focus.
        if (detail != null) {
            val (info, tracks) = detail!!
            com.walkman.tv.ui.components.TracksDetailOverlay(
                title = info.name,
                subtitle = info.author.takeIf { it.isNotBlank() },
                tracks = tracks,
                onBack = { detail = null },
                onOpenPlayer = onOpenPlayer,
            )
        }
    }
}

