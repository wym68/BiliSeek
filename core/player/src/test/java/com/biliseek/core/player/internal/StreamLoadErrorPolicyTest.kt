package com.biliseek.core.player.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 分片加载失败的重试节奏。
 *
 * 判据来自《手动测试指南》步骤 26 / 27：缓冲耗尽可以卡住，但**网络一回来就要自动续上**，
 * 不需要手动重新点播放。Media3 默认第 4 次失败就判死，这一条永远过不了。
 */
class StreamLoadErrorPolicyTest {

    @Test
    fun `没网时网络类失败一直等，退避封顶 5 秒`() {
        assertEquals(0L, resolveLoadRetryDelayMs(LoadFailure.NETWORK, errorCount = 1, deviceOnline = false))
        assertEquals(1_000L, resolveLoadRetryDelayMs(LoadFailure.NETWORK, errorCount = 2, deviceOnline = false))
        assertEquals(5_000L, resolveLoadRetryDelayMs(LoadFailure.NETWORK, errorCount = 6, deviceOnline = false))
        assertEquals(5_000L, resolveLoadRetryDelayMs(LoadFailure.NETWORK, errorCount = 500, deviceOnline = false))
    }

    @Test
    fun `有网却连不上是线路的问题，试 3 次就上浮去换新链接`() {
        assertEquals(2_000L, resolveLoadRetryDelayMs(LoadFailure.NETWORK, errorCount = 3, deviceOnline = true))
        assertNull(resolveLoadRetryDelayMs(LoadFailure.NETWORK, errorCount = 4, deviceOnline = true))
    }

    @Test
    fun `错误状态码试 3 次就上浮——过期的直链再怎么试也是 403`() {
        assertEquals(0L, resolveLoadRetryDelayMs(LoadFailure.HTTP_STATUS, errorCount = 1, deviceOnline = true))
        assertEquals(2_000L, resolveLoadRetryDelayMs(LoadFailure.HTTP_STATUS, errorCount = 3, deviceOnline = true))
        assertNull(resolveLoadRetryDelayMs(LoadFailure.HTTP_STATUS, errorCount = 4, deviceOnline = true))
        // 没网时拿到的状态码也不是网络问题，一样不无限等
        assertNull(resolveLoadRetryDelayMs(LoadFailure.HTTP_STATUS, errorCount = 4, deviceOnline = false))
    }

    @Test
    fun `有网时连不上当前线路就换下一条`() {
        assertEquals(true, shouldSwitchCdnOnFailure(LoadFailure.NETWORK, deviceOnline = true, anotherLocation = true))
    }

    @Test
    fun `没网时不换线路——所有线路都会失败，换了只会把好线路也排除掉`() {
        assertEquals(false, shouldSwitchCdnOnFailure(LoadFailure.NETWORK, deviceOnline = false, anotherLocation = true))
    }

    @Test
    fun `没有别的线路可换时不做任何兜底（不降码率换轨）`() {
        assertEquals(false, shouldSwitchCdnOnFailure(LoadFailure.NETWORK, deviceOnline = true, anotherLocation = false))
    }

    @Test
    fun `其他可重试的失败同样试 3 次`() {
        assertEquals(1_000L, resolveLoadRetryDelayMs(LoadFailure.OTHER, errorCount = 2, deviceOnline = false))
        assertNull(resolveLoadRetryDelayMs(LoadFailure.OTHER, errorCount = 4, deviceOnline = false))
    }
}
