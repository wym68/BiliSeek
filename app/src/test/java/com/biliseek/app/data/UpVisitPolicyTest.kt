package com.biliseek.app.data

import com.biliseek.core.network.dto.FollowingUp
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class UpVisitPolicyTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun ms(date: LocalDate, hour: Int = 12): Long =
        date.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

    private fun up(mid: Long, name: String = "UP$mid") =
        FollowingUp(mid = mid, uname = name, face = "https://face/$mid")

    // ── nextVisit ─────────────────────────────────────────

    @Test
    fun `首次访问 count 1`() {
        val today = ms(LocalDate.now())
        val next = UpVisitPolicy.nextVisit(prev = null, mid = 42L, nowMs = today)
        assertEquals(42L, next.mid)
        assertEquals(1L, next.visitCount)
        assertEquals(today, next.lastVisitMs)
    }

    @Test
    fun `同日再次访问 count 不变 只刷新时间`() {
        val today = ms(LocalDate.now(), hour = 9)
        val prev = UpVisitPolicy.nextVisit(null, mid = 1L, nowMs = today)
        val sameDayLater = ms(LocalDate.now(), hour = 22)
        val next = UpVisitPolicy.nextVisit(prev, mid = 1L, nowMs = sameDayLater)
        assertEquals(1L, next.visitCount)
        assertEquals(sameDayLater, next.lastVisitMs)
    }

    @Test
    fun `跨日访问 count 加 1`() {
        val yesterday = ms(LocalDate.now().minusDays(1))
        val prev = UpVisitEntity(mid = 1L, visitCount = 3L, lastVisitMs = yesterday)
        val today = ms(LocalDate.now())
        val next = UpVisitPolicy.nextVisit(prev, mid = 1L, nowMs = today)
        assertEquals(4L, next.visitCount)
        assertEquals(today, next.lastVisitMs)
    }

    // ── mergeUpChips ─────────────────────────────────────

    @Test
    fun `无访问记录时按关注原序截 limit`() {
        val merged = UpVisitPolicy.mergeUpChips(
            followings = listOf(up(1), up(2), up(3), up(4), up(5)),
            visits = emptyList(),
            limit = 3,
        )
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.mid })
    }

    @Test
    fun `有访问记录的排前面 按次数降序`() {
        val today = ms(LocalDate.now())
        val merged = UpVisitPolicy.mergeUpChips(
            followings = listOf(up(1), up(2), up(3), up(4)),
            visits = listOf(
                UpVisitEntity(1L, 5L, today),
                UpVisitEntity(3L, 8L, today),
                UpVisitEntity(2L, 5L, today - 1),
            ),
            limit = 10,
        )
        assertEquals(listOf(3L, 1L, 2L, 4L), merged.map { it.mid })
    }

    @Test
    fun `同次数时按最近访问优先`() {
        val today = ms(LocalDate.now())
        val older = today - 60_000
        val merged = UpVisitPolicy.mergeUpChips(
            followings = listOf(up(1), up(2), up(3)),
            visits = listOf(
                UpVisitEntity(1L, 5L, older),
                UpVisitEntity(2L, 5L, today),
            ),
            limit = 10,
        )
        assertEquals(listOf(2L, 1L, 3L), merged.map { it.mid })
    }

    @Test
    fun `不关注的访问记录被忽略 不参与展示`() {
        val today = ms(LocalDate.now())
        val merged = UpVisitPolicy.mergeUpChips(
            followings = listOf(up(1), up(2)),
            visits = listOf(
                UpVisitEntity(999L, 100L, today),
            ),
            limit = 10,
        )
        assertEquals(listOf(1L, 2L), merged.map { it.mid })
    }

    @Test
    fun `limit 生效 截断到指定数量`() {
        val today = ms(LocalDate.now())
        val merged = UpVisitPolicy.mergeUpChips(
            followings = listOf(up(1), up(2), up(3), up(4), up(5)),
            visits = listOf(UpVisitEntity(1L, 9L, today)),
            limit = 2,
        )
        assertEquals(listOf(1L, 2L), merged.map { it.mid })
    }
}