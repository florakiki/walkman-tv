package com.walkman.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import androidx.tv.material3.Text
import com.walkman.tv.di.CrashDiagnostics
import com.walkman.tv.data.model.Track
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One focusable track row: index, cover, name/singer, quality badge + source chip.
 *  [downloaded] shows a small green ✓ download icon — opt-in, only set by 我的列表.
 *  [onDownload] / [onAddToPlaylist] / [onRemove] add always-visible icon buttons at the row end (D-pad Right
 *  from the row reaches them); [modifier] applies to the main (play) focusable. */
@Composable
fun TrackRow(
    track: Track,
    index: Int,
    modifier: Modifier = Modifier,
    nowPlaying: Boolean = false,
    downloaded: Boolean = false,
    onDownload: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "删除",
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TvFocusable(onClick = onClick, modifier = modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    if (nowPlaying) {
                        MiniWaveform(modifier = Modifier.size(width = 22.dp, height = 18.dp))
                    } else {
                        Text(
                            "${index + 1}",
                            color = AppColors.TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Artwork(track.picURL, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        track.name,
                        color = if (nowPlaying) AppColors.AccentGreen else AppColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
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
                Spacer(Modifier.width(8.dp))
                if (downloaded) {
                    androidx.tv.material3.Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = "已下载",
                        tint = AppColors.AccentGreen,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (track.hasMv) {
                    MvBadge()
                    Spacer(Modifier.width(6.dp))
                }
                QualityBadge(track.qualities)
                Spacer(Modifier.width(8.dp))
                // 时长（QQ音乐搜索页歌列表里有这列）。
                if (track.duration != null) {
                    Text(track.intervalText, color = AppColors.TextSecondary, fontSize = 13.sp)
                }
                // 不显示平台来源：歌是哪个平台的不在界面上标，源的选择全在后台自动做。
            }
        }
        if (onDownload != null || onAddToPlaylist != null || onRemove != null) {
            // Tight cluster: the buttons sit closer to each other than to the row.
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                onDownload?.let { RowAction(Icons.Filled.Download, "下载", it) }
                onAddToPlaylist?.let { RowAction(Icons.AutoMirrored.Filled.PlaylistAdd, "添加到歌单", it) }
                onRemove?.let { RowAction(Icons.Filled.DeleteOutline, removeLabel, it, danger = true) }
            }
        }
    }
}

/** Small icon button at a track row's end. Dimmed until focused so a column of them stays quiet. */
@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    TvPill(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
        shape = androidx.compose.foundation.shape.CircleShape,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        accent = if (danger) AppColors.Danger else AppColors.BrandPrimary,
        contentPadding = PaddingValues(8.dp),
    ) {
        androidx.tv.material3.Icon(
            imageVector = icon,
            contentDescription = label,
            tint = androidx.tv.material3.LocalContentColor.current.copy(alpha = if (focused) 1f else 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Tiny 6-bar animated equaliser used as the "now playing" indicator in list rows.
 * Each bar runs its own sine phase so they don't move in lockstep.
 *
 * Public so other surfaces (e.g. the player queue drawer) can reuse the same indicator.
 */
@Composable
internal fun MiniWaveform(modifier: Modifier = Modifier) {
    val bars = 6
    val transition = rememberInfiniteTransition(label = "row-eq")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart,
        ),
        label = "row-eq-phase",
    )
    Canvas(modifier = modifier) {
        val totalW = size.width
        val barWidth = totalW / bars * 0.55f
        val gap = (totalW - barWidth * bars) / (bars - 1).coerceAtLeast(1)
        val centerY = size.height / 2
        val maxH = size.height * 0.95f
        val minH = size.height * 0.25f
        for (i in 0 until bars) {
            val x = i * (barWidth + gap)
            val v = ((sin(phase * (1f + (i % 3) * 0.55f) + i * 0.7f) + 1f) / 2f)
            val h = (minH + (maxH - minH) * v).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                color = AppColors.AccentGreen,
                topLeft = Offset(x, centerY - h / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}

@Composable
private fun MvBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(AppColors.AccentGreen.copy(alpha = 0.18f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text("MV", color = AppColors.AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * A focusable list of tracks. Back-key behavior: when the list has been scrolled away from
 * the top, the first press scrolls back to row 0 and refocuses it; only when already at the
 * top does back fall through to the parent BackHandler (which navigates out one layer).
 *
 * 焦点恢复不走 `Modifier.focusRestorer()`（它在节点脱离时抛 IllegalStateException、会闪退，
 * 2026-11-26 已定性）：播放器浮层关掉后由下面 playerClosed 那处恢复到上次点的那行；
 * 其它浮层由调用方传的 entryFocuser / OverlayFocusRestore 管。
 * If [initialFocus] is true the list grabs focus on its first row on first composition —
 * used by overlays (songlist detail) where the list appears on top of something focused.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TrackList(
    tracks: List<Track>,
    modifier: Modifier = Modifier,
    nowPlayingId: String? = null,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    initialFocus: Boolean = false,
    showDownloadedBadge: Boolean = false,
    /** Non-null ⇒ every row gets a delete button (从歌单移除 / 删除播放历史 …). */
    onRemove: ((Track) -> Unit)? = null,
    removeLabel: String = "删除",
    /** true ⇒ 上下到头时把按键吃掉、停在原地，绝不漏到列表外（浮层里的列表用；顶层列表默认 false，走原来的空间焦点搜索）。 */
    trapFocus: Boolean = false,
    /** trapFocus 且在第 0 行按上时调用（浮层内往上走：歌手页回专辑行、搜索页回 tab 行）；不设就停在第 0 行。 */
    onExitUp: (() -> Unit)? = null,
    /** 焦点行变化时回调（歌手页用它在进歌单深处时收起专辑区）。 */
    onFocusedIndexChange: ((Int) -> Unit)? = null,
    /** 外面传进来的列表滚动状态（不传就自己建一个）：歌手页翻页时外面要看滚到哪了，
     *  浮层入口重试时外面要把列表滚回顶部。 */
    listState: LazyListState? = null,
    /** 浮层入口用的请求器：挂在第 0 行上。左边面板按右 / 顶栏按下时，
     *  浮层通过它把焦点送到列表最上面那一行。 */
    entryFocuser: FocusRequester? = null,
    /** 列表最前面的一块内容（歌手页用它把歌手头 + 专辑区放进同一列里，整页顺着往下滚，
     *  不用收起/展开那套状态切换）。不传就没有这一项。 */
    header: (@Composable () -> Unit)? = null,
    /** 列表最后面的一块内容（歌手页用它放“正在加载更多…/已显示全部 N 首”那行脚标）。不传就没有这一项。 */
    footer: (@Composable () -> Unit)? = null,
    onPlay: (index: Int) -> Unit,
) {
    // header 占掉布局里的 0 号位：下面步进里凡是拿 layoutInfo 的下标（可见行判断、
    // scrollToItem）都要加这个偏移；歌曲行自己的下标（focusedIndex、focuserFor）
    // 还是按 tracks 记，不动。不传 header 时偏移是 0，其它调用方行为一点不变。
    val headerOffset = if (header != null) 1 else 0
    // Collect once here (shared StateFlows) and derive the set of tracks that are downloaded AND
    // whose file is actually present, so rows don't each subscribe. Reactive: a finished download,
    // a removed download, or a play-time "文件缺失" discovery all update it. Used to hide the row's
    // 下载 button everywhere; the ✓ badge only where [showDownloadedBadge] (我的列表) opts in.
    val records by com.walkman.tv.ui.appContainer.downloadStore.records.collectAsState()
    val missing by com.walkman.tv.ui.appContainer.downloadStore.missingDownloads.collectAsState()
    val completedIds: Set<String> = remember(records, missing) {
        records.values
            .filter { it.status == com.walkman.tv.data.model.DownloadStatus.COMPLETED }
            .map { it.track.id }
            .filter { it !in missing }
            .toSet()
    }
    val effectiveListState = listState ?: rememberLazyListState()
    // One FocusRequester per row, stable across recompositions: explicit Up/Down handling
    // below moves focus through these, so navigation never depends on Compose's spatial
    // focus search alone — that search is what left focus wandering to the TopNav instead
    // of landing on a song in the 热歌榜 detail list.
    // 崩点教训（2026-11-26，Yang 实测）：显示出来的行没事，往下翻到还没显示的行就闪退——
    // 旧步进对没组合出来的行直接请求焦点。修法见下面的 stepFocus：目标行不在屏幕上时，
    // 先把列表滚过去、等行组合好再聚焦，失败有兜底，绝不对没显示的行直接请求焦点。
    // 列表换了就重建整张表，不留指着已拆掉的行的旧 FocusRequester。
    // 注意：翻页是往后追加（首首歌不变），不能按整个 tracks 记——追加会把整张表和
    // focusedIndex 都重置，焦点就从正在看的那一行丢了。按首首歌的 id 记：
    // 追加时首首不变、表和焦点行都不动；换了一份新列表（首首不同）时才重建。
    val listIdentity = tracks.firstOrNull()?.id
    val rowFocusers = remember(listIdentity) { mutableMapOf<Int, FocusRequester>() }
    val focuserFor: (Int) -> FocusRequester = { rowFocusers.getOrPut(it) { FocusRequester() } }
    // Index of the row that currently holds focus (-1 = focus is outside the list).
    var focusedIndex by remember(listIdentity) { androidx.compose.runtime.mutableStateOf(-1) }
    // 每次滚动步进的目标行：快速连按时，旧的滚动协程看到目标变了就自己停，别把焦点拽回旧行。
    var stepTarget by remember { androidx.compose.runtime.mutableStateOf(-1) }
    // Holds the index of the row most recently clicked-to-play. When the player overlay
    // closes we re-request focus on this row so the user returns to the same line they
    // launched playback from.
    var lastClickedIndex by remember { androidx.compose.runtime.mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    // 往上/往下一行移焦点。目标行已经在屏幕上就直接聚焦；还没显示出来（在屏幕外、没组合）
    // 就先把列表滚过去，等行组合好再聚焦。
    //
    // 教训（2026-11-26，Yang 实测安泊猜想 9 首）：旧步进只试两次、每次等 80ms，慢电视上
    // 第 9 行还没组合好两次就试完了，焦点停在第 8 首到不了第 9 首；再按一下时按键没被吃掉，
    // 焦点被默认搜索送到浮层下面去了。新步进：滚过去后循环重试，直到行真正显示出来且焦点
    // 落上去、或试满 12 次（约 0.9 秒）才放弃；中途行一直没显示就再滚一次；快速连按时旧
    // 协程看到目标变了就自己停，不把焦点拽回旧行。
    // 到头（第 0 行按上 / 最后一行按下）：trapFocus 时把按键吃掉、停在原地，绝不漏到列表外；
    // 非 trapFocus 保持原来的放行（顶层页面里焦点要能回到上面的 tab/行）。
    fun stepFocus(delta: Int): Boolean {
        if (focusedIndex < 0) {
            if (!trapFocus) return false
            if (tracks.isEmpty()) return true
            // layoutInfo 的下标是布局下标（header 占 0 号位），focuserFor 要的是歌曲下标，减掉偏移。
            val first = effectiveListState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: headerOffset
            runCatching { focuserFor((first - headerOffset).coerceIn(tracks.indices)).requestFocus() }
            return true
        }
        val target = focusedIndex + delta
        if (target < 0 || target >= tracks.size) {
            if (!trapFocus) return false
            if (delta < 0) onExitUp?.invoke()
            return true
        }
        // 布局下标 = 歌曲下标 + header 偏移。
        val visible = effectiveListState.layoutInfo.visibleItemsInfo.any { it.index == target + headerOffset }
        if (visible) {
            runCatching { focuserFor(target).requestFocus() }
        } else {
            stepTarget = target
            scope.launch {
                runCatching { effectiveListState.scrollToItem(target + headerOffset) }
                repeat(12) {
                    if (focusedIndex == target || stepTarget != target) return@launch
                    val nowVisible = effectiveListState.layoutInfo.visibleItemsInfo.any { it.index == target + headerOffset }
                    if (nowVisible) {
                        runCatching { focuserFor(target).requestFocus() }
                    } else {
                        runCatching { effectiveListState.scrollToItem(target + headerOffset) }
                    }
                    delay(70)
                }
                runCatching { focuserFor(target).requestFocus() }
            }
        }
        return true
    }
    val atTop by remember {
        derivedStateOf {
            effectiveListState.firstVisibleItemIndex == 0 && effectiveListState.firstVisibleItemScrollOffset == 0
        }
    }

    BackHandler(enabled = !atTop) {
        scope.launch {
            runCatching { effectiveListState.animateScrollToItem(0) }
            // Wait a frame so item 0 is composed/visible before requesting focus.
            delay(80)
            runCatching { focuserFor(0).requestFocus() }
        }
    }

    if (initialFocus) {
        LaunchedEffect(tracks.firstOrNull()?.id) {
            // A one-shot focus request silently fails when the row isn't laid out yet.
            // Retry until the first row actually takes focus (onFocusChanged reports it
            // via focusedIndex) — but stop the moment focus is already inside the list,
            // so a late retry never yanks focus back from a row the user already moved to.
            for (attempt in 0 until 20) {
                if (focusedIndex >= 0) break
                runCatching { focuserFor(0).requestFocus() }
                delay(100)
            }
        }
    }

    // Restore focus to the most-recently clicked row when the player overlay closes.
    LaunchedEffect(Unit) {
        com.walkman.tv.ui.appContainer.events.playerClosed.collect {
            if (lastClickedIndex in tracks.indices) {
                // Scroll it into view, wait for compose, then request focus.
                runCatching { effectiveListState.animateScrollToItem(lastClickedIndex) }
                repeat(5) {
                    delay(60)
                    runCatching { focuserFor(lastClickedIndex).requestFocus() }
                    if (focusedIndex == lastClickedIndex) return@collect
                }
            }
        }
    }

    // Row-end 下载 / ＋歌单 buttons open their dialogs. Owned here so every TrackList consumer
    // (search / library / songlist / leaderboard / recommend) gets them.
    var pickerTrack by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Track?>(null) }
    var downloadTrack by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Track?>(null) }

    val rowKeys = remember(tracks) { uniqueKeys(tracks.map { it.id }) }

    LazyColumn(
        state = effectiveListState,
        // 播放器浮层关掉后回哪一行，由上面 playerClosed 那处 LaunchedEffect 管（记
        // lastClickedIndex、滚回去再重试聚焦）；这里不用 focusRestorer，它在节点脱离时
        // 抛 IllegalStateException（2026-11-26 闪退根因），删掉。
        // Explicit Up/Down: step focus through the rows' own FocusRequesters instead of
        // relying on spatial focus search. Up from row 0 / Down from the last row fall
        // through (return false) so focus can still leave the list normally.
        // 安全步进见上面的 stepFocus：目标行没显示出来时先滚出来再聚焦，不直接请求焦点。
        modifier = modifier
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> stepFocus(+1)
                    Key.DirectionUp -> stepFocus(-1)
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = contentPadding,
    ) {
        // 歌手页一页用：歌手头 + 专辑区在最前面、脚标在最后面，都跟歌曲行在同一列里滚。
        // header 占布局 0 号位（步进里加 headerOffset 换算，见上面）。
        if (header != null) item(key = "tracklist_header") { header() }
        itemsIndexed(tracks, key = { i, _ -> rowKeys[i] }) { index, track ->
            // Exactly one FocusRequester per row, so initial-focus / click-restore never
            // collide on the same row. onFocusChanged tracks which row holds focus — the
            // explicit Up/Down handler above uses it to step row by row.
            val rowModifier = Modifier
                .focusRequester(focuserFor(index))
                // 浮层入口请求器挂在第 0 行上（和本行自己的请求器并存，一行可以挂多个，
                // 和歌手页第一张专辑卡片上 anchorFocus + albumRestore 并存一个道理）。
                .then(if (index == 0 && entryFocuser != null) Modifier.focusRequester(entryFocuser) else Modifier)
                .onFocusChanged {
                    if (it.isFocused) {
                        focusedIndex = index
                        CrashDiagnostics.noteFocus("歌曲「${track.name}」第 ${index + 1} 行")
                        onFocusedIndexChange?.invoke(index)
                    }
                }
            TrackRow(
                track = track,
                index = index,
                modifier = rowModifier,
                nowPlaying = track.id == nowPlayingId,
                downloaded = showDownloadedBadge && track.id in completedIds,
                onDownload = if (track.canDownload && track.id !in completedIds) ({ downloadTrack = track }) else null,
                onAddToPlaylist = { pickerTrack = track },
                onRemove = onRemove?.let { remove -> { remove(track) } },
                removeLabel = removeLabel,
                onClick = {
                    lastClickedIndex = index
                    onPlay(index)
                },
            )
        }
        if (footer != null) item(key = "tracklist_footer") { footer() }
    }

    pickerTrack?.let { t ->
        com.walkman.tv.ui.components.PlaylistPickerDialog(track = t, onDismiss = { pickerTrack = null })
    }
    downloadTrack?.let { t ->
        DownloadDialog(track = t, onDismiss = { downloadTrack = null })
    }
}

/** Local-folder tracks are already files on the device — no 下载 button for them. */
internal val Track.canDownload: Boolean
    get() = !songmid.startsWith("lf://")
