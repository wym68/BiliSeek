package com.biliseek.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 流量策略。
 *
 * 判据只有一条真正要紧：**Wi-Fi 下永远不拦**。
 * 一个把「省流量」做成「Wi-Fi 也看不了视频」的开关，用户只会去关掉它。
 */
class VideoGatePolicyTest {

    private val default = AppSettings()

    @Test
    fun `非计量网络一律放行`() {
        assertEquals(VideoGate.ALLOW, resolveVideoGate(default, isMetered = false))
        assertEquals(
            VideoGate.ALLOW,
            resolveVideoGate(default.copy(allowVideoOnMetered = false), isMetered = false),
        )
    }

    @Test
    fun `计量网络下默认拦住`() {
        assertEquals(VideoGate.BLOCKED_BY_METERED, resolveVideoGate(default, isMetered = true))
    }

    @Test
    fun `播的是下载好的视频文件 计量网络也放行`() {
        assertEquals(VideoGate.ALLOW, resolveVideoGate(default, isMetered = true, playingLocalVideo = true))
    }

    @Test
    fun `用户显式允许后计量网络也放行`() {
        assertEquals(
            VideoGate.ALLOW,
            resolveVideoGate(default.copy(allowVideoOnMetered = true), isMetered = true),
        )
    }
}
