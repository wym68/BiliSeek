package com.biliseek.core.network

import com.biliseek.core.network.dto.ReplyCursor
import com.biliseek.core.network.dto.ReplyData
import com.biliseek.core.network.dto.ReplyItem
import com.biliseek.core.network.dto.ReplyPaginationReply
import com.biliseek.core.network.dto.ReplyTop
import com.biliseek.core.network.dto.ReplyUpper
import com.biliseek.core.network.internal.collectTopReplies
import com.biliseek.core.network.internal.commentListParams
import com.biliseek.core.network.internal.resolveCommentMode
import com.biliseek.core.network.internal.resolveNextOffset
import com.biliseek.core.network.internal.toCommentPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主评论列表：mode 排序 + pagination_str 游标翻页 + 置顶合并。 */
class CommentPagePolicyTest {

    @Test
    fun `排序映射到 mode`() {
        assertEquals(3, resolveCommentMode(1))
        assertEquals(2, resolveCommentMode(0))
    }

    @Test
    fun `第一页 pagination_str 的 offset 为空串，不再带 pn 和 sort`() {
        val p = commentListParams(oid = 100, type = 1, sort = 1, offset = "")
        assertEquals("100", p["oid"])
        assertEquals("1", p["type"])
        assertEquals("3", p["mode"])
        assertEquals("20", p["ps"])
        assertEquals("""{"offset":""}""", p["pagination_str"])
        assertFalse(p.containsKey("pn"))
        assertFalse(p.containsKey("sort"))
    }

    @Test
    fun `翻页把 next_offset 原样放进 pagination_str`() {
        val p = commentListParams(oid = 1, type = 17, sort = 0, offset = """{"type":1,"data":{"pn":2}}""")
        assertEquals("2", p["mode"])
        assertEquals("""{"offset":"{\"type\":1,\"data\":{\"pn\":2}}"}""", p["pagination_str"])
    }

    @Test
    fun `置顶合并并按 rpid 去重`() {
        val a = ReplyItem(rpid = 1)
        val b = ReplyItem(rpid = 2)
        val data = ReplyData(topReplies = listOf(a, b), top = ReplyTop(upper = a), upper = ReplyUpper(mid = 9, top = a))
        assertEquals(listOf(1L, 2L), collectTopReplies(data).map { it.rpid })
    }

    @Test
    fun `到底或没有游标就没有下一页`() {
        assertNull(resolveNextOffset(ReplyCursor(isEnd = true, paginationReply = ReplyPaginationReply("x"))))
        assertNull(resolveNextOffset(ReplyCursor(isEnd = false, paginationReply = null)))
        assertNull(resolveNextOffset(ReplyCursor(isEnd = false, paginationReply = ReplyPaginationReply(""))))
        assertEquals("x", resolveNextOffset(ReplyCursor(isEnd = false, paginationReply = ReplyPaginationReply("x"))))
    }

    @Test
    fun `只有第一页带置顶，作者与游标照取`() {
        val data = ReplyData(
            replies = listOf(ReplyItem(rpid = 5)),
            topReplies = listOf(ReplyItem(rpid = 1)),
            upper = ReplyUpper(mid = 9),
            cursor = ReplyCursor(allCount = 50, paginationReply = ReplyPaginationReply("n")),
        )
        val first = toCommentPage(data, firstPage = true)
        assertEquals(listOf(5L), first.items.map { it.rpid })
        assertEquals(listOf(1L), first.topReplies.map { it.rpid })
        assertEquals(9L, first.upMid)
        assertEquals("n", first.nextOffset)
        assertEquals(50, first.count)
        assertTrue(toCommentPage(data, firstPage = false).topReplies.isEmpty())
        assertNull(toCommentPage(null, firstPage = true).nextOffset)
    }
}
