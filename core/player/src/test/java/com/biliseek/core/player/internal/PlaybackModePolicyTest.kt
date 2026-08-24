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
