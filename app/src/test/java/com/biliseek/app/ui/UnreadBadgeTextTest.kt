package com.biliseek.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UnreadBadgeTextTest {

    @Test
    fun `99 及以下照实显示`() {
        assertEquals("1", unreadBadgeText(1))
        assertEquals("99", unreadBadgeText(99))
    }

    @Test
    fun `超过 99 显示 99+`() {
        assertEquals("99+", unreadBadgeText(100))
        assertEquals("99+", unreadBadgeText(3000))
    }
}
