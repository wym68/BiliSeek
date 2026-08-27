package com.biliseek.core.player.internal

import com.biliseek.core.player.PlaybackMode
import com.biliseek.core.player.PresentationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModePolicyTest {

    @Test
    fun `无媒体时恒为 IDLE`() {
        PresentationMode.entries.forEach { presentation ->
            assertEquals(
                PlaybackMode.IDLE,
                resolvePlaybackMode(presentation, hasMedia = false),
            )
        }
    }

    @Test
    fun `无可见画面时回落 AUDIO`() {
        assertEquals(
            PlaybackMode.AUDIO,
            resolvePlaybackMode(PresentationMode.NONE, hasMedia = true),
        )
    }

    @Test
    fun `任何可见呈现态都是 VIDEO`() {
        listOf(PresentationMode.INLINE, PresentationMode.FULLSCREEN, PresentationMode.PIP)
            .forEach { presentation ->
                assertEquals(
                    PlaybackMode.VIDEO,
                    resolvePlaybackMode(presentation, hasMedia = true),
                )
            }
    }

    /**
     * 《架构设计篇》§5.2 的核心不变式：
     * PlaybackMode == VIDEO ⟺ 视频轨启用 ⟺ 存在可见 surface
     *
     * 这条不变式若被打破，要么解码了没人看的画面（费电费流量），
     * 要么画面该出而不出。
     */
    @Test
    fun `不变式 VIDEO 态与视频轨启用互为充要条件`() {
        PresentationMode.entries.forEach { presentation ->
            val mode = resolvePlaybackMode(presentation, hasMedia = true)
            assertEquals(
                "呈现态 $presentation 下不变式被打破",
                mode == PlaybackMode.VIDEO,
                shouldEnableVideoTrack(presentation),
            )
        }
    }

    @Test
    fun `进后台时 PiP 不回落`() {
        assertEquals(
            PresentationMode.PIP,
            resolvePresentationOnEnterBackground(PresentationMode.PIP),
        )
    }

    @Test
    fun `进后台时非 PiP 一律回落 NONE`() {
        listOf(PresentationMode.INLINE, PresentationMode.FULLSCREEN, PresentationMode.NONE)
            .forEach { presentation ->
                assertEquals(
                    PresentationMode.NONE,
                    resolvePresentationOnEnterBackground(presentation),
                )
            }
    }

    @Test
    fun `PiP 视为可见故视频轨保持启用`() {
        assertTrue(shouldEnableVideoTrack(PresentationMode.PIP))
        assertFalse(shouldEnableVideoTrack(PresentationMode.NONE))
    }

    @Test
    fun `换内容起播时看模式回落到听`() {
        // 产品取向：看模式下切下一首回到听。
        // 起播本来就会禁掉视频轨，呈现态必须跟着复位，否则界面以为还在看模式——
        // 渲染一块没有画面的 SurfaceView：黑屏、不显示封面、按钮还写着「退回听」
        assertEquals(
            PresentationMode.NONE,
            resolvePresentationOnTrackChange(PresentationMode.INLINE),
        )
        assertEquals(
            PresentationMode.NONE,
            resolvePresentationOnTrackChange(PresentationMode.FULLSCREEN),
        )
    }

    @Test
    fun `换内容起播时 PiP 不回落`() {
        // 与进后台同一个理由：PiP 是系统窗口，关掉轨只会剩一个黑窗，不是「回到听」
        assertEquals(
            PresentationMode.PIP,
            resolvePresentationOnTrackChange(PresentationMode.PIP),
        )
    }

    @Test
    fun `本来就在听模式则原地不动`() {
        assertEquals(
            PresentationMode.NONE,
            resolvePresentationOnTrackChange(PresentationMode.NONE),
        )
    }

    @Test
    fun `换内容与进后台两条回落规则一致`() {
        // 两者理由相同（PiP 可见、其余回落），不该出现一个改了另一个没改
        PresentationMode.entries.forEach { presentation ->
            assertEquals(
                "呈现态 $presentation 下两条规则分叉了",
                resolvePresentationOnEnterBackground(presentation),
                resolvePresentationOnTrackChange(presentation),
            )
        }
    }

    @Test
    fun `切换标签可读`() {
        assertEquals("听 → 看", switchLabel(PresentationMode.NONE, PresentationMode.INLINE))
        assertEquals("看 → 听", switchLabel(PresentationMode.INLINE, PresentationMode.NONE))
    }

    @Test
    fun `内联与全屏之间切换不改变播放态`() {
        val inline = resolvePlaybackMode(PresentationMode.INLINE, hasMedia = true)
        val fullscreen = resolvePlaybackMode(PresentationMode.FULLSCREEN, hasMedia = true)
        assertEquals(inline, fullscreen)
        assertEquals(
            shouldEnableVideoTrack(PresentationMode.INLINE),
            shouldEnableVideoTrack(PresentationMode.FULLSCREEN),
        )
    }
}
