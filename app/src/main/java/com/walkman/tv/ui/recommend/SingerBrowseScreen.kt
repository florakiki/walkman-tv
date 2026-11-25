package com.walkman.tv.ui.recommend

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.walkman.tv.source.catalog.ArtistInfo
import com.walkman.tv.source.catalog.SingerRegion
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.components.EmptyHint
import com.walkman.tv.ui.components.LoadingState
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.search.ArtistCard
import com.walkman.tv.ui.theme.AppColors

/**
 * 歌手浏览页（首页“歌手”入口进来）：顶上一排地区 tab（全部 / 内地 / 港台 / 欧美 / 日本 / 韩国），
 * 下面是圆形歌手头像网格，能往下滚；点歌手进歌手页（热门歌 + 全部专辑）。
 * 盖在首页右侧内容区上，左边正在播的那一栏还在。
 * 歌手列表：全部用酷狗 + 网易热门歌手接口合并；地区没有平台接口，用知名歌手名走搜索接口现查拼出来。
 */
@Composable
fun SingerBrowseOverlay(
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
    Box(modifier = Modifier.fillMaxSize().background(AppColors.BgDeep).focusGroup()) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            Text(
                "歌手",
                color = AppColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SingerRegion.entries.forEach { r ->
                    TvPill(
                        onClick = { region = r },
                        selected = region == r,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
                    ) {
                        Text(r.label, fontSize = 14.sp, fontWeight = if (region == r) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                loading -> LoadingState(Modifier.fillMaxSize())
                singers.isEmpty() -> EmptyHint("歌手列表拉不到，检查网络后重进试试", Modifier.fillMaxSize())
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(singers, key = { "${it.source.key}_${it.id}" }) { artist ->
                        ArtistCard(artist = artist, onClick = { onOpenArtist(artist) })
                    }
                }
            }
        }
    }
    BackHandler { onBack() }
}
