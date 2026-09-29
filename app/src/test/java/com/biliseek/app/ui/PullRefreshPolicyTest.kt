package com.biliseek.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 下拉刷新的结果提示。
 *
 * 早期各页面自己判断：「已刷新」永远出不来，收藏夹每次进页面都冒一句假的「已刷新」，
 * 有内容时刷新失败则悄无声息。这里只在「用户下拉 → 真的转起来 → 转完」时提示一次。
 */
class PullRefreshPolicyTest {

    private fun PullRefreshTracker.step(refreshing: Boolean, error: String? = null) =
        onRefreshingChanged(refreshing, error, successText = "已刷新")

    @Test
    fun `没下拉过的加载不提示——首次进页面、别处触发的刷新`() {
        val (t1, toast1) = PullRefreshTracker().step(refreshing = true)
        assertNull(toast1)
        val (_, toast2) = t1.step(refreshing = false)
        assertNull(toast2)
    }

    @Test
    fun `下拉刷新成功提示一次，之后回到初始态`() {
        val pulled = PullRefreshTracker().onPull(isRefreshing = false)
        val (t1, toast1) = pulled.step(refreshing = true)
        assertNull(toast1)
        val (t2, toast2) = t1.step(refreshing = false)
        assertEquals(RefreshToast("已刷新", isError = false), toast2)
        assertEquals(PullRefreshTracker(), t2)
        // 下一轮不是用户下拉的，不再提示
        val (t3, _) = t2.step(refreshing = true)
        assertNull(t3.step(refreshing = false).second)
    }

    @Test
    fun `下拉刷新失败提示原因`() {
        val (t1, _) = PullRefreshTracker().onPull(isRefreshing = false).step(refreshing = true)
        val (_, toast) = t1.step(refreshing = false, error = "code=-352")
        assertEquals(RefreshToast("刷新失败：code=-352", isError = true), toast)
    }

    @Test
    fun `下拉时已在刷新，这次刷新的结果就是本次的结果`() {
        val pulled = PullRefreshTracker().onPull(isRefreshing = true)
        assertEquals(RefreshToast("已刷新", isError = false), pulled.step(refreshing = false).second)
    }

    @Test
    fun `请求被挡掉没转起来，不提示`() {
        val pulled = PullRefreshTracker().onPull(isRefreshing = false)
        val (t1, toast) = pulled.step(refreshing = false)
        assertNull(toast)
        assertEquals(pulled, t1)
    }
}
