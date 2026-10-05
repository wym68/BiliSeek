package com.biliseek.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表滚到底的自动加载：还有更多、没在加载、上一次没失败才加载。
 * 失败后要等用户点「重试」——否则底部的自动加载失败后重新进入组合就会再发一次，成了对 B 站的自动重试循环。
 */
class LoadMorePolicyTest {

    @Test
    fun `还有更多、没在加载、没失败时自动加载`() {
        assertTrue(shouldAutoLoadMore(hasMore = true, isLoadingMore = false, errorMessage = null))
    }

    @Test
    fun `上一次加载失败后不自动再来`() {
        assertFalse(shouldAutoLoadMore(hasMore = true, isLoadingMore = false, errorMessage = "网络错误"))
    }

    @Test
    fun `正在加载时不重复发`() {
        assertFalse(shouldAutoLoadMore(hasMore = true, isLoadingMore = true, errorMessage = null))
    }

    @Test
    fun `没有更多时不加载`() {
        assertFalse(shouldAutoLoadMore(hasMore = false, isLoadingMore = false, errorMessage = null))
    }
}
