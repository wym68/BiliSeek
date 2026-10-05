package com.biliseek.app.ui

import com.biliseek.app.data.DefaultPresentation
import com.biliseek.core.player.PresentationMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页与迷你播放条之间的听 / 看规则：离开播放页（回到迷你条）在看就切成听；
 * 迷你条在显示却还在看时兜底切回听；「起播模式：记住上次」在打开播放页时生效。
 */
class PlayerPagePolicyTest {

    // ── 离开播放页 ───────────────────────────────

    @Test
    fun `在看（内嵌或全屏）时离开播放页要先切成听`() {
        assertTrue(mustLeaveToListen(PresentationMode.INLINE))
        assertTrue(mustLeaveToListen(PresentationMode.FULLSCREEN))
    }

    @Test
    fun `已经在听或在画中画时直接离开`() {
        assertFalse(mustLeaveToListen(PresentationMode.NONE))
        assertFalse(mustLeaveToListen(PresentationMode.PIP))
    }

    // ── 迷你条在显示却还在看：兜底 ───────────────

    @Test
    fun `不在播放页却还在内嵌看时切回听`() {
        assertTrue(shouldForceListen(onPlayerPage = false, PresentationMode.INLINE, fullscreen = false, inPip = false))
    }

    @Test
    fun `在播放页、全屏、画中画或本来就在听时不动`() {
        assertFalse(shouldForceListen(onPlayerPage = true, PresentationMode.INLINE, fullscreen = false, inPip = false))
        assertFalse(shouldForceListen(onPlayerPage = false, PresentationMode.FULLSCREEN, fullscreen = true, inPip = false))
        assertFalse(shouldForceListen(onPlayerPage = false, PresentationMode.PIP, fullscreen = false, inPip = true))
        assertFalse(shouldForceListen(onPlayerPage = false, PresentationMode.NONE, fullscreen = false, inPip = false))
    }

    // ── 打开播放页：「记住上次」 ─────────────────

    @Test
    fun `记住上次且上次离开播放页时在看，打开播放页自动切回看`() {
        assertTrue(
            shouldAutoWatchOnPlayerPage(
                DefaultPresentation.REMEMBER_LAST, lastWasVideo = true, PresentationMode.NONE, hasMedia = true,
            ),
        )
    }

    @Test
    fun `默认只听、上次在听、没有内容或已经在看时都不自动切`() {
        assertFalse(
            shouldAutoWatchOnPlayerPage(DefaultPresentation.AUDIO_ONLY, true, PresentationMode.NONE, hasMedia = true),
        )
        assertFalse(
            shouldAutoWatchOnPlayerPage(DefaultPresentation.REMEMBER_LAST, false, PresentationMode.NONE, hasMedia = true),
        )
        assertFalse(
            shouldAutoWatchOnPlayerPage(DefaultPresentation.REMEMBER_LAST, true, PresentationMode.NONE, hasMedia = false),
        )
        assertFalse(
            shouldAutoWatchOnPlayerPage(DefaultPresentation.REMEMBER_LAST, true, PresentationMode.INLINE, hasMedia = true),
        )
    }
}
