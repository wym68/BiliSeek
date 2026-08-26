package com.biliseek.core.network

import com.biliseek.core.network.internal.RetryBudget
import com.biliseek.core.network.internal.SeedingProfile
import com.biliseek.core.network.internal.nextBackoffMs
import com.biliseek.core.network.internal.retryBudgetFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快慢两类重试预算。
 *
 * 本里程碑最硬的判据（B-1「断网 5 秒内报错」）就落在这些数字上，
 * 因此把它们钉进单测，而不是靠代码里的常量自证。
 */
class RetryBudgetTest {

    private val background = retryBudgetFor(SeedingProfile.BACKGROUND)
    private val foreground = retryBudgetFor(SeedingProfile.FOREGROUND)

    @Test
    fun `前台预算只试一次且不退避`() {
        assertEquals(1, foreground.maxAttempts)
        // 第 0 次失败后就该收工，不再等待
        assertNull(nextBackoffMs(foreground, 0))
    }

    /** 用户在等，整轮必须压在 5 秒判据之内。 */
    @Test
    fun `前台整轮耗时上限低于 5 秒`() {
        assertTrue(
            "前台单次超时 ${foreground.attemptTimeoutMs}ms 不该逼近 5 秒判据",
            foreground.attemptTimeoutMs < 5_000,
        )
    }

    @Test
    fun `后台预算指数退避`() {
        assertEquals(5, background.maxAttempts)
        assertEquals(1_000L, nextBackoffMs(background, 0))
        assertEquals(2_000L, nextBackoffMs(background, 1))
        assertEquals(4_000L, nextBackoffMs(background, 2))
        assertEquals(8_000L, nextBackoffMs(background, 3))
    }

    /** 最后一次尝试失败后不该再白等一轮退避——旧实现正是这样多耗掉一段时间。 */
    @Test
    fun `预算耗尽后不再退避`() {
        assertNull(nextBackoffMs(background, 4))
        assertNull(nextBackoffMs(background, 99))
        assertNull(nextBackoffMs(background, -1))
    }

    @Test
    fun `退避有上限`() {
        val budget = RetryBudget(
            maxAttempts = 20,
            initialBackoffMs = 1_000,
            maxBackoffMs = 8_000,
            attemptTimeoutMs = 1_000,
        )
        assertEquals(8_000L, nextBackoffMs(budget, 5))
        assertEquals(8_000L, nextBackoffMs(budget, 10))
    }

    /** 后台的整体预算应当明显长于前台——这正是拆开两者的意义。 */
    @Test
    fun `后台比前台舍得等`() {
        assertTrue(background.maxAttempts > foreground.maxAttempts)
        assertTrue(background.attemptTimeoutMs > foreground.attemptTimeoutMs)
    }
}
