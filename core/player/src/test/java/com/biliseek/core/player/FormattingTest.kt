package com.biliseek.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    @Test
    fun `不足一小时用 mm ss`() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:09", formatDuration(9_000))
        assertEquals("04:32", formatDuration(272_000))
        assertEquals("59:59", formatDuration(3_599_000))
    }

    @Test
    fun `超过一小时用 h mm ss`() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
        assertEquals("10:00:00", formatDuration(36_000_000))
    }

    /** ExoPlayer 时长未知时返回 C.TIME_UNSET（负数），不能让界面显示 `-1:-1`。 */
    @Test
    fun `负数夹成零`() {
        assertEquals("00:00", formatDuration(-1))
        assertEquals("00:00", formatDuration(Long.MIN_VALUE))
    }

    @Test
    fun `不足一秒的余数向下取整`() {
        assertEquals("00:01", formatDuration(1_999))
    }
}
