package com.biliseek.core.player.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumePolicyTest {

    @Test
    fun `开头几秒不算进度`() {
        assertEquals(0L, resolveStartPosition(0, 300_000))
        assertEquals(0L, resolveStartPosition(3_000, 300_000))
        assertEquals(0L, resolveStartPosition(RESUME_MIN_MS, 300_000))
    }

    @Test
    fun `中段进度正常续播`() {
        assertEquals(60_000L, resolveStartPosition(60_000, 300_000))
    }

    /**
     * 实测踩到的 case：一首 62 秒的歌存了 61 秒进度，
     * 续播后 1 秒就结束，用户看到的是「点了没反应」。
     */
    @Test
    fun `接近结尾时从头开始`() {
        assertEquals(0L, resolveStartPosition(61_000, 62_000))
        assertEquals(0L, resolveStartPosition(295_000, 300_000))
    }

    @Test
    fun `恰好在尾部边界外仍续播`() {
        // 300s 的歌，284s 处距结尾 16s > 15s 边界，应续播
        assertEquals(284_000L, resolveStartPosition(284_000, 300_000))
    }

    @Test
    fun `时长未知时只裁开头`() {
        assertEquals(60_000L, resolveStartPosition(60_000, 0))
        assertEquals(0L, resolveStartPosition(2_000, 0))
    }
}
