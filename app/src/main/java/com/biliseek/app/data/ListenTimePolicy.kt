package com.biliseek.app.data

import java.time.Instant
import java.time.ZoneId

// 真实收听时长：只在「正在播放」时计时（暂停、缓冲不算，倍速按实际过去的时间算），按本地日期累计。
// 早先日播放记录页的「当日听歌时长」是把当天播过的条目的**完整时长**相加，不是真的听了多久。

/** 某天（[epochDay] = `LocalDate.toEpochDay()`）累计的 [ms] 毫秒收听时长。 */
data class DaySlice(val epochDay: Long, val ms: Long)

/** 把从墙钟 [startWallMs] 起、长 [durationMs] 的一段播放按本地日期切开：跨零点的分到两天。 */
fun splitByLocalDay(startWallMs: Long, durationMs: Long, zone: ZoneId): List<DaySlice> {
    if (durationMs <= 0) return emptyList()
    val end = startWallMs + durationMs
    val slices = mutableListOf<DaySlice>()
    var cursor = startWallMs
    while (cursor < end) {
        val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
        val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sliceEnd = minOf(end, nextMidnight)
        slices += DaySlice(date.toEpochDay(), sliceEnd - cursor)
        cursor = sliceEnd
    }
    return slices
}

/**
 * 收听计时的状态机（不碰协程和时钟，时间都由调用方传进来）。
 *
 * 时长用单调时钟（开机时长，不受改系统时间影响）量，归到哪天用墙钟。
 * 播放中宿主定时调 [tick] 结算一次（进程被杀最多丢一个间隔），停下时调 [stop] 结算剩下的一段。
 */
class ListenClock(private val zone: ZoneId) {
    private var startElapsedMs: Long? = null
    private var startWallMs = 0L

    /** 开始播放；已经在计时就不重置起点。 */
    fun start(elapsedMs: Long, wallMs: Long) {
        if (startElapsedMs != null) return
        startElapsedMs = elapsedMs
        startWallMs = wallMs
    }

    /** 播放中结算到现在，并从现在重新起一段。没在计时返回空。 */
    fun tick(elapsedMs: Long, wallMs: Long): List<DaySlice> {
        val slices = settle(elapsedMs)
        if (startElapsedMs != null) {
            startElapsedMs = elapsedMs
            startWallMs = wallMs
        }
        return slices
    }

    /** 停下：结算这一段，停止计时。没在计时返回空。 */
    fun stop(elapsedMs: Long): List<DaySlice> = settle(elapsedMs).also { startElapsedMs = null }

    private fun settle(elapsedMs: Long): List<DaySlice> {
        val start = startElapsedMs ?: return emptyList()
        return splitByLocalDay(startWallMs, elapsedMs - start, zone)
    }
}
