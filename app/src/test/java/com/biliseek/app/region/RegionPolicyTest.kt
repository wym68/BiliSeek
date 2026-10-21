package com.biliseek.app.region

import com.biliseek.core.network.ChartItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RegionPolicyTest {

    private val today = LocalDate.of(2026, 10, 21)
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun item(bvid: String, sec: Long) = ChartItem(
        bvid = bvid, title = bvid, coverUrl = "", author = "", durationSec = sec, playCount = null, rank = null,
    )

    @Test
    fun `清单_5大类22个子分区_顺序与tid`() {
        assertEquals(22, RegionCatalog.all.size)
        assertEquals(
            listOf(28, 31, 59, 30, 29, 243, 267, 130),
            RegionCatalog.inGroup(RegionGroup.MUSIC).map { it.tid },
        )
        assertEquals(listOf(228, 124, 201, 207, 209, 208), RegionCatalog.inGroup(RegionGroup.KNOWLEDGE).map { it.tid })
        assertEquals(listOf(37, 178, 180), RegionCatalog.inGroup(RegionGroup.DOCUMENTARY).map { it.tid })
        assertEquals(listOf(182, 241, 253), RegionCatalog.inGroup(RegionGroup.TALK).map { it.tid })
        assertEquals(listOf(195, 257), RegionCatalog.inGroup(RegionGroup.VOICE).map { it.tid })
        assertEquals("电台", RegionCatalog.byTid(267)?.name)
        assertNull(RegionCatalog.byTid(175))
        assertEquals(RegionCatalog.all.size, RegionCatalog.all.map { it.tid }.toSet().size)
    }

    @Test
    fun `最近7天_含今天往前6天_跨月`() {
        assertEquals(DateSpan(d(2026, 10, 15), today), lastSevenDays(today))
        assertEquals(DateSpan(d(2026, 2, 26), d(2026, 3, 4)), lastSevenDays(d(2026, 3, 4)))
    }

    @Test
    fun `按月份_往月到月末_本月到今天_闰年2月`() {
        assertEquals(DateSpan(d(2026, 9, 1), d(2026, 9, 30)), monthSpan(2026, 9, today))
        assertEquals(DateSpan(d(2026, 10, 1), today), monthSpan(2026, 10, today))
        assertEquals(DateSpan(d(2024, 2, 1), d(2024, 2, 29)), monthSpan(2024, 2, today))
    }

    @Test
    fun `自定义_校验`() {
        assertNull(validateCustomSpan(DateSpan(d(2026, 9, 22), today), today)) // 30 天（含首尾）
        assertEquals(CustomSpanError.TOO_LONG, validateCustomSpan(DateSpan(d(2026, 9, 21), today), today))
        assertEquals(CustomSpanError.REVERSED, validateCustomSpan(DateSpan(today, d(2026, 10, 1)), today))
        assertEquals(CustomSpanError.TOO_EARLY, validateCustomSpan(DateSpan(d(2008, 12, 31), d(2009, 1, 2)), today))
        assertEquals(CustomSpanError.AFTER_TODAY, validateCustomSpan(DateSpan(today, d(2026, 10, 22)), today))
    }

    @Test
    fun `设置的时间范围_最近7天按当天重算_月份与自定义用存的`() {
        assertEquals(lastSevenDays(today), RegionSettings().span(today))
        val saved = DateSpan(d(2026, 9, 1), d(2026, 9, 30))
        assertEquals(saved, RegionSettings(timeMode = RegionTimeMode.MONTH, savedSpan = saved).span(today))
        assertEquals(saved, RegionSettings(timeMode = RegionTimeMode.CUSTOM, savedSpan = saved).span(today))
        // 存的范围丢了：退回最近 7 天
        assertEquals(lastSevenDays(today), RegionSettings(timeMode = RegionTimeMode.CUSTOM, savedSpan = null).span(today))
    }

    @Test
    fun `日期格式`() {
        assertEquals("20261015", formatApiDate(d(2026, 10, 15)))
        assertEquals(d(2026, 10, 15), parseApiDate("20261015"))
        assertNull(parseApiDate("2026-10-15"))
        assertNull(parseApiDate(null))
        assertEquals("10-15 至 10-21", formatSpanLabel(DateSpan(d(2026, 10, 15), today), today))
        assertEquals("2025-12-01 至 2025-12-31", formatSpanLabel(DateSpan(d(2025, 12, 1), d(2025, 12, 31)), today))
    }

    @Test
    fun `读设置_缺失或非法时用默认`() {
        assertEquals(RegionSettings(), resolveRegionSettings(null, null, null, null, null))
        val month = resolveRegionSettings("MONTH", "20260901", "20260930", "stow", false)
        assertEquals(RegionTimeMode.MONTH, month.timeMode)
        assertEquals(DateSpan(d(2026, 9, 1), d(2026, 9, 30)), month.savedSpan)
        assertEquals(RegionOrder.STOW, month.order)
        assertFalse(month.hideShort)
        // 模式要范围、范围却坏了：退回最近 7 天
        assertEquals(RegionTimeMode.LAST_7_DAYS, resolveRegionSettings("CUSTOM", "bad", "20260930", null, null).timeMode)
        assertEquals(RegionOrder.CLICK, resolveRegionSettings(null, null, null, "unknown", null).order)
        assertEquals(RegionTimeMode.LAST_7_DAYS, resolveRegionSettings("WHATEVER", null, null, null, null).timeMode)
    }

    @Test
    fun `短视频过滤_不足60秒去掉_开关关时原样`() {
        val items = listOf(item("a", 59), item("b", 60), item("c", 0), item("d", 300))
        assertEquals(listOf("b", "d"), visibleRegionItems(items, hideShort = true).map { it.bvid })
        assertEquals(items, visibleRegionItems(items, hideShort = false))
    }

    @Test
    fun `翻页追加按bvid去重_还有没有下一页`() {
        val merged = appendUnseenRegionItems(listOf(item("a", 99), item("b", 99)), listOf(item("b", 99), item("c", 99)))
        assertEquals(listOf("a", "b", "c"), merged.map { it.bvid })
        assertTrue(regionHasMore(page = 1, totalPages = 337, received = 20))
        assertFalse(regionHasMore(page = 337, totalPages = 337, received = 20))
        assertFalse(regionHasMore(page = 2, totalPages = 337, received = 0))
    }
}
