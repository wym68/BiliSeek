package com.biliseek.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 看视频时横过手机自动进全屏、竖回来自动退出：角度换算、只认变化、跟随系统「自动旋转」开关。
 */
class AutoRotatePolicyTest {

    // ── 角度 → 竖 / 横 ───────────────────────────

    @Test
    fun `接近正放算竖`() {
        assertEquals(DeviceTilt.PORTRAIT, deviceTilt(0))
        assertEquals(DeviceTilt.PORTRAIT, deviceTilt(25))
        assertEquals(DeviceTilt.PORTRAIT, deviceTilt(340))
    }

    @Test
    fun `向左或向右横过来算横`() {
        assertEquals(DeviceTilt.LANDSCAPE, deviceTilt(90))
        assertEquals(DeviceTilt.LANDSCAPE, deviceTilt(70))
        assertEquals(DeviceTilt.LANDSCAPE, deviceTilt(270))
        assertEquals(DeviceTilt.LANDSCAPE, deviceTilt(290))
    }

    @Test
    fun `平放、倒拿和临界角度不判断`() {
        assertNull(deviceTilt(-1))
        assertNull(deviceTilt(45))
        assertNull(deviceTilt(180))
        assertNull(deviceTilt(320))
    }

    // ── 什么时候进 / 退全屏 ──────────────────────

    @Test
    fun `看横屏视频时从竖变横进全屏`() {
        assertEquals(
            AutoRotateAction.ENTER_FULLSCREEN,
            resolveAutoRotate(DeviceTilt.PORTRAIT, DeviceTilt.LANDSCAPE, fullscreen = null, canAutoEnter = true, systemAutoRotate = true),
        )
    }

    @Test
    fun `横屏全屏里从横变竖退出全屏`() {
        assertEquals(
            AutoRotateAction.EXIT_FULLSCREEN,
            resolveAutoRotate(
                DeviceTilt.LANDSCAPE, DeviceTilt.PORTRAIT,
                fullscreen = FullscreenOrientation.LANDSCAPE, canAutoEnter = false, systemAutoRotate = true,
            ),
        )
    }

    @Test
    fun `系统自动旋转关着时什么都不做`() {
        assertNull(resolveAutoRotate(DeviceTilt.PORTRAIT, DeviceTilt.LANDSCAPE, null, canAutoEnter = true, systemAutoRotate = false))
        assertNull(
            resolveAutoRotate(DeviceTilt.LANDSCAPE, DeviceTilt.PORTRAIT, FullscreenOrientation.LANDSCAPE, false, systemAutoRotate = false),
        )
    }

    @Test
    fun `只认变化：手动点全屏时手机还竖着不会被立刻退出，横着手动退出也不会被立刻拉回`() {
        // 刚开始监听：上一次方向未知
        assertNull(resolveAutoRotate(null, DeviceTilt.PORTRAIT, FullscreenOrientation.LANDSCAPE, false, true))
        assertNull(resolveAutoRotate(null, DeviceTilt.LANDSCAPE, null, canAutoEnter = true, systemAutoRotate = true))
        // 方向没变
        assertNull(resolveAutoRotate(DeviceTilt.PORTRAIT, DeviceTilt.PORTRAIT, FullscreenOrientation.LANDSCAPE, false, true))
        assertNull(resolveAutoRotate(DeviceTilt.LANDSCAPE, DeviceTilt.LANDSCAPE, null, canAutoEnter = true, systemAutoRotate = true))
    }

    @Test
    fun `不能自动进时横过来不进全屏`() {
        // 不在播放页 / 没在看 / 画中画 / 竖屏视频
        assertNull(resolveAutoRotate(DeviceTilt.PORTRAIT, DeviceTilt.LANDSCAPE, null, canAutoEnter = false, systemAutoRotate = true))
    }

    @Test
    fun `竖屏视频的竖屏全屏不因转手机退出`() {
        assertNull(
            resolveAutoRotate(DeviceTilt.LANDSCAPE, DeviceTilt.PORTRAIT, FullscreenOrientation.PORTRAIT, false, true),
        )
    }

    // ── 能不能自动进 ────────────────────────────

    @Test
    fun `在播放页内嵌看横屏视频时可以自动进`() {
        assertEquals(true, canAutoEnterFullscreen(onPlayerPage = true, watchingInline = true, inPip = false, videoAspectRatio = 16f / 9f))
        assertEquals(true, canAutoEnterFullscreen(onPlayerPage = true, watchingInline = true, inPip = false, videoAspectRatio = null))
    }

    @Test
    fun `不在播放页、在听、画中画或竖屏视频时不能自动进`() {
        assertEquals(false, canAutoEnterFullscreen(onPlayerPage = false, watchingInline = true, inPip = false, videoAspectRatio = 1.7f))
        assertEquals(false, canAutoEnterFullscreen(onPlayerPage = true, watchingInline = false, inPip = false, videoAspectRatio = 1.7f))
        assertEquals(false, canAutoEnterFullscreen(onPlayerPage = true, watchingInline = true, inPip = true, videoAspectRatio = 1.7f))
        assertEquals(false, canAutoEnterFullscreen(onPlayerPage = true, watchingInline = true, inPip = false, videoAspectRatio = 9f / 16f))
    }
}
