package com.biliseek.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.biliseek.app.ui.TickLevel
import com.biliseek.app.ui.barFraction
import com.biliseek.app.ui.scrubSteps
import com.biliseek.app.ui.scrubTarget
import com.biliseek.app.ui.zoomPressMs
import com.biliseek.app.ui.zoomStartMs
import com.biliseek.app.ui.zoomTargetMs
import com.biliseek.app.ui.zoomTicks
import com.biliseek.app.ui.zoomWindowMs
import com.biliseek.core.player.formatDuration

/**
 * 迷你条顶部的进度区（设计稿：docs/superpowers/specs/2026-10-04-minibar-progress-design.md、
 * 2026-10-04-minibar-zoom-scrub-design.md）。
 *
 * - 24dp 高、整条宽的触摸区，中间画 3dp 细线：离顶约 9.5dp，左右缩进 16dp；已播主色，未播主色 16%。
 * - 直接左右拖：从按下时的位置算起，每挪 [com.biliseek.app.ui.SCRUB_STEP_DP] 前进 / 后退 5 秒，每变一格震一下。
 * - 按住约半秒不动：震一下，按住处先定位到整条线上对应的时间，上方浮出刻度尺（整条只代表 3 分钟，每格 5 秒），
 *   手指在刻度尺上的位置就是选中的时间，每换一格震一下。
 * - 两种都是松手才 [onSeek]，没变不跳；轻点不跳。拖动中线加粗到 5dp，目标位置出现 12dp 圆点，
 *   [onScrub] 把目标时间交给宿主显示（松手 / 取消后为 null）。
 * - 竖着滑不在这里处理，交给外层（迷你条：往上打开播放页、往下收起）；刻度尺出来以后竖着动也不交出去。
 */
@Composable
fun DraggableProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (Long?) -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val currentPosition by rememberUpdatedState(positionMs)
    val currentDuration by rememberUpdatedState(durationMs)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnScrub by rememberUpdatedState(onScrub)
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    // 按住后浮出的刻度尺；为空 = 没在放大
    var ruler by remember { mutableStateOf<ZoomRuler?>(null) }
    var widthPx by remember { mutableIntStateOf(0) }

    val primary = MaterialTheme.colorScheme.primary
    val shownMs = scrubMs ?: positionMs
    val fraction = if (durationMs > 0) (shownMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Box(modifier.fillMaxWidth().height(24.dp).onSizeChanged { widthPx = it.width }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "播放进度"
                    progressBarRangeInfo = ProgressBarRangeInfo(shownMs.toFloat(), 0f..durationMs.coerceAtLeast(1L).toFloat())
                    if (durationMs > 0) {
                        setProgress { value ->
                            currentOnSeek(value.toLong().coerceIn(0L, durationMs))
                            true
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (currentDuration <= 0) return@awaitEachGesture
                        // 长按时间内：横着拖过阈值 → 5 秒一格；抬起或被外层的竖滑拿走 → 不管；一直按着不动 → 放大
                        var dragStart: PointerInputChange? = null
                        var overSlop = 0f
                        var settled = false
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            dragStart = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                change.consume()
                                overSlop = over
                            }
                            settled = true
                        }
                        val start = dragStart
                        if (start != null) {
                            val startMs = currentPosition
                            var dragPx = overSlop
                            var steps = 0
                            var completed = false
                            scrubMs = startMs
                            currentOnScrub(startMs)
                            fun applyDrag() {
                                val newSteps = scrubSteps(dragPx.toDp().value)
                                if (newSteps == steps) return
                                steps = newSteps
                                val target = scrubTarget(startMs, newSteps, currentDuration)
                                // 到头之后格数还在变、位置不变：不再震
                                if (target != scrubMs) {
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    scrubMs = target
                                    currentOnScrub(target)
                                }
                            }
                            try {
                                applyDrag()
                                completed = horizontalDrag(start.id) { change ->
                                    dragPx += change.positionChange().x
                                    change.consume()
                                    applyDrag()
                                }
                            } finally {
                                val target = scrubMs
                                scrubMs = null
                                currentOnScrub(null)
                                if (completed && target != null && target != startMs) currentOnSeek(target)
                            }
                        } else if (!settled) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val duration = currentDuration
                            val insetPx = BAR_INSET.toPx()
                            val width = size.width.toFloat()
                            val window = zoomWindowMs(duration)
                            val pressFraction = barFraction(down.position.x, width, insetPx)
                            val pressMs = zoomPressMs(pressFraction, duration)
                            val zoom = ZoomRuler(zoomStartMs(pressMs, pressFraction, window), window)
                            val startPosition = currentPosition
                            var completed = false
                            ruler = zoom
                            scrubMs = pressMs
                            currentOnScrub(pressMs)
                            try {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        completed = true
                                        break
                                    }
                                    // 全部吃掉：放大时竖着动也不打开 / 收起迷你条
                                    change.consume()
                                    val target = zoomTargetMs(
                                        zoom.startMs,
                                        barFraction(change.position.x, width, insetPx),
                                        zoom.windowMs,
                                        duration,
                                    )
                                    if (target != scrubMs) {
                                        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                        scrubMs = target
                                        currentOnScrub(target)
                                    }
                                }
                            } finally {
                                val target = scrubMs
                                ruler = null
                                scrubMs = null
                                currentOnScrub(null)
                                if (completed && target != null && target != startPosition) currentOnSeek(target)
                            }
                        }
                    }
                },
        ) {
            val scrubbing = scrubMs != null
            val thick = (if (scrubbing) 5.dp else 3.dp).toPx()
            val y = 11.dp.toPx()
            // 圆头画在端点外半个线宽，端点往里收半个线宽，整条正好落在左右 16dp 的缩进里
            val x0 = BAR_INSET.toPx() + thick / 2
            val x1 = size.width - BAR_INSET.toPx() - thick / 2
            val xp = x0 + (x1 - x0) * fraction
            drawLine(primary.copy(alpha = 0.16f), Offset(x0, y), Offset(x1, y), thick, StrokeCap.Round)
            if (fraction > 0f) drawLine(primary, Offset(x0, y), Offset(xp, y), thick, StrokeCap.Round)
            if (scrubbing) drawCircle(primary, radius = 6.dp.toPx(), center = Offset(xp, y))
        }

        val zoom = ruler
        if (zoom != null && widthPx > 0) {
            val gapPx = with(LocalDensity.current) { RULER_GAP.roundToPx() }
            Popup(popupPositionProvider = remember(gapPx) { AboveAnchor(gapPx) }) {
                ZoomRulerPanel(
                    ruler = zoom,
                    targetMs = scrubMs ?: positionMs,
                    durationMs = durationMs,
                    widthPx = widthPx,
                )
            }
        }
    }
}

/** 长按后的刻度尺：左端的时间、整条代表的时长（见 MiniBarPolicy 的 zoom* 函数）。 */
private data class ZoomRuler(val startMs: Long, val windowMs: Long)

/** 浮层放在进度区（迷你条顶边）正上方 [gapPx] 处，左边对齐。 */
private class AboveAnchor(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(anchorBounds.left, anchorBounds.top - gapPx - popupContentSize.height)
}

/**
 * 刻度尺浮层：宽同迷你条，左右端和进度线对齐。每 5 秒一小格、30 秒中格、整分钟大格（下面标时间）；
 * 主色指针在选中的格上，上方大字是选中的时间。底色同 [AppToast]：浅色最白一档，深色高一档。
 */
@Composable
private fun ZoomRulerPanel(ruler: ZoomRuler, targetMs: Long, durationMs: Long, widthPx: Int) {
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.surface.luminance() < 0.5f
    val textMeasurer = rememberTextMeasurer()
    val timeStyle = MaterialTheme.typography.titleMedium.copy(
        color = scheme.primary,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant, fontSize = 10.sp)
    val width = with(LocalDensity.current) { widthPx.toDp() }
    Surface(
        modifier = Modifier.width(width).height(RULER_HEIGHT),
        shape = RoundedCornerShape(16.dp),
        color = if (isDark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest,
        shadowElevation = 6.dp,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = BAR_INSET.toPx()
            val span = size.width - 2 * inset
            fun xOf(ms: Long) = inset + (ms - ruler.startMs).toFloat() / ruler.windowMs * span
            val baseY = 56.dp.toPx()
            drawLine(scheme.outlineVariant, Offset(inset, baseY), Offset(size.width - inset, baseY), 1.dp.toPx())
            zoomTicks(ruler.startMs, ruler.windowMs, durationMs).forEach { tick ->
                val x = xOf(tick.ms)
                val (height, color, stroke) = when (tick.level) {
                    TickLevel.STEP -> Triple(5.dp, scheme.outline, 1.dp)
                    TickLevel.HALF_MINUTE -> Triple(9.dp, scheme.onSurfaceVariant, 1.dp)
                    TickLevel.MINUTE -> Triple(14.dp, scheme.onSurface, 1.5.dp)
                }
                drawLine(color, Offset(x, baseY - height.toPx()), Offset(x, baseY), stroke.toPx())
                if (tick.level == TickLevel.MINUTE) {
                    val label = textMeasurer.measure(formatDuration(tick.ms), labelStyle)
                    val lx = (x - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
                    drawText(label, topLeft = Offset(lx, baseY + 3.dp.toPx()))
                }
            }
            val xt = xOf(targetMs)
            drawLine(scheme.primary, Offset(xt, 28.dp.toPx()), Offset(xt, baseY + 2.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
            val time = textMeasurer.measure(formatDuration(targetMs), timeStyle)
            val edge = 6.dp.toPx()
            val tx = (xt - time.size.width / 2f).coerceIn(edge, size.width - edge - time.size.width)
            drawText(time, topLeft = Offset(tx, 3.dp.toPx()))
        }
    }
}

/** 进度线、刻度尺左右的缩进（对齐封面左边与列表键右边）。 */
private val BAR_INSET = 16.dp

/** 刻度尺浮层的高度、离迷你条顶边的距离。 */
private val RULER_HEIGHT = 84.dp
private val RULER_GAP = 12.dp
