package com.biliseek.app.data

import java.time.Instant
import java.time.ZoneId

// 真实收听时长：只在「正在播放」时计时（暂停、缓冲不算，倍速按实际过去的时间算），按视频、按本地日期累计。
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

/** 收听计时归到哪个视频：与 `play_history` 同口径的 (bvid, cid)。 */
data class ListenKey(val bvid: String, val cid: Long)

/** [key] 这个视频在 [epochDay] 这天听了 [ms] 毫秒，这一段在墙钟 [endWallMs] 结束。 */
data class ListenSlice(val key: ListenKey, val epochDay: Long, val ms: Long, val endWallMs: Long)

/**
 * 收听计时的状态机（不碰协程和时钟，时间都由调用方传进来）。每一段都记在当时在播的视频（[ListenKey]）名下。
 *
 * 时长用单调时钟（开机时长，不受改系统时间影响）量，归到哪天用墙钟。
 * 播放中宿主定时调 [tick] 结算一次（进程被杀最多丢一个间隔），停下时调 [stop] 结算剩下的一段；
 * 播放中换了视频，[start] 先把前一段结算给旧视频。
 */
class ListenClock(private val zone: ZoneId) {
    private var key: ListenKey? = null
    private var startElapsedMs = 0L
    private var startWallMs = 0L

    /** 开始给 [key] 计时；同一视频已在计时不重置起点；正在给别的视频计时就先结算那一段并返回。 */
    fun start(key: ListenKey, elapsedMs: Long, wallMs: Long): List<ListenSlice> {
        if (this.key == key) return emptyList()
        val settled = settle(elapsedMs)
        this.key = key
        startElapsedMs = elapsedMs
        startWallMs = wallMs
        return settled
    }

    /** 播放中结算到现在，并从现在重新起一段。没在计时返回空。 */
    fun tick(elapsedMs: Long, wallMs: Long): List<ListenSlice> {
        val slices = settle(elapsedMs)
        if (key != null) {
            startElapsedMs = elapsedMs
            startWallMs = wallMs
        }
        return slices
    }

    /** 停下：结算这一段，停止计时。没在计时返回空。 */
    fun stop(elapsedMs: Long): List<ListenSlice> = settle(elapsedMs).also { key = null }

    private fun settle(elapsedMs: Long): List<ListenSlice> {
        val current = key ?: return emptyList()
        var end = startWallMs
        return splitByLocalDay(startWallMs, elapsedMs - startElapsedMs, zone).map {
            end += it.ms
            ListenSlice(current, it.epochDay, it.ms, end)
        }
    }
}
