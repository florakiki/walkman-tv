@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.walkman.tv.ui.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.playList
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 手机搜索：输入框 + 平台筛选 + 歌曲/歌单结果；没搜时显示搜索历史和热搜词。
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
    var filter by remember { mutableStateOf<SourceID?>(null) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var songlists by remember { mutableStateOf<List<SonglistInfo>>(emptyList()) }
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
            val pair = runCatching {
                coroutineScope {
                    val tracksJob = async {
                        if (filter == null) appContainer.catalogs.searchAll(q)
                        else appContainer.catalogs.search(filter!!, q)
                    }
                    val listsJob = async {
                        if (filter == null) appContainer.songlists.searchAll(q)
                        else appContainer.songlists.serviceFor(filter!!)?.search(q, 1) ?: emptyList()
                    }
                    tracksJob.await() to listsJob.await()
                }
            }.getOrDefault(emptyList<Track>() to emptyList<SonglistInfo>())
            tracks = pair.first
            songlists = pair.second
            loading = false
        }
    }

    LaunchedEffect(filter) {
        if (searched) runSearch()
    }

    val playingId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id

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
        Row {
            PlatformChip("全部", filter == null) { filter = null }
            Spacer(Modifier.width(8.dp))
            listOf(SourceID.KW, SourceID.KG, SourceID.TX, SourceID.WY).forEach { src ->
                PlatformChip(src.displayName, filter == src) { filter = src }
                Spacer(Modifier.width(8.dp))
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
                hotColumns.forEach { col ->
                    item {
                        Text("${col.source.displayName}热搜", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.padding(vertical = 6.dp))
                        FlowRow {
                            col.words.take(10).forEach { w ->
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
                if (tracks.isEmpty() && songlists.isEmpty()) {
                    item {
                        Text("没搜到相关结果，换个关键词试试。", color = AppColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
                    }
                }
                if (tracks.isNotEmpty()) {
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
                if (songlists.isNotEmpty()) {
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
                                Text(listOfNotNull(info.author.takeIf { it.isNotEmpty() }, info.source.displayName).joinToString(" · "), fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
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
