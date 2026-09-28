package com.biliseek.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

class CountFormatTest {

    @Test
    fun `万以下原样显示`() {
        assertEquals("0", formatCompactCount(0))
        assertEquals("9999", formatCompactCount(9_999))
    }

    @Test
    fun `万级保留一位小数_整数去掉点零`() {
        assertEquals("1万", formatCompactCount(10_000))
        assertEquals("1.2万", formatCompactCount(12_345))
        assertEquals("30万", formatCompactCount(300_000))
    }

    @Test
    fun `截断不进位`() {
        assertEquals("9.9万", formatCompactCount(99_999))
        assertEquals("9999.9万", formatCompactCount(99_999_999))
    }

    @Test
    fun `亿级`() {
        assertEquals("1亿", formatCompactCount(100_000_000L))
        assertEquals("2.5亿", formatCompactCount(250_000_000L))
    }

    @Test
    fun `负数按零`() {
        assertEquals("0", formatCompactCount(-1))
    }
}
