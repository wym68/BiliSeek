package com.biliseek.app.danmaku

/**
 * 弹幕轨道分配。纯函数，单位：像素 / 视频毫秒。
 *
 * 滚动弹幕：从右边缘进、左边缘出，屏上停留时长固定为 [scrollDurationMs]，
 * 所以越长的弹幕跑得越快。同一轨道上放新弹幕要满足两条：
 * 1. 前一条的尾巴已经完全进屏并留出 [gap]（不重叠）；
 * 2. 新的一条（可能更快）在前一条离屏之前追不上它（不追尾）。
 *
 * 顶部 / 底部弹幕：居中不动，停留 [fixedDurationMs]，轨道空出来才能放。
 */

/** 已占用轨道的最后一条弹幕。 */
data class TrackOccupant(val startMs: Long, val widthPx: Float)

/** 滚动弹幕在某一时刻的横坐标（左边缘）。 */
fun scrollX(nowMs: Long, startMs: Long, widthPx: Float, screenWidth: Float, scrollDurationMs: Long): Float {
    val progress = (nowMs - startMs).toFloat() / scrollDurationMs
    return screenWidth - progress * (screenWidth + widthPx)
}

/** 新的滚动弹幕能否跟在 [previous] 后面放进同一轨道。 */
fun canFollowScroll(
    previous: TrackOccupant?,
    startMs: Long,
    widthPx: Float,
    screenWidth: Float,
    scrollDurationMs: Long,
    gap: Float,
): Boolean {
    previous ?: return true
    val prevX = scrollX(startMs, previous.startMs, previous.widthPx, screenWidth, scrollDurationMs)
    // 1. 前一条尾巴要已经进屏并留出间距
    if (prevX + previous.widthPx > screenWidth - gap) return false
    // 2. 追尾：新的一条头部到达左边缘时，前一条必须已经完全离屏
    val prevSpeed = (screenWidth + previous.widthPx) / scrollDurationMs
    val newSpeed = (screenWidth + widthPx) / scrollDurationMs
    if (newSpeed <= prevSpeed) return true
    val prevExitMs = previous.startMs + scrollDurationMs
    val newHeadAtLeftMs = startMs + (screenWidth / newSpeed).toLong()
    return newHeadAtLeftMs >= prevExitMs
}

/** 固定弹幕的轨道是否已空出。 */
fun canFollowFixed(previous: TrackOccupant?, startMs: Long, fixedDurationMs: Long): Boolean =
    previous == null || startMs >= previous.startMs + fixedDurationMs

/**
 * 给滚动弹幕挑轨道：从上往下第一条能放的。都放不下返回 null（这条弹幕丢弃——
 * 堆叠着画出来只会糊成一片）。
 */
fun pickScrollTrack(
    tracks: List<TrackOccupant?>,
    startMs: Long,
    widthPx: Float,
    screenWidth: Float,
    scrollDurationMs: Long,
    gap: Float,
): Int? = tracks.indices.firstOrNull {
    canFollowScroll(tracks[it], startMs, widthPx, screenWidth, scrollDurationMs, gap)
}

/** 给固定弹幕挑轨道：顶部从上往下、底部从下往上找第一条空的。 */
fun pickFixedTrack(tracks: List<TrackOccupant?>, startMs: Long, fixedDurationMs: Long): Int? =
    tracks.indices.firstOrNull { canFollowFixed(tracks[it], startMs, fixedDurationMs) }

/** 显示区域能放几条轨道。至少 1 条。 */
fun danmakuTrackCount(areaHeightPx: Float, lineHeightPx: Float): Int =
    if (lineHeightPx <= 0f) 1 else (areaHeightPx / lineHeightPx).toInt().coerceAtLeast(1)
