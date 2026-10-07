package com.biliseek.app.hint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HintControllerTest {

    private class FakeRecords(var saved: PersistedHints = PersistedHints()) : HintRecords {
        var saves = 0
        override fun load(): PersistedHints = saved
        override fun save(hints: PersistedHints) {
            saved = hints
            saves++
        }
    }

    @Test
    fun `同一时间只能开始一个`() {
        val c = HintController(FakeRecords())
        assertTrue(c.tryStart(HintKey.SWIPE_QUEUE))
        assertFalse(c.tryStart(HintKey.SWIPE_FAVORITE))
        // 已经在放，不算新开始
        assertFalse(c.tryStart(HintKey.SWIPE_QUEUE))
    }

    @Test
    fun `放完记为看过并落盘 离开页面不记`() {
        val records = FakeRecords()
        val c = HintController(records)
        c.tryStart(HintKey.SWIPE_QUEUE)
        c.cancel(HintKey.SWIPE_QUEUE)
        assertTrue(records.saved.shown.isEmpty())
        assertNull(c.state.value.active)
        c.tryStart(HintKey.SWIPE_QUEUE)
        c.finish(HintKey.SWIPE_QUEUE)
        assertEquals(setOf(HintKey.SWIPE_QUEUE), records.saved.shown)
        assertNull(c.state.value.active)
    }

    @Test
    fun `碰屏幕结束当前演示并记为看过 没在放时什么也不做`() {
        val records = FakeRecords()
        val c = HintController(records)
        c.onTouch()
        assertEquals(0, records.saves)
        c.tryStart(HintKey.SEARCH_MULTI_SELECT)
        c.onTouch()
        assertNull(c.state.value.active)
        assertEquals(setOf(HintKey.SEARCH_MULTI_SELECT), records.saved.shown)
    }

    @Test
    fun `迷你条上滑看过后 下一次冷启动才放拖进度`() {
        val records = FakeRecords()
        val first = HintController(records)
        first.onColdStart()
        first.tryStart(HintKey.MINIBAR_SWIPE)
        first.finish(HintKey.MINIBAR_SWIPE)
        assertFalse(first.tryStart(HintKey.MINIBAR_DRAG))
        val second = HintController(records)
        second.onColdStart()
        assertTrue(second.tryStart(HintKey.MINIBAR_DRAG))
    }

    @Test
    fun `用户自己拖完进度线后 刻度尺演示可以开始`() {
        val c = HintController(FakeRecords())
        assertFalse(c.tryStart(HintKey.MINIBAR_ZOOM))
        c.onUserAction(HintKey.MINIBAR_DRAG)
        assertTrue(c.tryStart(HintKey.MINIBAR_ZOOM))
    }

    @Test
    fun `动态页 头像看过的那次进入不放视频长按 下次进入才放`() {
        val c = HintController(FakeRecords())
        c.enterDynamic()
        c.tryStart(HintKey.DYNAMIC_UP_FILTER)
        c.finish(HintKey.DYNAMIC_UP_FILTER)
        assertFalse(c.tryStart(HintKey.DYNAMIC_VIDEO_MENU))
        c.enterDynamic()
        assertTrue(c.tryStart(HintKey.DYNAMIC_VIDEO_MENU))
    }

    @Test
    fun `全屏或小窗时不开始`() {
        val c = HintController(FakeRecords())
        c.setEnvironmentBlocked(true)
        assertFalse(c.tryStart(HintKey.SWIPE_QUEUE))
        c.setEnvironmentBlocked(false)
        assertTrue(c.tryStart(HintKey.SWIPE_QUEUE))
    }

    @Test
    fun `重置清空看过的记录并落盘 启动序号保留`() {
        val records = FakeRecords(
            PersistedHints(shown = setOf(HintKey.SWIPE_QUEUE), launchSeq = 4, minibarSwipeLaunch = 2),
        )
        val c = HintController(records)
        c.reset()
        assertEquals(PersistedHints(launchSeq = 4), records.saved)
        assertTrue(c.tryStart(HintKey.SWIPE_QUEUE))
    }

    @Test
    fun `只改内存状态时不写盘`() {
        val records = FakeRecords()
        val c = HintController(records)
        c.enterDynamic()
        c.setEnvironmentBlocked(true)
        c.setEnvironmentBlocked(false)
        c.tryStart(HintKey.SWIPE_QUEUE)
        c.cancel(HintKey.SWIPE_QUEUE)
        assertEquals(0, records.saves)
    }
}
