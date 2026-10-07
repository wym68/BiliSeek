package com.biliseek.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 日播放记录页的口径：有按视频的收听明细就用明细（列表与当日时长出自同一份数据），否则回退。
 */
class DayListenPolicyTest {

    private fun h(bvid: String, durationSec: Long = 300) =
        PlayHistoryEntity(
            bvid = bvid, cid = 0, title = bvid, author = "", coverUrl = "",
            durationSec = durationSec, positionMs = 0, playedAt = 0,
        )

    @Test
    fun `有明细时列表就是明细，时长是各行之和`() {
        val view = resolveDayView(
            listened = listOf(DayListenRow(h("BV1a"), 60_000, 2), DayListenRow(h("BV1b"), 30_000, 1)),
            legacyMs = 999_999,
            legacyHistory = listOf(h("BV1z")),
        )
        assertEquals(listOf("BV1a" to 60_000L, "BV1b" to 30_000L), view.rows.map { it.history.bvid to it.listenedMs })
        assertEquals(90_000L, view.totalMs)
    }

    @Test
    fun `没有明细时回到播放历史口径，时长取旧的按天合计`() {
        val view = resolveDayView(listened = emptyList(), legacyMs = 120_000, legacyHistory = listOf(h("BV1z")))
        assertEquals(listOf(DayRow(h("BV1z"), null)), view.rows)
        assertEquals(120_000L, view.totalMs)
    }

    @Test
    fun `两样都没有时显示未统计`() {
        val view = resolveDayView(listened = emptyList(), legacyMs = null, legacyHistory = listOf(h("BV1z")))
        assertNull(view.totalMs)
    }

    @Test
    fun `日合计以明细为准，没有明细的日子取旧合计`() {
        assertEquals(
            mapOf(1L to 50L, 2L to 70L, 3L to 9L),
            mergeDayTotals(
                entries = listOf(DayMs(1, 50), DayMs(2, 70)),
                legacy = listOf(DayMs(2, 999), DayMs(3, 9)),
            ),
        )
    }
}
