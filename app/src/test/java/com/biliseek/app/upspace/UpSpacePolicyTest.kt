package com.biliseek.app.upspace

import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.dto.SpaceVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpSpacePolicyTest {

    private fun videos(vararg bvids: String) = bvids.map { SpaceVideo(bvid = it) }

    private fun page(page: Int, vararg bvids: String, hasMore: Boolean = true) =
        BiliRepository.SpaceArchivePage(items = videos(*bvids), page = page, hasMore = hasMore, total = 100)

    @Test
    fun `投稿第1页替换列表并记下页码 下一次加载更多才会去拉第2页`() {
        val loading = RemoteListState<SpaceVideo>(items = videos("OLD"), isLoading = true)
        val next = UpSpacePolicy.applyPostsPage(loading, page(1, "BV1", "BV2"), loadingMore = false)
        assertEquals(listOf("BV1", "BV2"), next.items.map { it.bvid })
        // 不记页码时这里是 0，「加载更多」会去拉 0 + 1 = 第 1 页，永远翻不过去
        assertEquals(1, next.page)
        assertTrue(next.hasMore)
        assertFalse(next.isLoading)
        assertFalse(next.isLoadingMore)
        assertNull(next.errorMessage)
    }

    @Test
    fun `投稿往下翻接在后面并记下第2页`() {
        val base = RemoteListState(items = videos("BV1", "BV2"), page = 1, hasMore = true, isLoadingMore = true)
        val next = UpSpacePolicy.applyPostsPage(base, page(2, "BV3", "BV4", hasMore = false), loadingMore = true)
        assertEquals(listOf("BV1", "BV2", "BV3", "BV4"), next.items.map { it.bvid })
        assertEquals(2, next.page)
        assertFalse(next.hasMore)
        assertFalse(next.isLoadingMore)
    }

    @Test
    fun `翻页期间UP发了新视频 后一页和前一页重叠的不重复`() {
        val base = RemoteListState(items = videos("BV1", "BV2"), page = 1, hasMore = true, isLoadingMore = true)
        val next = UpSpacePolicy.applyPostsPage(base, page(2, "BV2", "BV3"), loadingMore = true)
        assertEquals(listOf("BV1", "BV2", "BV3"), next.items.map { it.bvid })
    }

    private fun session(uid: String, sessData: String = "sess") =
        BiliSession(sessData = sessData, biliJct = "jct", dedeUserId = uid, refreshToken = "")

    @Test
    fun `登录账号的 mid 与 UP 相同就是本人`() {
        assertTrue(UpSpacePolicy.isSelf(12345, session("12345")))
    }

    @Test
    fun `别人的空间不是本人`() {
        assertFalse(UpSpacePolicy.isSelf(67890, session("12345")))
    }

    @Test
    fun `未登录一律不算本人`() {
        assertFalse(UpSpacePolicy.isSelf(12345, BiliSession.EMPTY))
        // 本地残留 uid 但没有 SESSDATA，也按未登录处理
        assertFalse(UpSpacePolicy.isSelf(12345, session("12345", sessData = "")))
    }

    @Test
    fun `mid 无效时不算本人`() {
        assertFalse(UpSpacePolicy.isSelf(0, session("0")))
        assertFalse(UpSpacePolicy.isSelf(12345, session("")))
        assertFalse(UpSpacePolicy.isSelf(12345, session("abc")))
    }
}
