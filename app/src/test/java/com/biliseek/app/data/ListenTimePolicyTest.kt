package com.biliseek.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 真实收听时长：只在「正在播放」时计时，按本地日期累计（跨零点的一段分到两天）。
 */
class ListenTimePolicyTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 4)
    private fun wallAt(hour: Int, minute: Int, second: Int) =
        LocalDateTime.of(day, java.time.LocalTime.of(hour, minute, second)).atZone(zone).toInstant().toEpochMilli()

    // ── 按本地日期切段 ───────────────────────────

    @Test
    fun `同一天里的一段整段记到这一天`() {
        assertEquals(
            listOf(DaySlice(day.toEpochDay(), 65_000)),
            splitByLocalDay(wallAt(10, 0, 0), 65_000, zone),
        )
    }

    @Test
    fun `跨零点的一段按毫秒分到两天`() {
        assertEquals(
            listOf(DaySlice(day.toEpochDay(), 10_000), DaySlice(day.plusDays(1).toEpochDay(), 10_000)),
            splitByLocalDay(wallAt(23, 59, 50), 20_000, zone),
        )
    }

    @Test
    fun `时长为零或负数什么都不记`() {
        assertTrue(splitByLocalDay(wallAt(10, 0, 0), 0, zone).isEmpty())
        assertTrue(splitByLocalDay(wallAt(10, 0, 0), -5_000, zone).isEmpty())
    }

    // ── 计时状态机 ───────────────────────────────

    @Test
    fun `播放 65 秒后停下记 65 秒`() {
        val clock = ListenClock(zone)
        clock.start(elapsedMs = 1_000, wallMs = wallAt(10, 0, 0))
        assertEquals(listOf(DaySlice(day.toEpochDay(), 65_000)), clock.stop(elapsedMs = 66_000))
    }

    @Test
    fun `播放中定时结算只记上次结算之后的部分，合起来不多不少`() {
        val clock = ListenClock(zone)
        clock.start(0, wallAt(10, 0, 0))
        val total = clock.tick(30_000, wallAt(10, 0, 30)) +
            clock.tick(60_000, wallAt(10, 1, 0)) +
            clock.stop(65_000)
        assertEquals(listOf(30_000L, 30_000L, 5_000L), total.map { it.ms })
    }

    @Test
    fun `暂停的时间不算`() {
        val clock = ListenClock(zone)
        clock.start(0, wallAt(10, 0, 0))
        val first = clock.stop(10_000)
        clock.start(20_000, wallAt(10, 0, 20))
        val second = clock.stop(30_000)
        assertEquals(listOf(10_000L, 10_000L), (first + second).map { it.ms })
    }

    @Test
    fun `没在播时结算或停下什么都不记`() {
        val clock = ListenClock(zone)
        assertTrue(clock.tick(30_000, wallAt(10, 0, 30)).isEmpty())
        assertTrue(clock.stop(30_000).isEmpty())
    }

    @Test
    fun `已经在计时再开始不重置起点`() {
        val clock = ListenClock(zone)
        clock.start(0, wallAt(10, 0, 0))
        clock.start(10_000, wallAt(10, 0, 10))
        assertEquals(listOf(20_000L), clock.stop(20_000).map { it.ms })
    }

    @Test
    fun `播放跨过零点时分到两天`() {
        val clock = ListenClock(zone)
        clock.start(0, wallAt(23, 59, 50))
        assertEquals(
            listOf(DaySlice(day.toEpochDay(), 10_000), DaySlice(day.plusDays(1).toEpochDay(), 10_000)),
            clock.stop(20_000),
        )
    }
}
