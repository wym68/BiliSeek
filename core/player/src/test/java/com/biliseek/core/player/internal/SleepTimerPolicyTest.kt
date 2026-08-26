package com.biliseek.core.player.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerPolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `倒计时按时长换算成绝对时间戳`() {
        val spec = deadlineAfter(now, 15 * 60_000L)
        assertEquals(SleepTimerSpec.Deadline(now + 900_000), spec)
    }

    @Test
    fun `时长非正视为取消`() {
        assertNull(deadlineAfter(now, 0))
        assertNull(deadlineAfter(now, -1))
    }

    @Test
    fun `剩余时间随时间推进递减`() {
        val spec = deadlineAfter(now, 60_000)
        assertEquals(60_000L, sleepTimerRemaining(spec, now))
        assertEquals(20_000L, sleepTimerRemaining(spec, now + 40_000))
    }

    /** 到点后剩余不能变负——界面会显示 `-00:03`。 */
    @Test
    fun `过点后剩余夹到零`() {
        val spec = deadlineAfter(now, 60_000)
        assertEquals(0L, sleepTimerRemaining(spec, now + 90_000))
    }

    @Test
    fun `播完当前无剩余时间可报`() {
        assertNull(sleepTimerRemaining(SleepTimerSpec.EndOfTrack, now))
        assertNull(sleepTimerRemaining(null, now))
    }

    @Test
    fun `到点判定含边界`() {
        val spec = deadlineAfter(now, 60_000)
        assertFalse(hasSleepTimerExpired(spec, now + 59_999))
        assertTrue(hasSleepTimerExpired(spec, now + 60_000))
        assertTrue(hasSleepTimerExpired(spec, now + 60_001))
    }

    @Test
    fun `未设定时器永不到点`() {
        assertFalse(hasSleepTimerExpired(null, now))
        assertFalse(hasSleepTimerExpired(SleepTimerSpec.EndOfTrack, now))
    }

    /** 两种终止条件互不越界：倒计时不会在曲目播完时抢跑。 */
    @Test
    fun `只有播完当前才拦截曲目推进`() {
        assertTrue(shouldStopOnTrackEnd(SleepTimerSpec.EndOfTrack))
        assertFalse(shouldStopOnTrackEnd(deadlineAfter(now, 60_000)))
        assertFalse(shouldStopOnTrackEnd(null))
    }
}
