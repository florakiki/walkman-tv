package com.walkman.tv.ui.mobile

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.model.Track
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.playList
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手机曲库（我的列表）：我喜欢的 / 播放历史 / 自建歌单三个 tab；
 * 顶部是同步歌单、备份导出、恢复歌单的入口。
 * 歌单只存本机 JSON，同步走局域网（MobileSyncScreen），备份走系统文件选择器。
 */
@Composable
fun MobileLibraryScreen(
    onOpenSync: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val love by appContainer.libraryStore.love.collectAsState()
    val history by appContainer.libraryStore.history.collectAsState()
    val userLists by appContainer.libraryStore.userLists.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var detailList by remember { mutableStateOf<Playlist?>(null) }
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(appContainer.libraryStore.exportBackupJson().toByteArray(Charsets.UTF_8))
                    } ?: error("打不开文件")
                }
            }
            status = ok.fold({ "备份已导出" }, { "导出失败：${it.message}" })
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            if (raw.isNullOrEmpty()) {
                status = "读取备份失败"
            } else {
                val r = appContainer.libraryStore.importBackupJson(raw)
                status = r.fold({ "已恢复 $it 个列表" }, { "恢复失败：${it.message}" })
            }
        }
    }

    if (detailList != null) {
        PlaylistDetailScreen(
            playlist = detailList!!,
            onClose = { detailList = null },
            onPlay = { tracks, index ->
                playList(tracks, index)
                onOpenPlayer()
            },
            onDelete = { pendingDelete = detailList },
            modifier = modifier,
        )
    } else {
        Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Text("我的列表", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Spacer(Modifier.height(10.dp))
            Row {
                Button(
                    onClick = onOpenSync,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.BrandPrimary),
                ) {
                    Icon(Icons.Filled.Sync, contentDescription = null, modifier = androidx.compose.ui.Modifier.size(18.dp))
                    Text("同步歌单", fontSize = 14.sp)
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { exportLauncher.launch("kitv-music-backup.json") }) {
                    Text("备份导出", fontSize = 13.sp, color = AppColors.BrandPrimary)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                    Text("恢复歌单", fontSize = 13.sp, color = AppColors.BrandPrimary)
                }
            }
            status?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = AppColors.TextSecondary)
            }
            Spacer(Modifier.height(8.dp))
            val tabs = listOf("我喜欢的", "播放历史", "自建歌单")
            TabRow(
                selectedTabIndex = tab,
                containerColor = Color.Transparent,
                contentColor = AppColors.TextPrimary,
                indicator = { positions ->
                    TabRowDefaults.Indicator(Modifier.tabIndicatorOffset(positions[tab]), color = AppColors.BrandPrimary)
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
                                fontSize = 14.sp,
                                fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal,
                                color = if (tab == i) AppColors.TextPrimary else AppColors.TextSecondary,
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val playingId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id
            when (tab) {
                0 -> TrackListPane(
                    tracks = love.tracks,
                    emptyHint = "还没收藏歌曲，在播放页点 ♥ 收藏",
                    playingId = playingId,
                    onPlay = { tracks, index ->
                        playList(tracks, index)
                        onOpenPlayer()
                    },
                    onFavoriteClick = { track ->
                        scope.launch { appContainer.libraryStore.toggleFavorite(track) }
                    },
                )
                1 -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${history.tracks.size} 首", fontSize = 12.sp, color = AppColors.TextMuted)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { appContainer.libraryStore.clearHistory() } }) {
                            Text("清空", fontSize = 13.sp, color = AppColors.Danger)
                        }
                    }
                    TrackListPane(
                        tracks = history.tracks,
                        emptyHint = "听过的歌会出现在这里",
                        playingId = playingId,
                        onPlay = { tracks, index ->
                            playList(tracks, index)
                            onOpenPlayer()
                        },
                    )
                }
                2 -> {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${userLists.size} 个歌单", fontSize = 12.sp, color = AppColors.TextMuted)
                            Spacer(Modifier.weight(1f))
                            OutlinedButton(onClick = { showCreate = true }) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("新建歌单", fontSize = 13.sp, color = AppColors.BrandPrimary)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(Modifier.fillMaxSize()) {
                            if (userLists.isEmpty()) {
                                item {
                                    Text("还没有自建歌单，点右上角新建一个", color = AppColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
                                }
                            }
                            items(userLists) { list ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { detailList = list }.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(list.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${list.tracks.size} 首", fontSize = 12.sp, color = AppColors.TextSecondary)
                                    }
                                    IconButton(onClick = {
                                        if (list.tracks.isNotEmpty()) {
                                            playList(list.tracks, 0)
                                            onOpenPlayer()
                                        }
                                    }) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = AppColors.BrandPrimary)
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(96.dp)) }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateListDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                showCreate = false
                scope.launch {
                    appContainer.libraryStore.createList(name)
                    status = "已新建歌单：$name"
                }
            },
        )
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除歌单") },
            text = { Text("确定删除「${target.name}」吗？里面的歌不会从收藏里删。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    detailList = null
                    scope.launch { appContainer.libraryStore.deleteList(target.id) }
                }) { Text("删除", color = AppColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TrackListPane(
    tracks: List<Track>,
    emptyHint: String,
    playingId: String?,
    onPlay: (List<Track>, Int) -> Unit,
    onFavoriteClick: ((Track) -> Unit)? = null,
) {
    if (tracks.isEmpty()) {
        Text(emptyHint, color = AppColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(tracks) { i, track ->
            TrackRowMobile(
                track = track,
                index = i,
                showCover = true,
                isPlaying = track.id == playingId,
                isFavorite = true,
                onFavoriteClick = onFavoriteClick?.let { { it(track) } },
                onClick = { onPlay(tracks, i) },
            )
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun PlaylistDetailScreen(
    playlist: Playlist,
    onClose: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val lists by appContainer.libraryStore.userLists.collectAsState()
    val live = lists.firstOrNull { it.id == playlist.id } ?: playlist
    val playingId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(live.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除歌单", tint = AppColors.Danger)
            }
        }
        Text("${live.tracks.size} 首", fontSize = 13.sp, color = AppColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { if (live.tracks.isNotEmpty()) onPlay(live.tracks, 0) },
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.BrandPrimary),
            enabled = live.tracks.isNotEmpty(),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("全部播放", fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (live.tracks.isEmpty()) {
            Text("这个歌单还是空的", color = AppColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(live.tracks) { i, track ->
                    TrackRowMobile(
                        track = track,
                        index = i,
                        isPlaying = track.id == playingId,
                        onClick = { onPlay(live.tracks, i) },
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
}

@Composable
private fun CreateListDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建歌单") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 24) name = it },
                placeholder = { Text("例如：开车听的歌") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text("创建", color = AppColors.BrandPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
