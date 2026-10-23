package com.biliseek.app.comment

import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.BiliSession
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
    fun `楼中楼翻页时本地插入过的那条不会重复`() {
        val merged = mergeCommentPage(existing = listOf(r(3), r(500)), incoming = listOf(r(4), r(500)), pinned = emptyList())
        assertEquals(listOf(3L, 500L, 4L), merged.map { it.rpid })
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

    private val me = BiliSession(sessData = "s", biliJct = "j", dedeUserId = "42", refreshToken = "r", uname = "我", face = "//f.jpg")

    @Test
    fun `buildPostedReply 用自己的头像昵称`() {
        val r = buildPostedReply(rpid = 500, oid = 1, message = "你好", session = me, nowSec = 1700)
        assertEquals(500L, r.rpid)
        assertEquals(42L, r.mid)
        assertEquals("我", r.member.uname)
        assertEquals("//f.jpg", r.member.avatar)
        assertEquals("你好", r.content.message)
        assertEquals(1700L, r.ctime)
    }

    @Test
    fun `一级评论插到普通列表最前面`() {
        val state = CommentThreadState(oid = 1, type = 1, comments = RemoteListState(items = listOf(r(2))))
        val out = state.insertPosted(r(500), target = null)
        assertEquals(listOf(500L, 2L), out.comments.items.map { it.rpid })
    }

    @Test
    fun `回复未展开的一级评论：进预览、回复数加一`() {
        val state = CommentThreadState(oid = 1, type = 1, comments = RemoteListState(items = listOf(ReplyItem(rpid = 2, rcount = 0))))
        val out = state.insertPosted(r(500), CommentReplyTarget(root = 2, parent = 2, uname = "x"))
        val root = out.comments.items.single()
        assertEquals(1, root.rcount)
        assertEquals(listOf(500L), root.replies!!.map { it.rpid })
    }

    @Test
    fun `回复已展开的一级评论：进展开列表末尾、回复数加一`() {
        val state = CommentThreadState(
            oid = 1, type = 1,
            comments = RemoteListState(items = listOf(ReplyItem(rpid = 2, rcount = 1, replies = listOf(r(3))))),
            expandedSubReplies = mapOf(2L to RemoteListState(items = listOf(r(3)))),
        )
        val out = state.insertPosted(r(500), CommentReplyTarget(root = 2, parent = 3, uname = "x"))
        assertEquals(2, out.comments.items.single().rcount)
        assertEquals(listOf(3L), out.comments.items.single().replies!!.map { it.rpid })
        assertEquals(listOf(3L, 500L), out.expandedSubReplies.getValue(2).items.map { it.rpid })
    }
}
