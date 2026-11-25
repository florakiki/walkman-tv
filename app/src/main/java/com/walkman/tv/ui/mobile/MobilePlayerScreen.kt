package com.walkman.tv.ui.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.walkman.tv.playback.LyricParser
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * 手机播放页（QQ风设计图）：大圆黑胶封面（播的时候转）、歌名/歌手、歌词（当前行深绿）、
 * 绿色进度条、随机/上一首/播放/下一首/队列，底部收藏。报错无条件显示（防闪退加固那套）。
 */
@Composable
fun MobilePlayerScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = appContainer.playbackController
    val state by controller.state.collectAsState()
    val lyrics by controller.lyrics.collectAsState()
    val settings by appContainer.settingsStore.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var showQueue by remember { mutableStateOf(false) }
    var sliderFraction by remember { mutableStateOf<Float?>(null) }
    // 点一下封面/歌词区隐藏底部控制栏（进度条 + 控制键 + 收藏行），再点一下显示回来。
    // 底部这一栏本来就没有实底面板，是透明底，不用改颜色。
    var controlsVisible by remember { mutableStateOf(true) }

    val track = state.currentTrack
    val isFavorite = track?.let { appContainer.libraryStore.isFavorite(it.id) } ?: false
    val activeLyric = if (lyrics.isEmpty()) -1 else LyricParser.activeIndex(state.positionMs / 1000.0, lyrics)
    val lyricListState = rememberLazyListState()

    // 黑胶转动：播的时候转，暂停马上停。
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) return@LaunchedEffect
        while (true) {
            rotation.animateTo(rotation.value + 360f, animationSpec = tween(20000, easing = LinearEasing))
        }
    }
    // 歌词跟随当前行。
    LaunchedEffect(activeLyric) {
        if (activeLyric >= 0) {
            runCatching { lyricListState.animateScrollToItem((activeLyric - 2).coerceAtLeast(0)) }
        }
    }

    Column(
        modifier.fillMaxSize().background(AppColors.BgDeep).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("KITV音乐 · 正在播放", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showQueue = true }) {
                Icon(Icons.Filled.QueueMusic, contentDescription = "播放队列", tint = AppColors.TextSecondary)
            }
        }

        Spacer(Modifier.height(12.dp))
        // 大圆黑胶：黑底 + 纹路圈 + 中间封面，播的时候转。点一下切换底部控制栏显示/隐藏。
        Box(
            modifier = Modifier
                .size(280.dp)
                .graphicsLayer { rotationZ = rotation.value }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { controlsVisible = !controlsVisible },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color(0xFF141414)),
            )
            Box(
                modifier = Modifier
                    .size(248.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(152.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
            )
            CoverImage(
                track?.picURL,
                Modifier.size(124.dp).clip(CircleShape),
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            track?.name ?: "还没放歌",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            track?.singer ?: "去首页点一首歌开始听",
            fontSize = 15.sp,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))
        // 歌词：当前行深绿加粗，其它行灰色；没歌词时给一句提示。点一下切换底部控制栏显示/隐藏。
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controlsVisible = !controlsVisible },
        ) {
            if (lyrics.isEmpty()) {
                if (state.resolving) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AppColors.BrandPrimary, modifier = Modifier.size(28.dp))
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无歌词", color = AppColors.TextMuted, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(state = lyricListState, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    itemsIndexed(lyrics) { i, line ->
                        val active = i == activeLyric
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text(
                                line.text,
                                fontSize = if (active) 19.sp else 15.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) AppColors.LyricActive else AppColors.LyricIdle,
                                textAlign = TextAlign.Center,
                            )
                            if (settings.showLyricTranslation && !line.translation.isNullOrEmpty()) {
                                Text(
                                    line.translation,
                                    fontSize = 12.sp,
                                    color = AppColors.TextMuted,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 进度 + 控制键 + 收藏行：点封面/歌词区可以隐藏，再点显示回来（透明底不变）。
        AnimatedVisibility(visible = controlsVisible) {
            Column {
        // 进度
        val duration = state.durationMs
        val fraction = if (duration > 0) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatMs(state.positionMs), fontSize = 12.sp, color = AppColors.TextSecondary)
            Slider(
                value = sliderFraction ?: fraction,
                onValueChange = { sliderFraction = it },
                onValueChangeFinished = {
                    sliderFraction?.let { controller.seekTo((it * duration).toLong()) }
                    sliderFraction = null
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = AppColors.BrandPrimary,
                    activeTrackColor = AppColors.BrandPrimary,
                    inactiveTrackColor = AppColors.Line,
                ),
            )
            Text(formatMs(duration), fontSize = 12.sp, color = AppColors.TextSecondary)
        }

        // 控制：随机 / 上一首 / 播放 / 下一首 / 队列
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { controller.toggleShuffle() }) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = "随机播放",
                    tint = if (state.shuffle) AppColors.BrandPrimary else AppColors.TextSecondary,
                    modifier = Modifier.size(26.dp),
                )
            }
            IconButton(onClick = { controller.prev() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首", tint = AppColors.TextPrimary, modifier = Modifier.size(36.dp))
            }
            IconButton(
                onClick = { controller.togglePlay() },
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(AppColors.BrandPrimary),
            ) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(38.dp),
                )
            }
            IconButton(onClick = { controller.next() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一首", tint = AppColors.TextPrimary, modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = { showQueue = true }) {
                Icon(Icons.Filled.QueueMusic, contentDescription = "播放队列", tint = AppColors.TextSecondary, modifier = Modifier.size(26.dp))
            }
        }

        // 收藏 + 状态（报错无条件显示，不藏在调试开关后面）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    track?.let {
                        scope.launch { appContainer.libraryStore.toggleFavorite(it) }
                    }
                },
                enabled = track != null,
            ) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "我喜欢",
                    tint = if (isFavorite) AppColors.Danger else AppColors.TextSecondary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text("我喜欢", fontSize = 14.sp, color = AppColors.TextSecondary)
            Spacer(Modifier.weight(1f))
            val statusText = state.error ?: state.warning
            if (state.resolving) {
                Text("正在获取播放地址…", fontSize = 12.sp, color = AppColors.TextMuted)
            } else if (statusText != null) {
                Text(
                    statusText,
                    fontSize = 12.sp,
                    color = if (state.error != null) AppColors.Danger else AppColors.Warning,
                )
            }
        }
            }
        }
    }

    if (showQueue) {
        QueueDialog(
            onDismiss = { showQueue = false },
            onPick = { index ->
                controller.playAt(index)
                showQueue = false
            },
        )
    }
}

@Composable
private fun QueueDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state by appContainer.playbackController.state.collectAsState()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(16.dp),
        ) {
            Text("播放队列（${state.queue.size} 首）", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                itemsIndexed(state.queue) { i, track ->
                    TrackRowMobile(
                        track = track,
                        index = i,
                        showCover = false,
                        isPlaying = i == state.index,
                        onClick = { onPick(i) },
                    )
                }
            }
        }
    }
}
