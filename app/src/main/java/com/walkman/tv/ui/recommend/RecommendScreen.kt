package com.walkman.tv.ui.recommend

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.walkman.tv.playback.LyricParser
import com.walkman.tv.ui.NavSection
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.playList
import com.walkman.tv.ui.components.Artwork
import com.walkman.tv.ui.components.rememberOverlayFocusRestore
import com.walkman.tv.ui.components.TvFocusable
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.theme.AppColors

@Composable
fun RecommendScreen(
  onNavigate: (NavSection) -> Unit,
  onOpenPlayer: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // 获取屏幕物理总高度（DP）
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val screenHeight = configuration.screenHeightDp.dp

  // 优化：左侧面板总宽度精调为屏幕高度的 55%
  val panelDynamicWidth = screenHeight * 0.55f

  // 左右两块共用同一张焦点表：面板按右要送到网格最顶上那张卡片，网格初始焦点和浮层
  // 关掉后的恢复也走这张表，落的是同一批卡片。网格的滚动状态提上来，面板按右时先把
  // 网格滚回顶部——最顶上那张卡片可能滚出屏幕外还没组合好，不滚回来直接请求焦点落不上。
  val restore = rememberOverlayFocusRestore()
  val gridState = rememberLazyGridState()
  Row(modifier = modifier.fillMaxSize().padding(vertical = 8.dp)) {
    NowPlayingPanel(
      onOpenPlayer = onOpenPlayer,
      restore = restore,
      onScrollGridToTop = { runCatching { gridState.scrollToItem(0) } },
      modifier = Modifier.width(panelDynamicWidth).fillMaxHeight()
    )
    Spacer(Modifier.width(16.dp))
    RecommendGrid(
      onNavigate = onNavigate,
      onOpenPlayer = onOpenPlayer,
      restore = restore,
      gridState = gridState,
      modifier = Modifier.weight(1f).fillMaxHeight(),
    )
  }
}

@Composable
private fun NowPlayingPanel(
  onOpenPlayer: () -> Unit,
  restore: com.walkman.tv.ui.components.OverlayFocusRestore,
  onScrollGridToTop: suspend () -> Unit,
  modifier: Modifier = Modifier,
) {
  val controller = appContainer.playbackController
  val state by controller.state.collectAsState()
  val lyrics by controller.lyrics.collectAsState()
  val track = state.currentTrack
  val scope = androidx.compose.runtime.rememberCoroutineScope()

  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val screenHeight = configuration.screenHeightDp.dp

  // 优化：封面尺寸精准锁定为屏幕高度的 46%（完美的黄金分割占比）
  val coverDynamicSize = screenHeight * 0.46f

  // 面板里当前焦点落在哪个元素上：封面 / 上一首 / 暂停 / 下一首。左右键按它分流——
  // 旧代码的教训（2026-11-26，Yang 视频）：整个 Column 只拦方向键右，三个圆钮
  // 也被包进去了，暂停上按右直接跳右边、落不到下一首。圆钮之间左右走是面板自己的事，
  // 只有从下一首（最右边）和封面按右，才进右边内容区。
  var panelFocus by remember { mutableStateOf(PanelFocus.COVER) }
  val prevFocus = remember { FocusRequester() }
  val playFocus = remember { FocusRequester() }
  val nextFocus = remember { FocusRequester() }

  // 从封面 / 下一首按右进右边内容区（Yang 定：落最顶上那张卡片；浮层开着进浮层顶上）。
  // 圆钮在屏幕最底下一排，空间焦点搜索跨不过右边网格那层 focusGroup（要落的卡片在
  // 上面几行、根本不在圆钮那条横线上），按右就卡在推荐页动不了。这里显式传焦点，不走
  // 空间搜索。按键吃掉的前提是焦点真的送出去了：先同步试一次，落上就吃掉；同步落不上
  // 时后台重试到落上为止（网格滚回顶部再试），绝不出现“按键吃掉了、焦点原地不动”；
  // 只有根本没有目标时才放行走空间搜索。
  fun goRightToContent(): Boolean {
    // 1. 浮层开着 → 进浮层里的顶上内容。
    val entry = appContainer.events.topOverlayEntry
    if (entry != null) {
      runCatching { entry.focuser.requestFocus() }
      if (entry.isFocused()) return true
      // 顶上内容还没组合好（歌单滚下去了、第 0 行不在屏幕上）：后台重试到落上为止。
      // 浮层在屏幕上，重试一定能落上；按键吃掉，不再往下漏。
      scope.launch { entry.retry?.invoke() }
      return true
    }
    // 2. 没浮层 → 同步试最顶上那张卡片，再试上次那张卡片。
    if (restore.tryFocusSync(restore.topCardKey)) return true
    if (restore.tryFocusSync(restore.lastFocusedKey)) return true
    // 3. 同步都落不上：不是没有目标，是目标卡片还没组合好（网格滚下去了、最顶上
    //    那张在屏幕外）。空间搜索进不了网格那层 focusGroup，放行等于没反应，
    //    所以还是吃掉按键、后台把网格滚回顶部再重试到落上为止。
    //    只有根本没有目标（topCardKey 和 lastFocusedKey 都是空）时才放行。
    val topKey = restore.topCardKey
    val key = topKey ?: restore.lastFocusedKey ?: return false
    scope.launch {
      if (topKey != null) onScrollGridToTop()
      restore.restoreTo(key)
      if (topKey != null) restore.restoreTo(restore.lastFocusedKey)
    }
    return true
  }

  Column(
    modifier = modifier.padding(start = 0.dp, end = 8.dp)
      .onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
          Key.DirectionRight -> when (panelFocus) {
            // 圆钮之间左右走：上一首 → 暂停 → 下一首；到下一首再按右才进右边内容区。
            PanelFocus.PREV -> { runCatching { playFocus.requestFocus() }; true }
            PanelFocus.PLAY -> { runCatching { nextFocus.requestFocus() }; true }
            PanelFocus.NEXT, PanelFocus.COVER -> goRightToContent()
          }
          Key.DirectionLeft -> when (panelFocus) {
            PanelFocus.PLAY -> { runCatching { prevFocus.requestFocus() }; true }
            PanelFocus.NEXT -> { runCatching { playFocus.requestFocus() }; true }
            // 最左边那个按左：停在原地、按键吃掉，不漏出去。
            PanelFocus.PREV -> true
            // 封面按左：不动它，走原来的空间搜索。
            PanelFocus.COVER -> false
          }
          // 上下不动：圆钮按上回封面、封面按下来落圆钮那排，面板里只有封面和三个圆钮
          // 可聚焦，空间搜索本来就走得通，不拦。
          else -> false
        }
      },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center // 保持纵向绝对居中，留白非常匀称
  ) {
    // 自适应封面
    TvFocusable(
      onClick = onOpenPlayer,
      modifier = Modifier.size(coverDynamicSize)
        .onFocusChanged { if (it.isFocused) panelFocus = PanelFocus.COVER },
      shape = RoundedCornerShape(12.dp),
    ) {
      Artwork(track?.picURL, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp))
    }
    Spacer(Modifier.height(12.dp))
    if (track != null) {
      Text(track.name, color = AppColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      Text(track.singer, color = AppColors.TextSecondary, fontSize = 13.sp, maxLines = 1)
      Spacer(Modifier.height(8.dp))
      val active = LyricParser.activeIndex(state.positionMs / 1000.0, lyrics)
      val line = lyrics.getOrNull(active)?.text ?: if (state.resolving) "解析中…" else ""
      Text(line, color = AppColors.LyricIdle, fontSize = 13.sp, maxLines = 1)
      // 播放状态行（与播放页同一条）：无条件显示，推荐页播失败了也能看到原因。
      val status = state.error ?: (if (state.resolving) "正在获取播放地址…" else state.warning)
      if (status != null) {
        Text(
          status,
          color = if (state.error != null) AppColors.Danger else AppColors.Warning,
          fontSize = 12.sp,
          maxLines = 1,
        )
      }
    } else {
      Text("暂无播放，去推荐里点歌开始吧", color = AppColors.TextSecondary, fontSize = 14.sp)
    }

    if (track != null) {
      Spacer(Modifier.height(10.dp))
      val audioLevel by controller.audioLevel.collectAsState()

      com.walkman.tv.ui.components.Waveform(
        isPlaying = state.isPlaying,
        level = audioLevel,
        modifier = Modifier.width(coverDynamicSize).height(28.dp),
      )
      Spacer(Modifier.height(6.dp))

      MiniProgressBar(
        positionMs = state.positionMs,
        durationMs = state.durationMs,
        modifier = Modifier.width(coverDynamicSize),
      )
    }
    Spacer(Modifier.height(14.dp))
    // 底部控制按钮：上一首 / 暂停 / 下一首。左右键在三个圆钮之间逐个走（面板 Column
    // 那层按 panelFocus 分流），圆钮自己只记焦点落在哪、不拦按键。
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CircleControl(
        Icons.Filled.SkipPrevious,
        modifier = Modifier.focusRequester(prevFocus)
          .onFocusChanged { if (it.isFocused) panelFocus = PanelFocus.PREV },
      ) { controller.prev() }
      Spacer(Modifier.width(20.dp))
      CircleControl(
        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
        modifier = Modifier.focusRequester(playFocus)
          .onFocusChanged { if (it.isFocused) panelFocus = PanelFocus.PLAY },
      ) { controller.togglePlay() }
      Spacer(Modifier.width(20.dp))
      CircleControl(
        Icons.Filled.SkipNext,
        modifier = Modifier.focusRequester(nextFocus)
          .onFocusChanged { if (it.isFocused) panelFocus = PanelFocus.NEXT },
      ) { controller.next() }
    }
  }
}

/** 左边面板里当前焦点落在哪个元素上（左右键按右 / 按左的分流用）。 */
private enum class PanelFocus { COVER, PREV, PLAY, NEXT }

/** 只读进度条 */
@Composable
private fun MiniProgressBar(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
  val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
  Column(modifier = modifier) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(2.dp)
        .clip(RoundedCornerShape(2.dp))
        .background(AppColors.Card),
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth(fraction)
          .fillMaxHeight()
          .background(AppColors.AccentGreen),
      )
    }
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(fmtMillis(positionMs), color = AppColors.TextMuted, fontSize = 10.sp)
      Text(fmtMillis(durationMs), color = AppColors.TextMuted, fontSize = 10.sp)
    }
  }
}

private fun fmtMillis(ms: Long): String {
  val s = (ms / 1000).toInt().coerceAtLeast(0)
  return "%02d:%02d".format(s / 60, s % 60)
}

@Composable
private fun CircleControl(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  TvPill(
    onClick = onClick,
    modifier = modifier,
    shape = androidx.compose.foundation.shape.CircleShape,
    contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
  }
}

// ============== 右侧推荐网格（保持完全一致） =====================================================

private data class DetailView(val title: String, val subtitle: String?, val tracks: List<com.walkman.tv.data.model.Track>)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun RecommendGrid(
  onNavigate: (NavSection) -> Unit,
  onOpenPlayer: () -> Unit,
  restore: com.walkman.tv.ui.components.OverlayFocusRestore,
  gridState: LazyGridState,
  modifier: Modifier = Modifier,
) {
  val settings by appContainer.settingsStore.settings.collectAsState()
  val home by appContainer.homeStore.state.collectAsState()
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  var detail by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<DetailView?>(null) }
  var loadingDetail by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  // 歌手浏览页（地区 tab + 圆形头像网格）与歌手页浮层的状态。
  var browseSingers by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  var openArtist by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.walkman.tv.source.catalog.ArtistInfo?>(null) }
  var hotSingers by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<com.walkman.tv.source.catalog.ArtistInfo>>(emptyList()) }
  // 首页“歌手”区只放前 12 个热门歌手预览，点“查看全部”进浏览页再按地区拉。
  androidx.compose.runtime.LaunchedEffect(Unit) {
    hotSingers = runCatching {
      appContainer.artists.hotSingers(com.walkman.tv.source.catalog.SingerRegion.ALL, 12)
    }.getOrDefault(emptyList())
  }
  // 猜你喜欢：本机播放历史 + 收藏推（见 GuessYouLike.kt），没历史时热门榜兜底。
  // 只读 libraryStore 的 StateFlow，不改它；历史 / 收藏 / 启用的音源一变就重算。
  val history by appContainer.libraryStore.history.collectAsState()
  val loved by appContainer.libraryStore.love.collectAsState()
  var guessTracks by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<com.walkman.tv.data.model.Track>>(emptyList()) }
  androidx.compose.runtime.LaunchedEffect(history, loved, settings.homeSources) {
    guessTracks = runCatching {
      buildGuessYouLike(appContainer.catalogs, appContainer.boards, settings.homeSources, history.tracks, loved.tracks)
    }.getOrDefault(emptyList())
  }
  // 进浮层之前记住点的那张卡片，出来时焦点回到那张卡片上（不是回首页）：浮层关掉时
  // 焦点是被整个清空的，不记就回不到点下去的位置。
  // restore 是 RecommendScreen 传下来的、左右两块共用的那张表（面板按右回网格也走它）。
  var detailEntryKey by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
  var artistEntryKey by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
  var browseEntryKey by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
  androidx.compose.runtime.LaunchedEffect(detail) { if (detail == null) restore.restoreTo(detailEntryKey) }
  // 从歌手浏览页点歌手进的歌手页，关掉时由浏览页自己把焦点送回浏览页里的那张卡片，
  // 推荐页这层不抢（不然两边同时抢，焦点可能落在被盖住的推荐页卡片上）。
  var artistFromBrowse by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  androidx.compose.runtime.LaunchedEffect(openArtist) {
    if (openArtist == null && !artistFromBrowse) restore.restoreTo(artistEntryKey)
  }
  androidx.compose.runtime.LaunchedEffect(browseSingers) { if (!browseSingers) restore.restoreTo(browseEntryKey) }
  // 首页打开时焦点落在最上面那张卡片上（现在是每日30首第一首，没有时是猜你喜欢第一首，
  // 再没有是歌手区第一张，最后是推荐歌单第一张）。每日30首比歌单 / 排行榜晚到（单独拉的），
  // 还在加载时等它最多约 3 秒，等到了直接落它上面；加载完了还没到（接口挂了）就按老顺序落，
  // 不干等。有缓存冷启动时歌单 / 排行榜先画出来，焦点可能先落歌手卡，等每日30首到了按上
  // 或面板按右能进，不强行抢焦点。
  // 数据是异步拉的，首张卡片可能还没排好版，重试到焦点真正落进网格里为止；焦点已经在
  // 网格里时就停，不把用户已移走的焦点拽回来。
  // 首张卡片的 FocusRequester 和“记住入口卡片”共用同一张表（restore.focuserFor），
  // 初始焦点和浮层关掉后的恢复落的是同一批卡片。首张卡片的 key 同时记到 restore.topCardKey
  // 上，左边面板按右就落到它上面（Yang 定：落最顶上，不是落上次那张）。
  var gridFocused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  androidx.compose.runtime.LaunchedEffect(hotSingers, home, guessTracks) {
    if (gridFocused) return@LaunchedEffect
    var firstKey: String? = null
    for (i in 0 until 30) {
      val d = appContainer.homeStore.state.value.daily30
      if (d.isNotEmpty()) { firstKey = "daily30_${d[0].id}"; break }
      if (!appContainer.homeStore.state.value.isLoading) break
      delay(100)
    }
    if (firstKey == null) {
      firstKey = when {
        guessTracks.isNotEmpty() -> "guess_${guessTracks[0].id}"
        hotSingers.isNotEmpty() -> "singer_${hotSingers[0].source.key}_${hotSingers[0].id}"
        home.heroes.isNotEmpty() -> "songlist_${home.heroes[0].songlist.source.key}_${home.heroes[0].songlist.id}"
        home.recommendations.isNotEmpty() -> "songlist_${home.recommendations[0].source.key}_${home.recommendations[0].id}"
        else -> null
      }
    }
    restore.topCardKey = firstKey
    val target = firstKey?.let { restore.focuserFor(it) }
    for (attempt in 0 until 20) {
      if (gridFocused) break
      if (target == null) break
      runCatching { target.requestFocus() }
      delay(100)
    }
  }

  androidx.compose.runtime.LaunchedEffect(settings.homeSources) {
    appContainer.homeStore.loadIfNeeded(settings.homeSources)
  }

  fun openSonglist(info: com.walkman.tv.data.model.SonglistInfo) {
    if (loadingDetail) return
    scope.launch {
      loadingDetail = true
      val svc = appContainer.songlists.serviceFor(info.source)
      val d = runCatching { svc?.fetchDetail(info) }.getOrNull()
      detail = DetailView(
        title = info.name,
        subtitle = info.author.takeIf { it.isNotBlank() },
        tracks = d?.tracks ?: emptyList(),
      )
      loadingDetail = false
    }
  }

  fun openBoard(board: com.walkman.tv.data.model.BoardInfo) {
    if (loadingDetail) return
    scope.launch {
      loadingDetail = true
      val svc = appContainer.boards.serviceFor(board.source)
      val tracks = runCatching { svc?.fetchTracks(board.bangid, 1) ?: emptyList() }.getOrDefault(emptyList())
      detail = DetailView(
        title = board.name,
        subtitle = null,
        tracks = tracks,
      )
      loadingDetail = false
    }
  }

  Box(modifier = modifier) {
    LazyVerticalGrid(
      columns = GridCells.Fixed(4),
      state = gridState,
      modifier = Modifier.fillMaxSize().focusGroup()
        .onFocusChanged { if (it.hasFocus) gridFocused = true },
      contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 32.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      when {
        settings.homeSources.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { NoSourcesHint(onNavigate) }
        home.isLoading && home.heroes.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingPlaceholder() }
        else -> {
          // 推荐页顺序（Yang 定，2026-11-26 改）：每日30首 → 猜你喜欢 → 歌手 → 已播/收藏 → 排行榜 → 推荐歌单。
          val songlists = home.heroes.map { it.songlist } + home.recommendations
          // 每日30首放最前面：横排卡片（前 12 首），点卡片直接播，点“查看全部”进完整 30 首列表。
          // 空的时候不画这段（接口全挂就当没有，不留空白标题）。
          if (home.daily30.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              SectionHeader("每日30首", "每天更新", trailing = "查看全部") {
                detail = DetailView("每日30首", "每天更新", home.daily30)
              }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
              TrackCardRow(
                tracks = home.daily30,
                keyPrefix = "daily30",
                restore = restore,
                onPlay = { index -> playList(home.daily30, index); onOpenPlayer() },
              )
            }
          }
          // 猜你喜欢紧接着它：同上，横排卡片 + 查看全部。
          if (guessTracks.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              SectionHeader("猜你喜欢", null, trailing = "查看全部") {
                detail = DetailView("猜你喜欢", null, guessTracks)
              }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
              TrackCardRow(
                tracks = guessTracks,
                keyPrefix = "guess",
                restore = restore,
                onPlay = { index -> playList(guessTracks, index); onOpenPlayer() },
              )
            }
          }
          // 歌手入口：圆形头像横排预览，点“查看全部”进歌手浏览页（地区 tab + 头像网格）。
          if (hotSingers.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              SectionHeader(
                "歌手", null,
                trailing = "查看全部",
                trailingModifier = restore.modifierFor("browse_all"),
              ) {
                browseEntryKey = "browse_all"
                browseSingers = true
              }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
              androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp),
              ) {
                itemsIndexed(hotSingers, key = { _, it -> "${it.source.key}_${it.id}" }) { index, artist ->
                  val singerKey = "singer_${artist.source.key}_${artist.id}"
                  com.walkman.tv.ui.search.ArtistCard(
                    artist = artist,
                    onClick = { artistFromBrowse = false; artistEntryKey = singerKey; openArtist = artist },
                    modifier = restore.modifierFor(singerKey),
                  )
                }
              }
            }
          }
          item(span = { GridItemSpan(maxLineSpan) }) { StatsRow(onNavigate) }
          if (home.boards.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              SectionHeader("排行榜", null, trailing = "查看全部") {
                onNavigate(NavSection.Leaderboard)
              }
            }
            gridItems(
              home.boards.distinctBy { "${it.source.key}_${it.id}" },
              key = { "${it.source.key}_${it.id}" },
            ) { board ->
              val boardKey = "board_${board.source.key}_${board.id}"
              GridAlbumCard(
                picURL = board.picURL,
                title = board.name,
                subtitle = null,
                fallbackTint = board.source.tintColor(),
                modifier = restore.modifierFor(boardKey),
                onClick = { detailEntryKey = boardKey; openBoard(board) },
              )
            }
          }
          // 推荐歌单放最后：每平台的第一张（heroes）+ 其余推荐，全部摊平成卡片网格，能往下滚。
          if (songlists.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              SectionHeader("推荐歌单", null, trailing = "查看全部") {
                onNavigate(NavSection.Songlist)
              }
            }
            gridItemsIndexed(
              songlists.distinctBy { "${it.source.key}_${it.id}" },
              key = { _, info -> "${info.source.key}_${info.id}" },
            ) { index, info ->
              val songlistKey = "songlist_${info.source.key}_${info.id}"
              GridAlbumCard(
                picURL = info.picURL,
                title = info.name,
                subtitle = info.playCount?.let { "▶ $it" },
                fallbackTint = info.source.tintColor(),
                modifier = restore.modifierFor(songlistKey),
                onClick = { detailEntryKey = songlistKey; openSonglist(info) },
              )
            }
          }
        }
      }
    }

    detail?.let { d ->
      com.walkman.tv.ui.components.TracksDetailOverlay(
        title = d.title,
        subtitle = d.subtitle,
        tracks = d.tracks,
        onBack = { detail = null },
        onOpenPlayer = onOpenPlayer,
      )
    }
    if (browseSingers) {
      SingerBrowseOverlay(
        onBack = { browseSingers = false },
        onOpenArtist = { artistFromBrowse = true; openArtist = it },
        artistOpen = openArtist != null,
      )
    }
    openArtist?.let { artist ->
      com.walkman.tv.ui.search.ArtistDetailOverlay(
        artist = artist,
        onBack = { openArtist = null },
        onOpenPlayer = onOpenPlayer,
      )
    }
  }
}

@Composable
private fun StatsRow(onNavigate: (NavSection) -> Unit) {
  val played by appContainer.libraryStore.history.collectAsState()
  val loved by appContainer.libraryStore.love.collectAsState()
  Row(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxWidth().height(72.dp),
  ) {
    StatTile(
      label = "已播",
      count = played.tracks.size,
      picURL = StatThumbs.PLAYED,
      modifier = Modifier.weight(1f),
    ) { onNavigate(NavSection.Library) }
    StatTile(
      label = "收藏",
      count = loved.tracks.size,
      picURL = StatThumbs.LOVE,
      modifier = Modifier.weight(1f),
    ) { onNavigate(NavSection.Library) }
  }
}

private object StatThumbs {
  const val PLAYED = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=200&q=60"
  const val LOVE   = "https://images.unsplash.com/photo-1459749411175-04bf5292ceea?w=200&q=60"
}

@Composable
private fun StatTile(label: String, count: Int, picURL: String, modifier: Modifier, onClick: () -> Unit) {
  TvFocusable(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(36.dp)) {
    Row(
      modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 20.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(50))) {
        AsyncImage(
          model = picURL,
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      }
      Spacer(Modifier.width(12.dp))
      Text(
        label,
        color = AppColors.TextSecondary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f),
      )
      Text(
        "$count",
        color = AppColors.TextPrimary,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

@Composable
private fun SectionHeader(
  title: String,
  subtitle: String?,
  trailing: String?,
  // 放在 onTrailingClick 前面：Kotlin 的尾随 lambda 只能绑最后一个参数，
  // onTrailingClick 必须留在最后，不然外面那几处尾随 lambda 全绑错。
  trailingModifier: Modifier = Modifier,
  onTrailingClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
    verticalAlignment = Alignment.Bottom,
  ) {
    Text(title, color = AppColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    if (!subtitle.isNullOrBlank()) {
      Spacer(Modifier.width(10.dp))
      Text(
        subtitle,
        color = AppColors.TextMuted,
        fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 4.dp),
      )
    }
    Spacer(Modifier.weight(1f))
    if (!trailing.isNullOrBlank()) {
      TvPill(
        onClick = onTrailingClick,
        modifier = trailingModifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
      ) {
        Text(trailing, fontSize = 12.sp)
      }
    }
  }
}

@Composable
private fun LoadingPlaceholder() {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth().height(260.dp)
        .clip(RoundedCornerShape(20.dp))
        .background(AppColors.Card),
    )
    repeat(2) {
      Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(4) {
          Box(
            modifier = Modifier
              .size(170.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(AppColors.Card),
          )
        }
      }
    }
  }
}

@Composable
private fun NoSourcesHint(onNavigate: (NavSection) -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("没有勾选任何音源", color = AppColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text("去设置里勾选一个音源后再来发现", color = AppColors.TextMuted, fontSize = 13.sp)
    Spacer(Modifier.height(16.dp))
    TvPill(onClick = { onNavigate(NavSection.Settings) }, selected = true) {
      Text("打开设置", fontSize = 13.sp)
    }
  }
}

private fun com.walkman.tv.data.model.SourceID.tintColor(): Color = when (this) {
  com.walkman.tv.data.model.SourceID.KW -> AppColors.SourceKw
  com.walkman.tv.data.model.SourceID.KG -> AppColors.SourceKg
  com.walkman.tv.data.model.SourceID.TX -> AppColors.SourceTx
  com.walkman.tv.data.model.SourceID.WY -> AppColors.SourceWy
  com.walkman.tv.data.model.SourceID.MG -> AppColors.SourceMg
  com.walkman.tv.data.model.SourceID.LOCAL -> AppColors.SourceLocal
}

/** 新区（每日30首 / 猜你喜欢）的横排歌曲卡片行：前 12 首，点卡片直接播整段队列。
 *  key 带前缀（daily30_ / guess_），和歌单卡片的 key 不撞；焦点登记进 restore 那张表，
 *  面板按右 / 浮层关掉恢复 / 初始焦点都走它。 */
@Composable
private fun TrackCardRow(
  tracks: List<com.walkman.tv.data.model.Track>,
  keyPrefix: String,
  restore: com.walkman.tv.ui.components.OverlayFocusRestore,
  onPlay: (Int) -> Unit,
) {
  androidx.compose.foundation.lazy.LazyRow(
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp),
  ) {
    itemsIndexed(tracks.take(12), key = { _, it -> "${keyPrefix}_${it.id}" }) { index, track ->
      val cardKey = "${keyPrefix}_${track.id}"
      Box(Modifier.width(168.dp)) {
        GridAlbumCard(
          picURL = track.picURL,
          title = track.name,
          subtitle = track.singer,
          fallbackTint = track.source.tintColor(),
          modifier = restore.modifierFor(cardKey),
          onClick = { onPlay(index) },
        )
      }
    }
  }
}

/** 首页卡片网格里的卡片：封面按格子宽度自适应（正方形），下面是标题 + 副标题。 */
@Composable
private fun GridAlbumCard(
  picURL: String?,
  title: String,
  subtitle: String?,
  fallbackTint: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    TvFocusable(
      onClick = onClick,
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .then(modifier),
      shape = RoundedCornerShape(12.dp),
    ) {
      Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) {
        if (!picURL.isNullOrBlank()) {
          AsyncImage(
            model = picURL,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
          )
        } else {
          Box(
            modifier = Modifier.fillMaxSize().background(
              Brush.linearGradient(listOf(fallbackTint, fallbackTint.copy(alpha = 0.5f))),
            ),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              Icons.Filled.PlayArrow,
              contentDescription = null,
              tint = Color.White.copy(alpha = 0.7f),
              modifier = Modifier.size(40.dp),
            )
          }
        }
      }
    }
    Spacer(Modifier.height(8.dp))
    Text(
      title,
      color = AppColors.TextPrimary,
      fontSize = 16.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    if (!subtitle.isNullOrBlank()) {
      Text(
        subtitle,
        color = AppColors.TextMuted,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
