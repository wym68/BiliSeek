package com.biliseek.app.ui

import kotlin.math.roundToLong

/**
 * 迷你条拖进度：手指每挪 [SCRUB_STEP_DP] 前进 / 后退 [SCRUB_STEP_MS]，和视频长短无关。
 * 手指位置对应整条时间线时，15 分钟的视频每 1dp 就差近 3 秒，很难拖准（设计稿：2026-10-04-minibar-progress）。
 */
const val SCRUB_STEP_MS = 5_000L
const val SCRUB_STEP_DP = 8f

/** 拖动距离（dp，往右为正）折算成格数：不满一格不算，向零取整。 */
fun scrubSteps(dragDp: Float): Int = (dragDp / SCRUB_STEP_DP).toInt()

/** 拖到第 [steps] 格时的目标位置：从按下时的位置 [startMs] 算起，夹在 0 到总长之间。 */
fun scrubTarget(startMs: Long, steps: Int, durationMs: Long): Long =
    (startMs + steps * SCRUB_STEP_MS).coerceIn(0L, durationMs.coerceAtLeast(0L))

/** 迷你条上竖着滑的结果。 */
enum class MiniBarSwipe { EXPAND, COLLAPSE }

/** 竖滑累计距离（dp，往下为正）：往上超过阈值打开播放页，往下超过阈值收起迷你条，不到阈值不算。 */
fun resolveMiniBarSwipe(totalDragDp: Float): MiniBarSwipe? = when {
    totalDragDp <= -SWIPE_THRESHOLD_DP -> MiniBarSwipe.EXPAND
    totalDragDp >= SWIPE_THRESHOLD_DP -> MiniBarSwipe.COLLAPSE
    else -> null
}

private const val SWIPE_THRESHOLD_DP = 16f

// ── 长按放大成刻度尺（设计稿：docs/superpowers/specs/2026-10-04-minibar-zoom-scrub-design.md）──
// 按住处先定位到整条线上对应的时间；之后整条刻度尺只代表 [ZOOM_WINDOW_MS]，手指在哪就选哪，停在 5 秒的格上。

/** 放大后整条刻度尺代表的时长：3 分钟，一格 5 秒约 8dp，和左右拖的手感一样。 */
const val ZOOM_WINDOW_MS = 180_000L

/** 对齐到最近的 5 秒（[SCRUB_STEP_MS]），夹在 0 到总长；总长不是 5 秒整数倍时只取总长以内的格。 */
fun snapToStep(ms: Long, durationMs: Long): Long {
    val duration = durationMs.coerceAtLeast(0L)
    val snapped = (ms.coerceIn(0L, duration) + SCRUB_STEP_MS / 2) / SCRUB_STEP_MS * SCRUB_STEP_MS
    return if (snapped > duration) snapped - SCRUB_STEP_MS else snapped
}

/** 刻度尺代表的时长：[ZOOM_WINDOW_MS] 与总长取小（短视频就是整个视频）。 */
fun zoomWindowMs(durationMs: Long): Long = minOf(ZOOM_WINDOW_MS, durationMs.coerceAtLeast(0L))

/** 手指横坐标在进度线上的比例：扣掉左右缩进 [insetPx]，夹在 0 到 1。 */
fun barFraction(xPx: Float, widthPx: Float, insetPx: Float): Float {
    val span = widthPx - 2 * insetPx
    if (span <= 0f) return 0f
    return ((xPx - insetPx) / span).coerceIn(0f, 1f)
}

/** 按住处（进度线上 [fraction] 处）对应的时间，对齐到 5 秒。 */
fun zoomPressMs(fraction: Float, durationMs: Long): Long =
    snapToStep((fraction.coerceIn(0f, 1f) * durationMs).roundToLong(), durationMs)

/** 刻度尺左端的时间：让按住处的时间 [pressMs] 正好落在手指下面（[pressFraction] 处）；可能小于 0。 */
fun zoomStartMs(pressMs: Long, pressFraction: Float, windowMs: Long): Long =
    pressMs - (pressFraction.coerceIn(0f, 1f) * windowMs).roundToLong()

/** 手指在刻度尺 [fraction] 处选中的时间：对齐到 5 秒；手指超出两端就停在两端。 */
fun zoomTargetMs(startMs: Long, fraction: Float, windowMs: Long, durationMs: Long): Long =
    snapToStep(startMs + (fraction.coerceIn(0f, 1f) * windowMs).roundToLong(), durationMs)

/** 刻度的三级：每 5 秒一小格、30 秒中格、整分钟大格（下面标时间）。 */
enum class TickLevel { STEP, HALF_MINUTE, MINUTE }

data class ZoomTick(val ms: Long, val level: TickLevel)

/** 刻度尺（左端 [startMs]、代表 [windowMs]）上要画的格：5 秒整数倍，超出 0 到总长的不画。 */
fun zoomTicks(startMs: Long, windowMs: Long, durationMs: Long): List<ZoomTick> {
    val from = startMs.coerceAtLeast(0L)
    val to = minOf(startMs + windowMs, durationMs)
    if (to < from) return emptyList()
    val first = (from + SCRUB_STEP_MS - 1) / SCRUB_STEP_MS * SCRUB_STEP_MS
    return (first..to step SCRUB_STEP_MS).map { ms ->
        val level = when {
            ms % 60_000L == 0L -> TickLevel.MINUTE
            ms % 30_000L == 0L -> TickLevel.HALF_MINUTE
            else -> TickLevel.STEP
        }
        ZoomTick(ms, level)
    }
}
