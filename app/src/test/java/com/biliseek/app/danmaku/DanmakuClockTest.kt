package com.biliseek.app.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuClockTest {

    private val ms = 1_000_000L // 1ms 的纳秒数

    @Test
    fun `首次更新视为跳变`() {
        assertTrue(DanmakuClock().update(10_000, 0, 1f, playing = true))
    }

    @Test
    fun `播放中按倍速外推`() {
        val clock = DanmakuClock()
        clock.update(10_000, 0, 1f, playing = true)
        assertEquals(10_100L, clock.now(100 * ms))
        clock.update(10_100, 100 * ms, 2f, playing = true)
        assertEquals(10_300L, clock.now(200 * ms))
    }

    @Test
    fun `暂停时时间冻结`() {
        val clock = DanmakuClock()
        clock.update(10_000, 0, 1f, playing = false)
        assertEquals(10_000L, clock.now(5_000 * ms))
    }

    @Test
    fun `小偏差不重新对齐`() {
        val clock = DanmakuClock()
        clock.update(10_000, 0, 1f, playing = true)
        // 250ms 后推来的位置比外推值慢 100ms：不对齐，继续外推
        assertFalse(clock.update(10_150, 250 * ms, 1f, playing = true))
        assertEquals(10_250L, clock.now(250 * ms))
    }

    @Test
    fun `大偏差重新对齐但不算跳变`() {
        val clock = DanmakuClock()
        clock.update(10_000, 0, 1f, playing = true)
        assertFalse(clock.update(10_700, 250 * ms, 1f, playing = true))
        assertEquals(10_700L, clock.now(250 * ms))
    }

    @Test
    fun `seek 视为跳变`() {
        val clock = DanmakuClock()
        clock.update(10_000, 0, 1f, playing = true)
        assertTrue(clock.update(60_000, 250 * ms, 1f, playing = true))
        assertEquals(60_000L, clock.now(250 * ms))
    }
}
