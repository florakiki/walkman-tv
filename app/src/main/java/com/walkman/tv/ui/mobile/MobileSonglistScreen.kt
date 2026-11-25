package com.walkman.tv.ui.mobile

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkman.tv.data.model.BoardInfo
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.Track
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.fetchOr
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * 歌单 / 排行榜详情（手机版）：封面 + 名字 + 全部播放 + 存为我的歌单 + 曲目列表。
 * 点一首就播（playList），播起来后由调用方打开播放页。
 */
@Composable
fun MobileSonglistScreen(
    songlist: SonglistInfo?,
    board: BoardInfo?,
    onClose: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val title = songlist?.name ?: board?.name ?: ""
    val cover = songlist?.picURL ?: board?.picURL
    val subtitle = when {
        songlist != null -> listOfNotNull(
            songlist.author.takeIf { it.isNotEmpty() },
            songlist.source.displayName,
            songlist.trackCount?.let { "$it 首" },
        ).joinToString(" · ")
        board != null -> board.source.displayName
        else -> ""
    }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(songlist, board) {
        loading = true
        tracks = fetchOr(emptyList()) {
            when {
                songlist != null ->
                    appContainer.songlists.serviceFor(songlist.source)?.fetchDetail(songlist)?.tracks
                        ?: emptyList()
                board != null ->
                    appContainer.boards.serviceFor(board.source)?.fetchTracks(board.bangid, 1)
                        ?: emptyList()
                else -> emptyList()
            }
        }
        loading = false
    }

    val playingId = appContainer.playbackController.state.collectAsState().value.currentTrack?.id

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("歌单", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(cover, Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, fontSize = 13.sp, color = AppColors.TextSecondary)
                }
                if (tracks.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("${tracks.size} 首", fontSize = 12.sp, color = AppColors.TextMuted)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Button(
                onClick = { if (tracks.isNotEmpty()) onPlay(tracks, 0) },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.BrandPrimary),
                enabled = tracks.isNotEmpty(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("全部播放", fontSize = 14.sp)
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = {
                    if (tracks.isEmpty()) return@OutlinedButton
                    scope.launch {
                        val list = appContainer.libraryStore.createList(title)
                        appContainer.libraryStore.addAllToList(list.id, tracks)
                        status = "已存到「我的列表」：$title（${tracks.size} 首）"
                    }
                },
                enabled = tracks.isNotEmpty(),
            ) {
                Text("存为我的歌单", fontSize = 14.sp, color = AppColors.BrandPrimary)
            }
        }
        status?.let {
            Text(it, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (loading) {
            Column(
                Modifier.fillMaxWidth().padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = AppColors.BrandPrimary)
            }
        } else if (tracks.isEmpty()) {
            Text(
                "暂无曲目",
                fontSize = 14.sp,
                color = AppColors.TextMuted,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                itemsIndexed(tracks) { i, track ->
                    TrackRowMobile(
                        track = track,
                        index = i,
                        showCover = false,
                        isPlaying = track.id == playingId,
                        onClick = { onPlay(tracks, i) },
                    )
                }
            }
        }
    }
}
