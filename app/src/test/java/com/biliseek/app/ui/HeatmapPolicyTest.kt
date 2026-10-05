package com.biliseek.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 首页播放热力图的格子日期：每列「周日 → 周六」（与左侧星期标签一致）、最后一列是今天所在周，
 * 今天之后的格子不画。
 */
class HeatmapPolicyTest {

    private val sunday = LocalDate.of(2026, 10, 4)
    private val wednesday = LocalDate.of(2026, 10, 7)
    private val saturday = LocalDate.of(2026, 10, 10)
    private val weeks = 53

    @Test
    fun `最后一列从今天所在周的周日开始`() {
        assertEquals(sunday, heatmapCellDate(wednesday, weeks, column = weeks - 1, row = 0))
        assertEquals(sunday, heatmapCellDate(sunday, weeks, column = weeks - 1, row = 0))
        assertEquals(sunday, heatmapCellDate(saturday, weeks, column = weeks - 1, row = 0))
    }

    @Test
    fun `今天是周三时最后一列显示周日到周三，周四到周六不画`() {
        val shown = (0..6).map { row ->
            heatmapCellShown(heatmapCellDate(wednesday, weeks, weeks - 1, row), today = wednesday)
        }
        assertEquals(listOf(true, true, true, true, false, false, false), shown)
        assertEquals(wednesday, heatmapCellDate(wednesday, weeks, weeks - 1, row = 3))
    }

    @Test
    fun `今天是周日时最后一列只有今天一格`() {
        assertTrue(heatmapCellShown(heatmapCellDate(sunday, weeks, weeks - 1, 0), today = sunday))
        assertFalse(heatmapCellShown(heatmapCellDate(sunday, weeks, weeks - 1, 1), today = sunday))
    }

    @Test
    fun `今天是周六时最后一列七格都显示`() {
        assertTrue((0..6).all { heatmapCellShown(heatmapCellDate(saturday, weeks, weeks - 1, it), saturday) })
        assertEquals(saturday, heatmapCellDate(saturday, weeks, weeks - 1, row = 6))
    }

    @Test
    fun `列内从上到下按日期递增，下一列接着上一列`() {
        for (column in 0 until weeks) {
            for (row in 0 until 6) {
                assertEquals(
                    heatmapCellDate(wednesday, weeks, column, row).plusDays(1),
                    heatmapCellDate(wednesday, weeks, column, row + 1),
                )
            }
            if (column < weeks - 1) {
                assertEquals(
                    heatmapCellDate(wednesday, weeks, column, 6).plusDays(1),
                    heatmapCellDate(wednesday, weeks, column + 1, 0),
                )
            }
        }
    }

    @Test
    fun `第一列是最后一列往前 52 周`() {
        assertEquals(sunday.minusWeeks(52), heatmapCellDate(wednesday, weeks, column = 0, row = 0))
    }
}
