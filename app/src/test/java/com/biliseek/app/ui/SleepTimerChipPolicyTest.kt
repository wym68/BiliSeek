package com.biliseek.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerChipPolicyTest {

    @Test
    fun `没设定时写入口文案`() {
        assertEquals("定时", sleepTimerChipLabel(remainingMs = null, endOfTrack = false))
    }

    @Test
    fun `按时长定时写剩余时间`() {
        assertEquals("28:40", sleepTimerChipLabel(remainingMs = 1_720_000, endOfTrack = false))
        assertEquals("00:05", sleepTimerChipLabel(remainingMs = 5_000, endOfTrack = false))
    }

    @Test
    fun `超过一小时带小时位`() {
        assertEquals("1:00:00", sleepTimerChipLabel(remainingMs = 3_600_000, endOfTrack = false))
    }

    @Test
    fun `选了播完当前就写播完当前`() {
        assertEquals("播完当前", sleepTimerChipLabel(remainingMs = null, endOfTrack = true))
    }
}
