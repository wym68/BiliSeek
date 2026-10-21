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
import com.biliseek.app.data.autoScrollSpeed
import com.biliseek.app.data.moved
import com.biliseek.app.data.reorderSwapTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 长按拖动排序（本地列表、设置 → 首页快捷入口共用；设计稿：docs/superpowers/specs/2026-10-04-playlist-reorder-design.md）。
 *
 * - 长按满时间：那一行拿起来，震一下；
 * - 拖动：被拖那一行跟着手指走，它的中线越过别的行时本地顺序挪一格，别的行滑开让位，每挪一格轻震一下；
 *   拖到列表上下边缘自动滚动；
 * - 松手：这一行从手指处滑进空位，回调一次「最初位置 → 最终位置」（本地列表据此算两侧 sortKey 写库）。
 *
 * 几何都按 [LazyListState.layoutInfo] 实测，被拖那一行按下标找（挪格后、重新布局前，新下标处就是它要落的格子）。
 * 手势回调读的都是最新的列表与回调，不会留着旧下标。
 */
@Stable
internal class DragReorderState<T>(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val haptic: HapticFeedback,
    private val edgePx: Float,
    private val maxSpeedPx: Float,
    private val items: () -> List<T>,
    private val keyOf: (T) -> Any,
    private val onMove: (key: Any, from: Int, to: Int) -> Unit,
) {
    /** 拖动中（以及松手后新顺序还没回来时）的本地顺序；null 时直接显示 [items]。 */
    private var localOrder by mutableStateOf<List<T>?>(null)
    val order: List<T> get() = localOrder ?: items()

    /** 正在拖的那一条的 key。 */
    var draggingId by mutableStateOf<Any?>(null)
        private set
    private var draggedIndex by mutableStateOf<Int?>(null)
    private var fromIndex = -1
    private var startTop = 0
    private var delta by mutableFloatStateOf(0f)

    /** 刚松手、正从手指处滑回空位的那一条。 */
    var settlingId by mutableStateOf<Any?>(null)
        private set
    private val settle = Animatable(0f)

    /** 列表变了（写库 / 存设置回来、增删）：不在拖动时就丢掉本地顺序，改显示 [items]。 */
    fun onItemsChanged() {
        if (draggingId == null) localOrder = null
    }

    private fun draggedInfo(): LazyListItemInfo? {
        val index = draggedIndex ?: return null
        return listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    }

    /** key 为 [key] 的那条此刻要加的上下位移：拖动中跟手，松手后滑回 0。 */
    fun offsetOf(key: Any): Float = when (key) {
        draggingId -> draggedInfo()?.let { startTop + delta - it.offset } ?: 0f
        settlingId -> settle.value
        else -> 0f
    }

    fun start(key: Any) {
        val list = order
        val index = list.indexOfFirst { keyOf(it) == key }
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        localOrder = list
        fromIndex = items().indexOfFirst { keyOf(it) == key }
        draggedIndex = index
        startTop = info.offset
        delta = 0f
        settlingId = null
        draggingId = key
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
            onMove(id, fromIndex, to)
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
internal fun <T> rememberDragReorderState(
    listState: LazyListState,
    items: List<T>,
    keyOf: (T) -> Any,
    onMove: (key: Any, from: Int, to: Int) -> Unit,
): DragReorderState<T> {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentItems by rememberUpdatedState(items)
    val currentKeyOf by rememberUpdatedState(keyOf)
    val currentOnMove by rememberUpdatedState(onMove)
    val state = remember(listState) {
        DragReorderState(
            listState = listState,
            scope = scope,
            haptic = haptic,
            edgePx = with(density) { AUTO_SCROLL_EDGE.toPx() },
            maxSpeedPx = with(density) { AUTO_SCROLL_MAX_SPEED.toPx() },
            items = { currentItems },
            keyOf = { currentKeyOf(it) },
            onMove = { key, from, to -> currentOnMove(key, from, to) },
        )
    }
    LaunchedEffect(items) { state.onItemsChanged() }
    LaunchedEffect(state.draggingId) {
        if (state.draggingId != null) state.autoScrollWhileDragging()
    }
    return state
}

/** 离列表上下边缘多近开始自动滚动。 */
private val AUTO_SCROLL_EDGE = 48.dp

/** 自动滚动最快每秒多少 dp（贴着边缘时）。 */
private val AUTO_SCROLL_MAX_SPEED = 800.dp
