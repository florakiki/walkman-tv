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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.walkman.tv.di.OverlayEntry
import com.walkman.tv.source.catalog.ArtistInfo
import com.walkman.tv.source.catalog.SingerRegion
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.components.EmptyHint
import com.walkman.tv.ui.components.LoadingState
import com.walkman.tv.ui.components.rememberOverlayFocusRestore
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.search.ArtistCard
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

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
    // 上面盖着的歌手页是否还开着：点歌手进歌手页时浏览页并不关（两层浮层叠着），
    // 歌手页关掉时焦点要回到浏览页里刚才点的那张歌手卡片上。
    artistOpen: Boolean = false,
) {
    // 进歌手页之前记住点的那张歌手卡片，出来时焦点回到那张卡片上。
    val restore = rememberOverlayFocusRestore()
    var artistEntryKey by remember { mutableStateOf<String?>(null) }
    var region by remember { mutableStateOf(SingerRegion.ALL) }
    var singers by remember { mutableStateOf<List<ArtistInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(0) }
    val gridState = rememberLazyGridState()

    // 歌手页关掉时焦点回到浏览页里刚才点的那张歌手卡片上：那张卡片可能已经滚出屏幕、
    // 还没组合出来，先把它滚回视野里再聚焦。刚进浏览页时 artistOpen 就是 false，
    // 但那时 entryKey 还是空的，什么都不做。
    LaunchedEffect(artistOpen) {
        if (!artistOpen) {
            val index = artistEntryKey?.let { key -> singers.indexOfFirst { "${it.source.key}_${it.id}" == key } } ?: -1
            if (index >= 0) runCatching { gridState.scrollToItem(index) }
            restore.restoreTo(artistEntryKey)
        }
    }

    suspend fun loadPage(p: Int) {
        val result = runCatching { appContainer.artists.hotSingersPage(region, p) }.getOrNull() ?: return
        singers = if (p == 0) result.singers else singers + result.singers
        hasMore = result.hasMore
        page = p
    }
    LaunchedEffect(region) {
        loading = true
        singers = emptyList()
        hasMore = true
        page = 0
        loadPage(0)
        loading = false
    }
    // 翻到底自动拉下一页（全部 tab 每页 24，地区 tab 每页 10 个种子，最多 3 页）。
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && last >= total - 6
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd && hasMore && !loading && !loadingMore) {
            loadingMore = true
            loadPage(page + 1)
            loadingMore = false
        }
    }
    // 打开就抢一次焦点落到“全部” tab 上，不然焦点还留在被盖住的首页上、页面看着像冻住。
    val firstTabFocus = remember { FocusRequester() }
    var tabFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        for (attempt in 0 until 20) {
            if (tabFocused) break
            runCatching { firstTabFocus.requestFocus() }
            delay(100)
        }
    }
    // 浮层入口：左边面板按右 / 顶栏按下时进浮层里的顶上内容（地区 tab 第一项“全部”）。
    // 上面盖着歌手页时歌手页后登记、在栈顶；歌手页关掉注销后栈顶回到浏览页。
    DisposableEffect(Unit) {
        val entry = OverlayEntry(
            key = "browseSingers",
            focuser = firstTabFocus,
            isFocused = { tabFocused },
            retry = {
                var landed = false
                for (attempt in 0 until 12) {
                    if (tabFocused) { landed = true; break }
                    runCatching { firstTabFocus.requestFocus() }
                    delay(70)
                    if (tabFocused) { landed = true; break }
                }
                landed
            },
        )
        appContainer.events.registerOverlayEntry(entry)
        onDispose { appContainer.events.unregisterOverlayEntry("browseSingers") }
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
                SingerRegion.entries.forEachIndexed { index, r ->
                    TvPill(
                        onClick = { region = r },
                        selected = region == r,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
                        focusRequester = if (index == 0) firstTabFocus else null,
                        modifier = if (index == 0) Modifier.onFocusChanged { if (it.isFocused) tabFocused = true } else Modifier,
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
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(singers, key = { "${it.source.key}_${it.id}" }) { artist ->
                        val singerKey = "${artist.source.key}_${artist.id}"
                        ArtistCard(
                            artist = artist,
                            modifier = restore.modifierFor(singerKey),
                            onClick = { artistEntryKey = singerKey; onOpenArtist(artist) },
                        )
                    }
                    if (loadingMore || !hasMore) {
                        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (loadingMore) "正在加载更多…" else "已显示全部",
                                    color = AppColors.TextMuted,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    BackHandler { onBack() }
}
