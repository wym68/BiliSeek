package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.ReplyData
import com.biliseek.core.network.internal.runCommentRead
import com.biliseek.core.network.internal.shouldFallbackCommentRead
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B5 评论读降级判据（工单 §4.1）。 */
class CommentReadPolicyTest {

    @Test
    fun `风控与 csrf 相关码触发降级`() {
        assertTrue(shouldFallbackCommentRead(-111))
        assertTrue(shouldFallbackCommentRead(-352))
        assertTrue(shouldFallbackCommentRead(-412))
        assertTrue(shouldFallbackCommentRead(-403))
    }

    @Test
    fun `成功与普通失败不降级`() {
        assertFalse(shouldFallbackCommentRead(0))
        assertFalse(shouldFallbackCommentRead(12345))
    }

    @Test
    fun `主链路成功不走兼容`() = runBlocking {
        var compatCalled = false
        val out = runCommentRead(
            primary = { BiliResponse(code = 0, data = ReplyData()) },
            compat = { compatCalled = true; BiliResponse(code = 0, data = ReplyData()) },
        )
        assertEquals(0, out.code)
        assertFalse("主链路成功不该走兼容", compatCalled)
    }

    @Test
    fun `主链路负一一一降级到兼容并成功`() = runBlocking {
        var compatCalled = false
        val out = runCommentRead(
            primary = { BiliResponse(code = -111, data = null) },
            compat = { compatCalled = true; BiliResponse(code = 0, data = ReplyData(replies = emptyList())) },
        )
        assertTrue("应降级到兼容链路", compatCalled)
        assertEquals(0, out.code)
    }

    @Test
    fun `主链路普通失败不降级直接返回`() = runBlocking {
        var compatCalled = false
        val out = runCommentRead(
            primary = { BiliResponse<ReplyData>(code = 12345) },
            compat = { compatCalled = true; BiliResponse<ReplyData>(code = 0) },
        )
        assertFalse(compatCalled)
        assertEquals(12345, out.code)
    }

    @Test
    fun `兼容链路也失败则返回兼容的失败`() = runBlocking {
        val out = runCommentRead(
            primary = { BiliResponse(code = -352) },
            compat = { BiliResponse<ReplyData>(code = -352) },
        )
        assertEquals(-352, out.code)
    }
}
