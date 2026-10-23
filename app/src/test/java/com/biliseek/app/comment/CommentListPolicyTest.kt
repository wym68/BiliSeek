package com.biliseek.app.comment

import com.biliseek.core.network.dto.ReplyItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CommentListPolicyTest {

    private fun r(id: Long) = ReplyItem(rpid = id)

    @Test
    fun `翻页合并丢掉重复项和置顶里已有的`() {
        val merged = mergeCommentPage(
            existing = listOf(r(2), r(3)),
            incoming = listOf(r(3), r(1), r(4)),
            pinned = listOf(r(1)),
        )
        assertEquals(listOf(2L, 3L, 4L), merged.map { it.rpid })
    }

    @Test
    fun `第一页也要去掉置顶那条`() {
        val merged = mergeCommentPage(existing = emptyList(), incoming = listOf(r(1), r(5)), pinned = listOf(r(1)))
        assertEquals(listOf(5L), merged.map { it.rpid })
    }

    @Test
    fun `patchReply 改到所有出现的位置`() {
        val sub = ReplyItem(rpid = 7, like = 1)
        val state = CommentThreadState(
            oid = 1, type = 1,
            topReplies = listOf(ReplyItem(rpid = 7, like = 1)),
            comments = com.biliseek.app.data.RemoteListState(items = listOf(ReplyItem(rpid = 3, replies = listOf(sub)))),
            expandedSubReplies = mapOf(3L to com.biliseek.app.data.RemoteListState(items = listOf(sub))),
        )
        val out = state.patchReply(7) { it.withLike(true) }
        assertEquals(2, out.topReplies.single().like)
        assertEquals(2, out.comments.items.single().replies!!.single().like)
        assertEquals(1, out.comments.items.single().replies!!.single().action)
        assertEquals(2, out.expandedSubReplies.getValue(3).items.single().like)
        assertEquals(0, out.comments.items.single().like)
    }

    @Test
    fun `取消点赞不会减成负数`() {
        val r = ReplyItem(rpid = 1, like = 0, action = 1).withLike(false)
        assertEquals(0, r.like)
        assertEquals(0, r.action)
    }
}
