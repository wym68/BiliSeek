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

    @Test
    fun `只能删自己的评论，未登录谁的都不能删`() {
        assertEquals(true, canDeleteReply(ReplyItem(rpid = 1, mid = 42), myMid = 42))
        assertEquals(false, canDeleteReply(ReplyItem(rpid = 1, mid = 7), myMid = 42))
        assertEquals(false, canDeleteReply(ReplyItem(rpid = 1, mid = 0), myMid = 0))
    }

    @Test
    fun `删一级评论：从置顶、列表移除，并丢掉它展开的楼中楼`() {
        val state = CommentThreadState(
            oid = 1, type = 1,
            topReplies = listOf(r(2)),
            comments = RemoteListState(items = listOf(r(2), r(3))),
            expandedSubReplies = mapOf(2L to RemoteListState(items = listOf(r(9)))),
        )
        val out = state.removeReply(rpid = 2, rootRpid = null)
        assertEquals(emptyList<Long>(), out.topReplies.map { it.rpid })
        assertEquals(listOf(3L), out.comments.items.map { it.rpid })
        assertEquals(false, out.expandedSubReplies.containsKey(2L))
    }

    @Test
    fun `删楼中楼：预览和展开列表都去掉，回复数减一`() {
        val state = CommentThreadState(
            oid = 1, type = 1,
            comments = RemoteListState(items = listOf(ReplyItem(rpid = 2, rcount = 2, replies = listOf(r(8), r(9))))),
            expandedSubReplies = mapOf(2L to RemoteListState(items = listOf(r(8), r(9)))),
        )
        val out = state.removeReply(rpid = 9, rootRpid = 2)
        assertEquals(1, out.comments.items.single().rcount)
        assertEquals(listOf(8L), out.comments.items.single().replies!!.map { it.rpid })
        assertEquals(listOf(8L), out.expandedSubReplies.getValue(2).items.map { it.rpid })
    }

    @Test
    fun `没有游标就没有下一页`() {
        assertEquals(false, resolveCommentHasMore(reset = true, sizeBefore = 0, sizeAfter = 20, nextOffset = null))
        assertEquals(false, resolveCommentHasMore(reset = false, sizeBefore = 20, sizeAfter = 40, nextOffset = null))
    }

    @Test
    fun `第一页有游标就还有`() {
        assertEquals(true, resolveCommentHasMore(reset = true, sizeBefore = 0, sizeAfter = 20, nextOffset = "n"))
    }

    @Test
    fun `翻页一条新的都没加进来就当到底，免得底部一直转圈`() {
        assertEquals(false, resolveCommentHasMore(reset = false, sizeBefore = 20, sizeAfter = 20, nextOffset = "n"))
        assertEquals(true, resolveCommentHasMore(reset = false, sizeBefore = 20, sizeAfter = 25, nextOffset = "n"))
    }

    @Test
    fun `收起的楼中楼当作没展开，已加载的还留着`() {
        val loaded = RemoteListState(items = listOf(r(8), r(9)))
        val state = CommentThreadState(oid = 1, type = 1, expandedSubReplies = mapOf(2L to loaded))
        assertEquals(loaded, state.visibleSubReplies(2))
        val collapsed = state.copy(collapsedSubReplies = setOf(2L))
        assertEquals(null, collapsed.visibleSubReplies(2))
        assertEquals(loaded, collapsed.expandedSubReplies[2L])
        assertEquals(null, state.visibleSubReplies(3))
    }

    @Test
    fun `回复提示带上原文，换行和多余空白压成一个空格`() {
        assertEquals(
            "回复 @小明：这期讲得 太好了",
            replyHintText(CommentReplyTarget(root = 1, parent = 1, uname = "小明", content = " 这期讲得\n\n太好了  ")),
        )
    }

    @Test
    fun `原文为空只写昵称`() {
        assertEquals("回复 @小明", replyHintText(CommentReplyTarget(root = 1, parent = 1, uname = "小明")))
    }

    @Test
    fun `回复已收起的楼中楼会自动展开，能看到刚发的那条`() {
        val state = CommentThreadState(
            oid = 1, type = 1,
            comments = RemoteListState(items = listOf(ReplyItem(rpid = 2, rcount = 1))),
            expandedSubReplies = mapOf(2L to RemoteListState(items = listOf(r(3)))),
            collapsedSubReplies = setOf(2L),
        )
        val out = state.insertPosted(r(500), CommentReplyTarget(root = 2, parent = 3, uname = "x"))
        assertEquals(false, 2L in out.collapsedSubReplies)
        assertEquals(listOf(3L, 500L), out.visibleSubReplies(2)!!.items.map { it.rpid })
    }

    @Test
    fun `删一级评论也清掉它的收起记录`() {
        val state = CommentThreadState(
            oid = 1, type = 1,
            comments = RemoteListState(items = listOf(r(2))),
            expandedSubReplies = mapOf(2L to RemoteListState(items = listOf(r(9)))),
            collapsedSubReplies = setOf(2L),
        )
        assertEquals(false, 2L in state.removeReply(rpid = 2, rootRpid = null).collapsedSubReplies)
    }

    @Test
    fun `楼中楼第一页：没展开过或第一页失败才拉`() {
        assertEquals(true, shouldLoadFirstSubReplyPage(null))
        assertEquals(true, shouldLoadFirstSubReplyPage(RemoteListState<ReplyItem>(errorMessage = "楼中楼加载失败")))
        assertEquals(false, shouldLoadFirstSubReplyPage(RemoteListState<ReplyItem>(isLoading = true)))
        assertEquals(false, shouldLoadFirstSubReplyPage(RemoteListState(items = listOf(r(1)))))
        assertEquals(false, shouldLoadFirstSubReplyPage(RemoteListState(items = listOf(r(1)), errorMessage = "楼中楼加载失败")))
    }

    @Test
    fun `回到藏起弹层的那个页面才恢复`() {
        val hidden = CommentThreadState(oid = 1, type = 1, content = "没写完", hiddenUntilEntry = "dyn-1")
        assertEquals(null, hidden.resumedOn("dyn-1").hiddenUntilEntry)
        assertEquals("没写完", hidden.resumedOn("dyn-1").content)
        assertEquals("dyn-1", hidden.resumedOn("up-2").hiddenUntilEntry)
        assertEquals("dyn-1", hidden.resumedOn(null).hiddenUntilEntry)
        val shown = CommentThreadState(oid = 1, type = 1)
        assertEquals(shown, shown.resumedOn("dyn-1"))
    }

    @Test
    fun `播放页评论区不是当前视频的就关掉`() {
        val a = CommentThreadState(oid = 1, type = 1, key = "BV1aaa")
        assertEquals(false, shouldDropPlayerComments(null, "BV1bbb"))
        assertEquals(false, shouldDropPlayerComments(a, "BV1aaa"))
        assertEquals(true, shouldDropPlayerComments(a, "BV1bbb"))
        assertEquals(true, shouldDropPlayerComments(a.copy(hiddenUntilEntry = "player-1"), "BV1bbb"))
        assertEquals(true, shouldDropPlayerComments(a, ""))
    }
}
