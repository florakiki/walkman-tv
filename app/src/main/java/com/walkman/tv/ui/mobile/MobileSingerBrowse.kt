package com.walkman.tv.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkman.tv.source.catalog.ArtistInfo
import com.walkman.tv.source.catalog.SingerRegion
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.theme.AppColors

/**
 * 手机版歌手浏览页：顶上一排地区 tab（全部 / 内地 / 港台 / 欧美 / 日本 / 韩国，横着能滑），
 * 下面是圆形歌手头像网格，能往下滚；点歌手进歌手页（热门歌 + 全部专辑）。
 * 歌手列表：全部用酷狗 + 网易热门歌手接口合并；地区没有平台接口，用知名歌手名走搜索接口现查拼出来。
 */
@Composable
fun SingerBrowseView(
    onBack: () -> Unit,
    onOpenArtist: (ArtistInfo) -> Unit,
) {
    var region by remember { mutableStateOf(SingerRegion.ALL) }
    var singers by remember { mutableStateOf<List<ArtistInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(region) {
        loading = true
        singers = runCatching { appContainer.artists.hotSingers(region, 48) }.getOrDefault(emptyList())
        loading = false
    }
    BackHandler { onBack() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.BgDeep)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = AppColors.TextPrimary)
            }
            Text("歌手", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SingerRegion.entries) { r ->
                FilterChip(
                    selected = region == r,
                    onClick = { region = r },
                    label = { Text(r.label, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppColors.BrandPrimary,
                        selectedLabelColor = androidx.compose.ui.graphics.Color.White,
                    ),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading -> {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppColors.BrandPrimary)
                }
            }
            singers.isEmpty() -> {
                Text("歌手列表拉不到，检查网络后重进试试", color = AppColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(singers, key = { "${it.source.key}_${it.id}" }) { artist ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenArtist(artist) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CoverImage(
                            artist.picURL,
                            modifier = Modifier.size(76.dp).clip(CircleShape),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            artist.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
