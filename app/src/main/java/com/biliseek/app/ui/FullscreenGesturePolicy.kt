package com.biliseek.app.ui

import kotlin.math.abs

/**
 * 全屏手势的判定与换算。纯函数，单位一律是像素 / 毫秒。
 *
 * 拖动方向在越过 touch slop 后判定一次、整个手势内不再改变——
 * 斜着拖时画面不能一会儿调进度一会儿调音量。
 */

/** 一次拖动被判定成的手势。 */
enum class DragGesture {
    /** 横向：调进度。 */
    SEEK,

    /** 左半屏纵向：调亮度。 */
    BRIGHTNESS,

    /** 右半屏纵向：调音量。 */
    VOLUME,

    /** 起点落在系统手势区：让给系统（侧边返回 / 边缘唤出系统栏）。 */
    NONE,
}

/**
 * 判定拖动手势。位移还没越过 [touchSlop] 时返回 null（尚未决定）。
 *
 * 起点落在左右 [edgeExclusion] 内让给系统返回手势；纵向手势的起点落在上下
 * [edgeExclusion] 内让给系统栏（沉浸模式下从边缘滑出系统栏）。
 */
fun resolveDragGesture(
    startX: Float,
    startY: Float,
    width: Float,
    height: Float,
    totalDx: Float,
    totalDy: Float,
    touchSlop: Float,
    edgeExclusion: Float,
): DragGesture? {
    if (startX < edgeExclusion || startX > width - edgeExclusion) return DragGesture.NONE
    if (abs(totalDx) < touchSlop && abs(totalDy) < touchSlop) return null
    if (abs(totalDx) > abs(totalDy)) return DragGesture.SEEK
    if (startY < edgeExclusion || startY > height - edgeExclusion) return DragGesture.NONE
    return if (startX < width / 2) DragGesture.BRIGHTNESS else DragGesture.VOLUME
}

/**
 * 横向拖动的目标进度。
 *
 * 拖满一整屏宽 = [SEEK_FULL_WIDTH_MS]（短视频则是整段时长）：
 * 按整段时长映射的话，一小时的视频手指一抖就是好几分钟，没法精调。
 */
fun resolveSeekTarget(startPositionMs: Long, totalDx: Float, width: Float, durationMs: Long): Long {
    if (width <= 0f || durationMs <= 0L) return startPositionMs
    val span = minOf(durationMs, SEEK_FULL_WIDTH_MS)
    val delta = (totalDx / width * span).toLong()
    return (startPositionMs + delta).coerceIn(0L, durationMs)
}

/** 纵向拖动后的亮度 / 音量（0..1）。上滑变大，拖满一整屏高 = 从 0 到 1。 */
fun resolveLevel(startLevel: Float, totalDy: Float, height: Float): Float {
    if (height <= 0f) return startLevel.coerceIn(0f, 1f)
    return (startLevel - totalDy / height).coerceIn(0f, 1f)
}

/**
 * 长按临时倍速的目标速度。返回 null 表示不加速（当前已经不慢于目标）。
 *
 * 正在放无损时只加到 1.5×：再往上会触发「无损 → AAC」的重新取流，
 * 按下、松开各缓冲一次，长按加速就成了卡两下。
 */
fun resolveLongPressSpeed(currentSpeed: Float, losslessActive: Boolean): Float? {
    val target = if (losslessActive) LONG_PRESS_SPEED_LOSSLESS else LONG_PRESS_SPEED
    return if (currentSpeed >= target) null else target
}

/** 横向拖满一整屏宽对应的时长。 */
const val SEEK_FULL_WIDTH_MS = 120_000L

private const val LONG_PRESS_SPEED = 2.0f
private const val LONG_PRESS_SPEED_LOSSLESS = 1.5f
