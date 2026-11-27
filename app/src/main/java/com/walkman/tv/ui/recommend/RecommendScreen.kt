package com.walkman.tv.ui.recommend

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
  // 2026-11-27（Yang 定）：左边正在播的大面板撤掉——顶栏右上角发光胶囊已经有正在播
  // 的歌了，左边重复。首页只剩推荐网格，全宽 5 列；焦点表和网格滚动状态还提在这一层，
  // 网格初始焦点和浮层关掉后的恢复走同一张表。
  val restore = rememberOverlayFocusRestore()
  val gridState = rememberLazyGridState()
  RecommendGrid(
    onNavigate = onNavigate,
    onOpenPlayer = onOpenPlayer,
    restore = restore,
    gridState = gridState,
    modifier = modifier.fillMaxSize(),
  )
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
  // 首页打开时焦点落在最上面那张卡片上（现在是歌手区第一张，没有时是每日30首第一首，
  // 再没有是猜你喜欢第一首，最后是推荐歌单第一张）。歌手和每日30首都异步拉，等最上面
  // 那个到了再落，最多等约 3 秒；加载完了还没到（接口挂了）就按顺序落，不干等。
  // 有缓存冷启动时歌单 / 排行榜先画出来，焦点可能先落每日30首那张，等歌手到了按上能进，
  // 不强行抢焦点。
  // 数据是异步拉的，首张卡片可能还没排好版，重试到焦点真正落进网格里为止；焦点已经在
  // 网格里时就停，不把用户已移走的焦点拽回来。
  // 首张卡片的 FocusRequester 和“记住入口卡片”共用同一张表（restore.focuserFor），
  // 初始焦点和浮层关掉后的恢复落的是同一批卡片。首张卡片的 key 同时记到 restore.topCardKey
  // 上（Yang 定：落最顶上，不是落上次那张）。
  var gridFocused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  androidx.compose.runtime.LaunchedEffect(hotSingers, home, guessTracks) {
    if (gridFocused) return@LaunchedEffect
    var firstKey: String? = null
    // 歌手在最上面：先看它，到了直接落；没到就等每日30首（和原来一样最多约 3 秒）。
    // 两个都是异步到的，LaunchedEffect 的 key 里有 hotSingers / home，哪个先到都会重跑、
    // 重新算一次 firstKey，焦点落的是当时最上面那张。
    for (i in 0 until 30) {
      if (hotSingers.isNotEmpty()) {
        firstKey = "singer_${hotSingers[0].source.key}_${hotSingers[0].id}"
        break
      }
      val d = appContainer.homeStore.state.value.daily30
      if (d.isNotEmpty()) { firstKey = "daily30_${d[0].id}"; break }
      if (!appContainer.homeStore.state.value.isLoading) break
      delay(100)
    }
    if (firstKey == null) {
      firstKey = when {
        guessTracks.isNotEmpty() -> "guess_${guessTracks[0].id}"
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

  // 首屏纵向预算（第二十三轮）：网格视口高 = 屏高 − 顶栏，两排海报（含歌名/歌手名）
  // 必须在一屏内完整露出来。海报边长按视口高自适应算，再被 5 列的列宽封顶；
  // 窄屏（720p 电视）上海报会比原来小，这是两排都露出来的代价。
  // 固定部分 438.dp = 上 padding 4 + 3 个区头 28×3 + 间距 10×6 + 歌手行 124
  //   + 已播/收藏 50 + 行文字区 52×2 + 12 余量；两排海报各占 P，(视口高 − 438)/2。
  androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier) {
    val posterW = minOf(
      (maxWidth - 8.dp - 14.dp * 4) / 5,
      (maxHeight - 438.dp) / 2,
    ).coerceAtLeast(96.dp)
    LazyVerticalGrid(
      columns = GridCells.Fixed(5),
      state = gridState,
      modifier = Modifier.fillMaxSize().focusGroup()
        .onFocusChanged { if (it.hasFocus) gridFocused = true },
      contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 32.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      when {
        settings.homeSources.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { NoSourcesHint(onNavigate) }
        home.isLoading && home.heroes.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingPlaceholder() }
        else -> {
          // 推荐页顺序（Yang 定，2026-11-27 改）：歌手 → 已播/收藏 → 每日30首 → 猜你喜欢 → 排行榜 → 推荐歌单。
          val songlists = home.heroes.map { it.songlist } + home.recommendations
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
            // 歌手行：一排 8 个圆头像（Yang 2026-11-27 21:19 定，原来 6 个），头像 72.dp，
            // 卡片宽按行宽算（8 张 + 7 个间距 + 两边 padding），不滚动；统计行不显示，
            // 行高压到 114.dp 左右，别把下面挤下去。列表里不到 8 个时有几个放几个。
            item(span = { GridItemSpan(maxLineSpan) }) {
              val singerCardWidth = (maxWidth - 8.dp - 14.dp * 7) / 8
              Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
              ) {
                hotSingers.take(8).forEach { artist ->
                  val singerKey = "singer_${artist.source.key}_${artist.id}"
                  com.walkman.tv.ui.search.ArtistCard(
                    artist = artist,
                    onClick = { artistFromBrowse = false; artistEntryKey = singerKey; openArtist = artist },
                    modifier = restore.modifierFor(singerKey),
                    cardWidth = singerCardWidth,
                    avatarSize = 72.dp,
                    showStats = false,
                  )
                }
              }
            }
          }
          item(span = { GridItemSpan(maxLineSpan) }) { StatsRow(onNavigate) }
          // 每日30首：横排卡片（前 12 首），点卡片直接播，点“查看全部”进完整 30 首列表。
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
                cardWidth = posterW,
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
                cardWidth = posterW,
              )
            }
          }
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
    modifier = Modifier.fillMaxWidth().height(48.dp),
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
      Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(50))) {
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
    Text(title, color = AppColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
        repeat(5) {
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
 *  浮层关掉恢复 / 初始焦点都走它。
 *  卡片宽由调用方按首屏纵向预算算好传进来（不是写死的 168.dp，也不再是 5 列列宽）：
 *  两排海报（含歌名/歌手名）要在一屏内完整露出来，海报边长按视口高自适应；歌名只
 *  放一行，行高省出来给海报。 */
@Composable
private fun TrackCardRow(
  tracks: List<com.walkman.tv.data.model.Track>,
  keyPrefix: String,
  restore: com.walkman.tv.ui.components.OverlayFocusRestore,
  onPlay: (Int) -> Unit,
  cardWidth: androidx.compose.ui.unit.Dp,
) {
  androidx.compose.foundation.lazy.LazyRow(
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp),
  ) {
    itemsIndexed(tracks.take(12), key = { _, it -> "${keyPrefix}_${it.id}" }) { index, track ->
      val cardKey = "${keyPrefix}_${track.id}"
      Box(Modifier.width(cardWidth)) {
        GridAlbumCard(
          picURL = track.picURL,
          title = track.name,
          subtitle = track.singer,
          fallbackTint = track.source.tintColor(),
          modifier = restore.modifierFor(cardKey),
          onClick = { onPlay(index) },
          titleMaxLines = 1,
        )
      }
    }
  }
}

/** 首页卡片网格里的卡片：封面按格子宽度自适应（正方形），下面是标题 + 副标题。
 *  titleMaxLines 默认 2 行（排行榜 / 推荐歌单网格）；首页两排海报行传 1，行高压一压。 */
@Composable
private fun GridAlbumCard(
  picURL: String?,
  title: String,
  subtitle: String?,
  fallbackTint: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  titleMaxLines: Int = 2,
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
      maxLines = titleMaxLines,
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
