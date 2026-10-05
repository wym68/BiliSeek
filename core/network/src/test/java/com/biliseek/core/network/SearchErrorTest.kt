package com.biliseek.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchErrorTest {

    /**
     * B-4 的机器可验版本。
     *
     * 《架构设计篇》§3.2 把 `-352` 标为「做错会主动伤害用户账号」：
     * UI 一旦给出重试按钮，用户就会猛戳，把临时限流推成长期封禁。
     */
    @Test
    fun `风控错误绝不给重试按钮`() {
        assertFalse(SearchError.Blocked.retryable)
    }

    @Test
    fun `无结果不给重试按钮`() {
        // 重试还是没有，按钮只会让人误以为是加载失败
        assertFalse(SearchError.Empty("不存在的关键词").retryable)
    }

    @Test
    fun `链接不是视频不给重试按钮`() {
        // 重试还是同一个链接，按钮只会让人误以为是加载失败
        assertFalse(SearchError.NotVideo.retryable)
    }

    @Test
    fun `可恢复的失败给重试按钮`() {
        assertTrue(SearchError.Network("timeout").retryable)
        assertTrue(SearchError.CredentialsInvalid.retryable)
        assertTrue(SearchError.NotActivated.retryable)
        assertTrue(SearchError.Unknown("code=-500").retryable)
    }

    @Test
    fun `无结果带上关键词供文案使用`() {
        val error = SearchError.Empty("洛天依")
        assertTrue(error.keyword == "洛天依")
    }
}
