package com.biliseek.app.remote

import com.biliseek.core.player.PlaybackRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 全部走假的 fetch，不碰 B 站。 */
class PagedQueueSourceTest {

    private fun items(vararg bvids: String) = bvids.map { PlaybackRequest.Bili(it) }

    @Test
    fun `按页码依次续页，最后一页之后不再请求`() = runBlocking {
        val asked = mutableListOf<Int>()
        val source = PagedQueueSource(nextPage = 2, hasMore = true) { page ->
            asked += page
            Result.success(QueuePage(items("BV$page"), hasMore = page < 3))
        }
        assertEquals(items("BV2"), source.next())
        assertEquals(items("BV3"), source.next())
        assertNull(source.next())
        assertEquals(listOf(2, 3), asked)
    }

    @Test
    fun `一开始就没有更多时一次都不请求`() = runBlocking {
        var calls = 0
        val source = PagedQueueSource(nextPage = 2, hasMore = false) {
            calls++
            Result.success(QueuePage(items("BVx"), hasMore = true))
        }
        assertNull(source.next())
        assertEquals(0, calls)
    }

    @Test
    fun `请求失败后停止，不重试`() = runBlocking {
        var calls = 0
        val source = PagedQueueSource(nextPage = 2, hasMore = true) {
            calls++
            Result.failure(IllegalStateException("-352"))
        }
        assertNull(source.next())
        assertNull(source.next())
        assertEquals(1, calls)
    }

    @Test
    fun `空页视为没有更多`() = runBlocking {
        var calls = 0
        val source = PagedQueueSource(nextPage = 2, hasMore = true) {
            calls++
            Result.success(QueuePage(emptyList(), hasMore = true))
        }
        assertNull(source.next())
        assertNull(source.next())
        assertEquals(1, calls)
    }
}
