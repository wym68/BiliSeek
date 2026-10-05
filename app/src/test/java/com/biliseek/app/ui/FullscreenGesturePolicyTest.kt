package com.biliseek.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullscreenGesturePolicyTest {

    private val w = 2000f
    private val h = 1000f
    private val slop = 20f
    private val edge = 60f

    private fun gesture(x: Float, y: Float, dx: Float, dy: Float) =
        resolveDragGesture(x, y, w, h, dx, dy, slop, edge)

    // ── 判定 ──

    @Test
    fun `未越过 slop 时尚未决定`() {
        assertNull(gesture(1000f, 500f, 10f, 5f))
    }

    @Test
    fun `横向为主调进度`() {
        assertEquals(DragGesture.SEEK, gesture(1000f, 500f, 100f, 30f))
        assertEquals(DragGesture.SEEK, gesture(300f, 500f, -100f, 30f))
    }

    @Test
    fun `左半屏纵向调亮度 右半屏纵向调音量`() {
        assertEquals(DragGesture.BRIGHTNESS, gesture(500f, 500f, 5f, -100f))
        assertEquals(DragGesture.VOLUME, gesture(1500f, 500f, 5f, 100f))
    }

    @Test
    fun `左右边缘让给系统返回手势`() {
        assertEquals(DragGesture.NONE, gesture(30f, 500f, 100f, 0f))
        assertEquals(DragGesture.NONE, gesture(1980f, 500f, -100f, 0f))
    }

    @Test
    fun `上下边缘的纵向拖动让给系统栏 横向不受影响`() {
        assertEquals(DragGesture.NONE, gesture(500f, 20f, 0f, 100f))
        assertEquals(DragGesture.NONE, gesture(1500f, 980f, 0f, -100f))
        assertEquals(DragGesture.SEEK, gesture(500f, 20f, 100f, 0f))
    }

    // ── 进度 ──

    @Test
    fun `拖满一屏宽等于两分钟`() {
        assertEquals(120_000L + 60_000L, resolveSeekTarget(60_000L, w, w, 3_600_000L))
        assertEquals(60_000L + 30_000L, resolveSeekTarget(60_000L, w / 4, w, 3_600_000L))
    }

    @Test
    fun `短视频拖满一屏宽等于整段时长`() {
        assertEquals(60_000L, resolveSeekTarget(0L, w, w, 60_000L))
    }

    @Test
    fun `进度夹在 0 到时长之间`() {
        assertEquals(0L, resolveSeekTarget(10_000L, -w, w, 3_600_000L))
        assertEquals(100_000L, resolveSeekTarget(90_000L, w, w, 100_000L))
    }

    @Test
    fun `时长未知时不动`() {
        assertEquals(5_000L, resolveSeekTarget(5_000L, w, w, 0L))
    }

    // ── 亮度 / 音量 ──

    @Test
    fun `上滑变大 下滑变小`() {
        assertEquals(0.75f, resolveLevel(0.5f, -h / 4, h), 0.001f)
        assertEquals(0.25f, resolveLevel(0.5f, h / 4, h), 0.001f)
    }

    @Test
    fun `亮度音量夹在 0 到 1`() {
        assertEquals(1f, resolveLevel(0.9f, -h, h), 0.001f)
        assertEquals(0f, resolveLevel(0.1f, h, h), 0.001f)
    }

    // ── 长按倍速 ──

    @Test
    fun `长按加到 2 倍`() {
        assertEquals(2.0f, resolveLongPressSpeed(1.0f, losslessActive = false))
    }

    @Test
    fun `正在放无损时只加到 1点5 倍`() {
        assertEquals(1.5f, resolveLongPressSpeed(1.0f, losslessActive = true))
    }

    @Test
    fun `已经不慢于目标时不加速`() {
        assertNull(resolveLongPressSpeed(2.0f, losslessActive = false))
        assertNull(resolveLongPressSpeed(3.0f, losslessActive = false))
        assertNull(resolveLongPressSpeed(1.5f, losslessActive = true))
    }

    @Test
    fun `暂停时长按不加速`() {
        // 用户 2026-10-04：「正在播放内容时」长按才 2 倍速（播放页小框、全屏一致）
        assertNull(resolveLongPressSpeed(1.0f, losslessActive = false, isPlaying = false))
        assertEquals(2.0f, resolveLongPressSpeed(1.0f, losslessActive = false, isPlaying = true))
    }
}
