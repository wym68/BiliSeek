package com.biliseek.app.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuLayoutPolicyTest {

    private val w = 1000f
    private val d = 8_000L
    private val gap = 20f

    @Test
    fun `滚动弹幕从右边缘进左边缘出`() {
        assertEquals(1000f, scrollX(0, 0, 200f, w, d), 0.01f)
        assertEquals(-200f, scrollX(d, 0, 200f, w, d), 0.01f)
    }

    @Test
    fun `空轨道直接放`() {
        assertTrue(canFollowScroll(null, 0, 100f, w, d, gap))
    }

    @Test
    fun `前一条尾巴还没进屏时不能放`() {
        // 刚出发 100ms：200px 宽的弹幕尾巴还在屏外
        assertFalse(canFollowScroll(TrackOccupant(0, 200f), 100, 100f, w, d, gap))
    }

    @Test
    fun `同宽弹幕尾巴进屏后能跟上`() {
        // 同宽同速，永远追不上；尾巴进屏留出间距后即可放
        assertTrue(canFollowScroll(TrackOccupant(0, 200f), 2_000, 200f, w, d, gap))
    }

    @Test
    fun `更长更快的弹幕会追尾时不能放`() {
        // 前一条很短，后一条很长（更快），虽然尾巴已进屏，但会在前一条离屏前追上
        assertFalse(canFollowScroll(TrackOccupant(0, 50f), 1_000, 900f, w, d, gap))
    }

    @Test
    fun `挑第一条能放的轨道 都放不下返回 null`() {
        val busy = TrackOccupant(0, 200f)
        assertEquals(1, pickScrollTrack(listOf(busy, null), 100, 100f, w, d, gap))
        assertNull(pickScrollTrack(listOf(busy, busy), 100, 100f, w, d, gap))
    }

    @Test
    fun `固定弹幕轨道到时才空出`() {
        assertFalse(canFollowFixed(TrackOccupant(0, 100f), 3_999, 4_000))
        assertTrue(canFollowFixed(TrackOccupant(0, 100f), 4_000, 4_000))
        assertEquals(1, pickFixedTrack(listOf(TrackOccupant(0, 1f), null), 1_000, 4_000))
    }

    @Test
    fun `轨道数按区域高度算且至少一条`() {
        assertEquals(10, danmakuTrackCount(500f, 50f))
        assertEquals(1, danmakuTrackCount(10f, 50f))
        assertEquals(1, danmakuTrackCount(500f, 0f))
    }
}
