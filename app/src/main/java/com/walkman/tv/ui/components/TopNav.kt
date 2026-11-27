package com.walkman.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.walkman.tv.ui.NavSection
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.theme.AppColors

/**
 * Top navigation bar — brand on left, six nav items centered (4 sections + search + settings),
 * glowing now-playing capsule on right. Active item shows green text (no pill); focus state
 * shows a green pill background and slight scale (D-pad highlight).
 * 顶栏是独立焦点区（focusGroup）：内容区按上进顶栏、顶栏内左右走、按下回内容，
 * 下面内容网格的焦点不再往左漏（左边正在播面板已撤掉，2026-11-27）。
 */
@Composable
fun TopNav(
    active: NavSection,
    onSelect: (NavSection) -> Unit,
    modifier: Modifier = Modifier,
    recommendFocusRequester: FocusRequester? = null,
    nowPlayingTitle: String? = null,
    nowPlayingIsPlaying: Boolean = false,
    onOpenPlayer: () -> Unit = {},
) {
    // 顶栏根容器：BoxWithConstraints 量一下可用宽度，给胶囊一个上限——
    // 胶囊宽度不能超过 (可用宽 − 中间 6 个 tab 宽约 430.dp)/2，否则窄屏上会盖住 tab
    // （第二十二轮的 bug：胶囊被进度条撑满整行，logo 和 tab 全被盖在下面）。
    BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp).focusGroup()) {
        val capsuleMaxW = ((maxWidth - 430.dp) / 2 - 8.dp).coerceAtLeast(190.dp)
        // Left: brand
        Brand(modifier = Modifier.align(Alignment.CenterStart))

        // Center: 6 nav items
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem("推荐", active == NavSection.Recommend, focusRequester = recommendFocusRequester) {
                onSelect(NavSection.Recommend)
            }
            NavItem("排行榜", active == NavSection.Leaderboard) { onSelect(NavSection.Leaderboard) }
            NavItem("歌单", active == NavSection.Songlist) { onSelect(NavSection.Songlist) }
            NavItem("我的列表", active == NavSection.Library) { onSelect(NavSection.Library) }
            NavItem("搜索", active == NavSection.Search, icon = Icons.Filled.Search) { onSelect(NavSection.Search) }
            NavItemIconOnly(Icons.Filled.Settings, active == NavSection.Settings, contentDescription = "设置") {
                onSelect(NavSection.Settings)
            }
        }

        // Right: glowing now-playing capsule (only when a track is loaded)
        if (!nowPlayingTitle.isNullOrEmpty()) {
            GlowCapsule(
                title = nowPlayingTitle,
                isPlaying = nowPlayingIsPlaying,
                onClick = onOpenPlayer,
                modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = capsuleMaxW),
            )
        }
    }
}

@Composable
private fun Brand(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.MusicNote,
            contentDescription = null,
            tint = AppColors.AccentGreen,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "KITV音乐",
            color = AppColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = AppColors.AccentGreen,
            pressedContainerColor = AppColors.AccentGreen,
            contentColor = if (selected) AppColors.AccentGreen else AppColors.TextPrimary,
            focusedContentColor = AppColors.BgDeep,
            pressedContentColor = AppColors.BgDeep,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, AppColors.AccentGreen), shape = shape),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                // 选中 tab 的下划线：直接画在文字下面、跟文字同宽、居中对齐，
                // 不占布局高度（drawBehind 不影响测量），选/不选切换时 tab 高度不变、线不跳位。
                // 聚焦时整颗变绿底白字，下划线被绿底盖住不显，正好不抢焦点态。
                modifier = if (selected) Modifier.drawBehind {
                    val strokeWidth = 2.5.dp.toPx()
                    val y = size.height + 3.dp.toPx()
                    drawLine(
                        color = AppColors.AccentGreen,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                } else Modifier,
            )
        }
    }
}

@Composable
private fun NavItemIconOnly(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = AppColors.AccentGreen,
            pressedContainerColor = AppColors.AccentGreen,
            contentColor = if (selected) AppColors.AccentGreen else AppColors.TextPrimary,
            focusedContentColor = AppColors.BgDeep,
            pressedContentColor = AppColors.BgDeep,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(0.dp, Color.Transparent), shape = CircleShape),
            focusedBorder = Border(BorderStroke(2.dp, AppColors.AccentGreen), shape = CircleShape),
        ),
    ) {
        Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * 顶栏右上角发光胶囊播放器（2026-11-27，Yang 定第 4 个设计）：
 * 浅绿胶囊 + 绿色光晕边框，跳动绿点表示正在播，歌名 + 进度条 + 时间；点它进播放页。
 * 没歌时不显示。进度 / 时间直接读播放器状态（只让胶囊自己每秒重组，不牵连整个顶栏）。
 */
@Composable
private fun GlowCapsule(
    title: String,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by appContainer.playbackController.state.collectAsState()
    val positionMs = state.positionMs
    val durationMs = state.durationMs
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    // 跳动绿点：播着时透明度来回呼吸（只在播着时跑动画），暂停时定格半亮。
    val dotAlpha = remember { Animatable(0.5f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                dotAlpha.animateTo(1f, animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing))
                dotAlpha.animateTo(0.45f, animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing))
            }
        } else {
            dotAlpha.snapTo(0.5f)
        }
    }
    var focused by remember { mutableStateOf(false) }
    val capsuleShape = RoundedCornerShape(50)
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = capsuleShape,
                ambientColor = AppColors.AccentGreen.copy(alpha = 0.45f),
                spotColor = AppColors.AccentGreen.copy(alpha = 0.45f),
            )
            .onFocusChanged { focused = it.isFocused },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = capsuleShape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFFE2F3E5),
            focusedContainerColor = AppColors.AccentGreen,
            pressedContainerColor = AppColors.AccentGreen,
            contentColor = AppColors.TextPrimary,
            focusedContentColor = AppColors.BgDeep,
            pressedContentColor = AppColors.BgDeep,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(BorderStroke(1.5.dp, AppColors.AccentGreen), shape = capsuleShape),
            focusedBorder = androidx.tv.material3.Border(BorderStroke(2.dp, AppColors.AccentGreen), shape = capsuleShape),
        ),
    ) {
        // 宽度取标题行内在宽度（IntrinsicSize.Max）：进度条 fillMaxWidth 只撑到标题行宽，
        // 胶囊保持紧凑小件；不加这句时 fillMaxWidth 的进度条会把胶囊撑满整个顶栏宽度，
        // 盖住 logo 和 tab（第二十二轮把进度条从定宽 230.dp 改成 fillMaxWidth 后出的回归）。
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp).width(IntrinsicSize.Max)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 跳动绿点：外圈光晕 + 实心点。
                Box(
                    modifier = Modifier.size(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(AppColors.AccentGreen.copy(alpha = dotAlpha.value * 0.35f)),
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(AppColors.AccentGreen.copy(alpha = dotAlpha.value)),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 170.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${fmtMillis(positionMs)} / ${fmtMillis(durationMs)}",
                    fontSize = 11.sp,
                    color = if (focused) AppColors.BgDeep.copy(alpha = 0.85f) else AppColors.TextSecondary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(5.dp))
            // 进度条：宽度跟上面标题行走满（fillMaxWidth），两端正好接上标题行左右边，
            // 不定宽、不超不短，线不断、不错位。聚焦时白底白条，不聚焦时淡绿底绿条。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (focused) Color.White.copy(alpha = 0.35f) else AppColors.AccentGreen.copy(alpha = 0.18f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(if (focused) Color.White else AppColors.AccentGreen),
                )
            }
        }
    }
}

private fun fmtMillis(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}
