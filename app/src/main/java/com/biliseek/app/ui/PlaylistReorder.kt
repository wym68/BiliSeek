package com.biliseek.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.biliseek.app.data.ItemSpan
import com.biliseek.app.data.PlaylistTrack
import com.biliseek.app.data.autoScrollSpeed
import com.biliseek.app.data.moved
import com.biliseek.app.data.reorderNeighbors
import com.biliseek.app.data.reorderSwapTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 本地列表长按拖动排序（设计稿：docs/superpowers/specs/2026-10-04-playlist-reorder-design.md）。
 *
 * - 长按满时间：那一行拿起来，震一下；
 * - 拖动：被拖那一行跟着手指走，它的中线越过别的行时本地顺序挪一格，别的行滑开让位，每挪一格轻震一下；
 *   拖到列表上下边缘自动滚动；
 * - 松手：这一行从手指处滑进空位，按「最初位置 → 最终位置」写一次库（两侧 sortKey 取中值）。
 *
 * 几何都按 [LazyListState.layoutInfo] 实测，被拖那一行按下标找（挪格后、重新布局前，新下标处就是它要落的格子）。
 * 手势回调读的都是最新的列表与回调，不会留着旧下标。
 */
@Stable
internal class PlaylistReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val haptic: HapticFeedback,
    private val edgePx: Float,
    private val maxSpeedPx: Float,
    private val tracks: () -> List<PlaylistTrack>,
    private val onReorder: (Long, Double?, Double?) -> Unit,
) {
    /** 拖动中（以及松手后数据库还没回来时）的本地顺序；null 时直接显示数据库里的顺序。 */
    private var localOrder by mutableStateOf<List<PlaylistTrack>?>(null)
    val order: List<PlaylistTrack> get() = localOrder ?: tracks()

    /** 正在拖的那一条。 */
    var draggingId by mutableStateOf<Long?>(null)
        private set
    private var draggedIndex by mutableStateOf<Int?>(null)
    private var fromIndex = -1
    private var startTop = 0
    private var delta by mutableFloatStateOf(0f)

    /** 刚松手、正从手指处滑回空位的那一条。 */
    var settlingId by mutableStateOf<Long?>(null)
        private set
    private val settle = Animatable(0f)

    /** 数据库的列表变了（写库回来、增删）：不在拖动时就丢掉本地顺序，改显示库里的。 */
    fun onTracksChanged() {
        if (draggingId == null) localOrder = null
    }

    private fun draggedInfo(): LazyListItemInfo? {
        val index = draggedIndex ?: return null
        return listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    }

    /** 第 [id] 条此刻要加的上下位移：拖动中跟手，松手后滑回 0。 */
    fun offsetOf(id: Long): Float = when (id) {
        draggingId -> draggedInfo()?.let { startTop + delta - it.offset } ?: 0f
        settlingId -> settle.value
        else -> 0f
    }

    fun start(id: Long) {
        val list = order
        val index = list.indexOfFirst { it.track.id == id }
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        localOrder = list
        fromIndex = tracks().indexOfFirst { it.track.id == id }
        draggedIndex = index
        startTop = info.offset
        delta = 0f
        settlingId = null
        draggingId = id
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun dragBy(dy: Float) {
        if (draggingId == null) return
        delta += dy
        moveIfCrossed()
    }

    /** 被拖那一行的中线越过了别的行：本地顺序挪过去，轻震一下。 */
    private fun moveIfCrossed() {
        val index = draggedIndex ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        val dragged = visible.firstOrNull { it.index == index } ?: return
        val target = reorderSwapTarget(
            draggedIndex = index,
            draggedTop = startTop + delta,
            draggedSize = dragged.size,
            visible = visible.map { ItemSpan(it.index, it.offset, it.size) },
        ) ?: return
        // 挪动第一屏最上面那一行时，懒加载列表会跟着它滚走：先钉住当前滚动位置（官方拖动排序示例的做法）
        if (index == listState.firstVisibleItemIndex || target == listState.firstVisibleItemIndex) {
            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
        localOrder = order.moved(index, target)
        draggedIndex = target
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    fun end() {
        val id = draggingId ?: return
        val to = draggedIndex
        val offset = offsetOf(id)
        draggingId = null
        draggedIndex = null
        if (to != null && fromIndex >= 0 && to != fromIndex) {
            val (before, after) = reorderNeighbors(tracks().map { it.sortKey }, fromIndex, to)
            onReorder(id, before, after)
        } else {
            localOrder = null
        }
        settlingId = id
        scope.launch {
            settle.snapTo(offset)
            settle.animateTo(0f)
            if (settlingId == id) settlingId = null
        }
    }

    /** 手势被打断：恢复原顺序。 */
    fun cancel() {
        draggingId = null
        draggedIndex = null
        localOrder = null
    }

    /** 拖动期间每帧看一次：被拖那一行进了上下边缘就滚，滚完照样挪格。 */
    suspend fun autoScrollWhileDragging() {
        var last = withFrameNanos { it }
        while (draggingId != null) {
            val now = withFrameNanos { it }
            val seconds = (now - last) / 1_000_000_000f
            last = now
            val dragged = draggedInfo() ?: continue
            val info = listState.layoutInfo
            val top = startTop + delta
            val speed = autoScrollSpeed(
                draggedTop = top,
                draggedBottom = top + dragged.size,
                viewportTop = info.viewportStartOffset.toFloat(),
                viewportBottom = info.viewportEndOffset.toFloat(),
                edgePx = edgePx,
                maxSpeedPx = maxSpeedPx,
            )
            if (speed != 0f) {
                listState.scrollBy(speed * seconds)
                moveIfCrossed()
            }
        }
    }
}

@Composable
internal fun rememberPlaylistReorderState(
    listState: LazyListState,
    tracks: List<PlaylistTrack>,
    onReorder: (Long, Double?, Double?) -> Unit,
): PlaylistReorderState {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentTracks by rememberUpdatedState(tracks)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val state = remember(listState) {
        PlaylistReorderState(
            listState = listState,
            scope = scope,
            haptic = haptic,
            edgePx = with(density) { AUTO_SCROLL_EDGE.toPx() },
            maxSpeedPx = with(density) { AUTO_SCROLL_MAX_SPEED.toPx() },
            tracks = { currentTracks },
            onReorder = { id, before, after -> currentOnReorder(id, before, after) },
        )
    }
    LaunchedEffect(tracks) { state.onTracksChanged() }
    LaunchedEffect(state.draggingId) {
        if (state.draggingId != null) state.autoScrollWhileDragging()
    }
    return state
}

/** 离列表上下边缘多近开始自动滚动。 */
private val AUTO_SCROLL_EDGE = 48.dp

/** 自动滚动最快每秒多少 dp（贴着边缘时）。 */
private val AUTO_SCROLL_MAX_SPEED = 800.dp
