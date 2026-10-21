package com.biliseek.app.hint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HintPolicyTest {

    private fun state(
        shown: Set<HintKey> = emptySet(),
        launchSeq: Int = 1,
        minibarSwipeLaunch: Int = HINT_NONE,
        active: HintKey? = null,
        blocked: Boolean = false,
        dynamicVisit: Int = 1,
        upFilterVisit: Int = HINT_NONE,
        zoomArmed: Boolean = false,
    ) = HintState(
        persisted = PersistedHints(shown, launchSeq, minibarSwipeLaunch),
        active = active,
        environmentBlocked = blocked,
        dynamicVisit = dynamicVisit,
        upFilterVisit = upFilterVisit,
        zoomArmed = zoomArmed,
    )

    @Test
    fun `没看过且没有别的在放就能放 看过就不放`() {
        assertTrue(canStartHint(HintKey.SWIPE_FAVORITE, state()))
        assertFalse(canStartHint(HintKey.SWIPE_FAVORITE, state(shown = setOf(HintKey.SWIPE_FAVORITE))))
    }

    @Test
    fun `同一时间只放一个 全屏或小窗时不放`() {
        assertFalse(canStartHint(HintKey.SWIPE_FAVORITE, state(active = HintKey.MINIBAR_SWIPE)))
        assertFalse(canStartHint(HintKey.SWIPE_FAVORITE, state(blocked = true)))
    }

    @Test
    fun `迷你条拖进度要等上滑看过 而且换了一次启动`() {
        assertFalse(canStartHint(HintKey.MINIBAR_DRAG, state()))
        val swipeThisLaunch = state(shown = setOf(HintKey.MINIBAR_SWIPE), launchSeq = 3, minibarSwipeLaunch = 3)
        assertFalse(canStartHint(HintKey.MINIBAR_DRAG, swipeThisLaunch))
        val nextLaunch = swipeThisLaunch.copy(persisted = swipeThisLaunch.persisted.copy(launchSeq = 4))
        assertTrue(canStartHint(HintKey.MINIBAR_DRAG, nextLaunch))
    }

    @Test
    fun `刻度尺演示只在用户自己拖完进度线之后`() {
        assertFalse(canStartHint(HintKey.MINIBAR_ZOOM, state()))
        assertTrue(canStartHint(HintKey.MINIBAR_ZOOM, state(zoomArmed = true)))
    }

    @Test
    fun `动态视频长按要等头像看过 而且不是同一次进入`() {
        assertFalse(canStartHint(HintKey.DYNAMIC_VIDEO_MENU, state()))
        val sameVisit = state(shown = setOf(HintKey.DYNAMIC_UP_FILTER), dynamicVisit = 2, upFilterVisit = 2)
        assertFalse(canStartHint(HintKey.DYNAMIC_VIDEO_MENU, sameVisit))
        assertTrue(canStartHint(HintKey.DYNAMIC_VIDEO_MENU, sameVisit.copy(dynamicVisit = 3)))
        // 头像是以前打开 App 时看过的：本进程没有进入序号
        assertTrue(canStartHint(HintKey.DYNAMIC_VIDEO_MENU, state(shown = setOf(HintKey.DYNAMIC_UP_FILTER))))
    }

    @Test
    fun `开始和取消只动正在放的那一个`() {
        assertEquals(HintKey.SWIPE_QUEUE, startHint(state(), HintKey.SWIPE_QUEUE).active)
        assertNull(startHint(state(shown = setOf(HintKey.SWIPE_QUEUE)), HintKey.SWIPE_QUEUE).active)
        assertNull(cancelHint(state(active = HintKey.SWIPE_QUEUE), HintKey.SWIPE_QUEUE).active)
        assertEquals(
            HintKey.SWIPE_QUEUE,
            cancelHint(state(active = HintKey.SWIPE_QUEUE), HintKey.SWIPE_FAVORITE).active,
        )
    }

    @Test
    fun `记为看过会停下正在放的 并记下上滑的启动序号和头像的进入序号`() {
        val s = markHintSeen(state(active = HintKey.MINIBAR_SWIPE, launchSeq = 5), HintKey.MINIBAR_SWIPE)
        assertNull(s.active)
        assertTrue(HintKey.MINIBAR_SWIPE in s.persisted.shown)
        assertEquals(5, s.persisted.minibarSwipeLaunch)
        assertEquals(4, markHintSeen(state(dynamicVisit = 4), HintKey.DYNAMIC_UP_FILTER).upFilterVisit)
    }

    @Test
    fun `已经看过的再记一次 不改序号`() {
        val s = state(shown = setOf(HintKey.MINIBAR_SWIPE), launchSeq = 7, minibarSwipeLaunch = 2)
        assertEquals(2, markHintSeen(s, HintKey.MINIBAR_SWIPE).persisted.minibarSwipeLaunch)
    }

    @Test
    fun `用户自己拖完进度线 记拖进度看过并且可以放刻度尺演示`() {
        val s = applyUserAction(state(), HintKey.MINIBAR_DRAG)
        assertTrue(HintKey.MINIBAR_DRAG in s.persisted.shown)
        assertTrue(s.zoomArmed)
        assertFalse(applyUserAction(state(shown = setOf(HintKey.MINIBAR_ZOOM)), HintKey.MINIBAR_DRAG).zoomArmed)
    }

    @Test
    fun `用户自己做过别的操作 只记那一项并停下它的演示`() {
        val s = applyUserAction(state(active = HintKey.SWIPE_FAVORITE), HintKey.SWIPE_FAVORITE)
        assertEquals(setOf(HintKey.SWIPE_FAVORITE), s.persisted.shown)
        assertNull(s.active)
        assertFalse(s.zoomArmed)
    }

    @Test
    fun `被别的演示占着而本来能放的 这次进入放弃`() {
        assertTrue(shouldForfeitHint(HintKey.SWIPE_FAVORITE, state(active = HintKey.MINIBAR_SWIPE)))
        assertFalse(shouldForfeitHint(HintKey.SWIPE_FAVORITE, state(active = HintKey.SWIPE_FAVORITE)))
        assertFalse(shouldForfeitHint(HintKey.SWIPE_FAVORITE, state()))
        // 本来就不能放（刻度尺还没解锁）：不放弃，等解锁
        assertFalse(shouldForfeitHint(HintKey.MINIBAR_ZOOM, state(active = HintKey.MINIBAR_SWIPE)))
    }

    @Test
    fun `重置清空记录 但保留启动序号`() {
        val s = resetHints(
            state(
                shown = HintKey.entries.toSet(),
                launchSeq = 9,
                minibarSwipeLaunch = 3,
                active = HintKey.SWIPE_QUEUE,
                upFilterVisit = 2,
                zoomArmed = true,
            ),
        )
        assertEquals(PersistedHints(launchSeq = 9), s.persisted)
        assertNull(s.active)
        assertEquals(HINT_NONE, s.upFilterVisit)
        assertFalse(s.zoomArmed)
    }

    @Test
    fun `队列沿用旧键 各项的键互不相同`() {
        assertEquals("queue_swipe_hint_shown", HintKey.SWIPE_QUEUE.prefKey)
        assertEquals(HintKey.entries.size, HintKey.entries.map { it.prefKey }.toSet().size)
    }

    @Test
    fun `首页快捷入口提示_没看过能放_放完记为看过`() {
        assertTrue(canStartHint(HintKey.HOME_QUICK_ENTRIES, state()))
        val started = startHint(state(), HintKey.HOME_QUICK_ENTRIES)
        assertEquals(HintKey.HOME_QUICK_ENTRIES, started.active)
        val seen = markHintSeen(started, HintKey.HOME_QUICK_ENTRIES)
        assertNull(seen.active)
        assertFalse(canStartHint(HintKey.HOME_QUICK_ENTRIES, seen))
    }
}
