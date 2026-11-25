package com.walkman.tv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.walkman.tv.data.model.Track
import com.walkman.tv.ui.theme.AppColors

/** ms → mm:ss。 */
fun formatMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val s = (ms / 1000).toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}

/** 封面图；没图时显示绿色占位。 */
@Composable
fun CoverImage(url: String?, modifier: Modifier = Modifier, contentDescription: String? = null) {
    if (url.isNullOrEmpty()) {
        Box(
            modifier = modifier.background(AppColors.GreenSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription, tint = AppColors.BrandPrimary)
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

/** 一行歌曲：序号/封面 + 歌名/歌手 + 时长，可选收藏按钮。 */
@Composable
fun TrackRowMobile(
    track: Track,
    index: Int? = null,
    isPlaying: Boolean = false,
    showCover: Boolean = true,
    isFavorite: Boolean = false,
    onFavoriteClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCover) {
            CoverImage(
                track.picURL,
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else if (index != null) {
            Text(
                text = "%02d".format(index + 1),
                color = if (isPlaying) AppColors.BrandPrimary else AppColors.TextMuted,
                fontSize = 14.sp,
                modifier = Modifier.width(28.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.name,
                color = if (isPlaying) AppColors.BrandPrimary else AppColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.subtitle,
                color = AppColors.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 音质角标（QQ音乐搜索页歌列表里有这列），取这首歌最高的音质。
        val topQuality = com.walkman.tv.data.model.Quality.orderedHighToLow.firstOrNull { it in track.qualities }
        if (topQuality != null) {
            MiniChip(topQuality.badgeLabel)
            Spacer(Modifier.width(8.dp))
        }
        if (track.duration != null) {
            Text(track.intervalText, color = AppColors.TextMuted, fontSize = 12.sp)
        }
        if (onFavoriteClick != null) {
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "收藏",
                    tint = if (isFavorite) AppColors.Danger else AppColors.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 绿底圆角小标签（平台 / 音质）。 */
@Composable
fun MiniChip(text: String, color: Color = AppColors.BrandPrimary) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** 圆形头像占位（首字母）。 */
@Composable
fun AvatarDot(letter: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(AppColors.GreenSoft),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = AppColors.BrandPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
