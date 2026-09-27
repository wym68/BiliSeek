package com.biliseek.app.ui

import com.biliseek.core.player.PresentationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenPolicyTest {

    // ── 方向 ──

    @Test
    fun `横屏视频转横屏`() {
        assertEquals(FullscreenOrientation.LANDSCAPE, resolveFullscreenOrientation(16f / 9f))
    }

    @Test
    fun `竖屏视频保持竖屏`() {
        assertEquals(FullscreenOrientation.PORTRAIT, resolveFullscreenOrientation(9f / 16f))
    }

    @Test
    fun `比例未知按横屏兜底`() {
        assertEquals(FullscreenOrientation.LANDSCAPE, resolveFullscreenOrientation(null))
    }

    @Test
    fun `正方形视频按横屏`() {
        assertEquals(FullscreenOrientation.LANDSCAPE, resolveFullscreenOrientation(1f))
    }

    // ── 自动隐藏 ──

    @Test
    fun `播放中且无操作时开始倒计时`() {
        assertTrue(shouldAutoHideControls(controlsVisible = true, isPlaying = true, interacting = false))
    }

    @Test
    fun `暂停时不隐藏`() {
        assertFalse(shouldAutoHideControls(controlsVisible = true, isPlaying = false, interacting = false))
    }

    @Test
    fun `拖动或面板打开时不隐藏`() {
        assertFalse(shouldAutoHideControls(controlsVisible = true, isPlaying = true, interacting = true))
    }

    @Test
    fun `已经隐藏时无需倒计时`() {
        assertFalse(shouldAutoHideControls(controlsVisible = false, isPlaying = true, interacting = false))
    }

    // ── 退出全屏 ──

    @Test
    fun `画面被回落为纯听时退出全屏`() {
        assertTrue(shouldLeaveFullscreen(PresentationMode.NONE))
    }

    @Test
    fun `可见呈现态下不退出全屏`() {
        assertFalse(shouldLeaveFullscreen(PresentationMode.FULLSCREEN))
        assertFalse(shouldLeaveFullscreen(PresentationMode.PIP))
        assertFalse(shouldLeaveFullscreen(PresentationMode.INLINE))
    }

    // ── PiP 恢复 ──

    @Test
    fun `从全屏进的 PiP 退出后回全屏`() {
        assertEquals(PresentationMode.FULLSCREEN, resolvePresentationAfterPip(PresentationMode.FULLSCREEN))
    }

    @Test
    fun `从内联进的 PiP 退出后回内联`() {
        assertEquals(PresentationMode.INLINE, resolvePresentationAfterPip(PresentationMode.INLINE))
    }

    @Test
    fun `未知来源退出后回内联`() {
        assertEquals(PresentationMode.INLINE, resolvePresentationAfterPip(null))
        assertEquals(PresentationMode.INLINE, resolvePresentationAfterPip(PresentationMode.PIP))
        assertEquals(PresentationMode.INLINE, resolvePresentationAfterPip(PresentationMode.NONE))
    }

    // ── 常亮 ──

    @Test
    fun `看模式播放中常亮`() {
        assertTrue(shouldKeepScreenOn(PresentationMode.INLINE, isPlaying = true))
        assertTrue(shouldKeepScreenOn(PresentationMode.FULLSCREEN, isPlaying = true))
    }

    @Test
    fun `纯听或暂停时不常亮`() {
        assertFalse(shouldKeepScreenOn(PresentationMode.NONE, isPlaying = true))
        assertFalse(shouldKeepScreenOn(PresentationMode.INLINE, isPlaying = false))
    }

    // ── PiP 比例 ──

    @Test
    fun `PiP 比例跟随视频`() {
        assertEquals(1777 to 1000, resolvePipAspectRatio(16f / 9f))
        assertEquals(562 to 1000, resolvePipAspectRatio(9f / 16f))
    }

    @Test
    fun `PiP 比例夹紧到系统允许范围`() {
        val (wide, d1) = resolvePipAspectRatio(4f)
        assertTrue(wide.toFloat() / d1 <= 2.39f)
        val (tall, d2) = resolvePipAspectRatio(0.1f)
        assertTrue(tall.toFloat() / d2 >= 1f / 2.39f)
    }

    @Test
    fun `PiP 比例未知按 16比9`() {
        assertEquals(1777 to 1000, resolvePipAspectRatio(null))
        assertEquals(1777 to 1000, resolvePipAspectRatio(0f))
    }
}
