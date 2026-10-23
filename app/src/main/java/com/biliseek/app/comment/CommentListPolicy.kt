package com.biliseek.app.comment

import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.dto.ReplyContent
import com.biliseek.core.network.dto.ReplyItem
import com.biliseek.core.network.dto.ReplyMember

/**
 * 翻页合并：追加新一页，丢掉已经有的、以及置顶里出现过的 rpid
 * （列表按 rpid 当 key，重复会崩；置顶那条服务端可能在普通列表里再给一次）。
 */
fun mergeCommentPage(
    existing: List<ReplyItem>,
    incoming: List<ReplyItem>,
    pinned: List<ReplyItem>,
): List<ReplyItem> {
    val seen = pinned.mapTo(HashSet()) { it.rpid }
    return (existing + incoming).filter { seen.add(it.rpid) }
}

/**
 * 对整个评论区里 rpid 相同的那条做同一个修改：同一条可能同时出现在置顶、普通列表、
 * 一级评论自带的楼中楼预览、已展开的楼中楼里。
 */
fun CommentThreadState.patchReply(rpid: Long, transform: (ReplyItem) -> ReplyItem): CommentThreadState {
    fun patch(item: ReplyItem): ReplyItem {
        val self = if (item.rpid == rpid) transform(item) else item
        val subs = self.replies ?: return self
        return self.copy(replies = subs.map { if (it.rpid == rpid) transform(it) else it })
    }
    return copy(
        topReplies = topReplies.map(::patch),
        comments = comments.copy(items = comments.items.map(::patch)),
        expandedSubReplies = expandedSubReplies.mapValues { (_, sub) -> sub.copy(items = sub.items.map(::patch)) },
    )
}

/** 点赞 / 取消后的样子：赞数 ±1（不小于 0），action 1=已赞。 */
fun ReplyItem.withLike(like: Boolean): ReplyItem =
    copy(like = (this.like + if (like) 1 else -1).coerceAtLeast(0), action = if (like) 1 else 0)

/** 刚发出去的评论在本地的样子：自己的头像昵称 + 输入的原文（表情按 `[xx]` 文本由富文本渲染）。 */
fun buildPostedReply(rpid: Long, oid: Long, message: String, session: BiliSession, nowSec: Long): ReplyItem =
    ReplyItem(
        rpid = rpid,
        oid = oid,
        mid = session.dedeUserId.toLongOrNull() ?: 0L,
        ctime = nowSec,
        content = ReplyContent(message = message),
        member = ReplyMember(mid = session.dedeUserId, uname = session.uname, avatar = session.face),
    )

/**
 * 发送成功后把自己的评论直接放进列表，不重拉（按热度排时新评论多半不在第一页，重拉了也看不到）。
 * 一级评论插到普通列表最前；回复插进所属一级评论：已展开放展开列表末尾，没展开放预览末尾；回复数 +1；该楼中楼若已收起会自动展开。
 */
fun CommentThreadState.insertPosted(reply: ReplyItem, target: CommentReplyTarget?): CommentThreadState {
    if (target == null) return copy(comments = comments.copy(items = listOf(reply) + comments.items))
    val rootRpid = target.root
    val expanded = expandedSubReplies[rootRpid]
    val patched = patchReply(rootRpid) { root ->
        root.copy(
            rcount = root.rcount + 1,
            replies = if (expanded != null) root.replies else root.replies.orEmpty() + reply,
        )
    }
    if (expanded == null) return patched
    return patched.copy(
        expandedSubReplies = patched.expandedSubReplies + (rootRpid to expanded.copy(items = expanded.items + reply)),
        collapsedSubReplies = patched.collapsedSubReplies - rootRpid,
    )
}

/** 只能删自己的评论（UP 删别人评论的权限不做）。 */
fun canDeleteReply(reply: ReplyItem, myMid: Long): Boolean = myMid > 0 && reply.mid == myMid

/**
 * 删除成功后从本地移除。`rootRpid == null`：一级评论，置顶 / 列表里去掉，它展开的楼中楼一并丢掉；
 * 否则是楼中楼：所属一级评论的预览和展开列表里去掉，回复数 -1。
 */
fun CommentThreadState.removeReply(rpid: Long, rootRpid: Long?): CommentThreadState {
    if (rootRpid == null) {
        return copy(
            topReplies = topReplies.filterNot { it.rpid == rpid },
            comments = comments.copy(items = comments.items.filterNot { it.rpid == rpid }),
            expandedSubReplies = expandedSubReplies - rpid,
        )
    }
    val patched = patchReply(rootRpid) { root ->
        root.copy(
            rcount = (root.rcount - 1).coerceAtLeast(0),
            replies = root.replies?.filterNot { it.rpid == rpid },
        )
    }
    val expanded = patched.expandedSubReplies[rootRpid] ?: return patched
    return patched.copy(
        expandedSubReplies = patched.expandedSubReplies + (rootRpid to expanded.copy(items = expanded.items.filterNot { it.rpid == rpid })),
    )
}

/**
 * 主评论还有没有下一页：服务端没给游标就没有；翻页（非第一页）一条新的都没加进来也当到底，
 * 否则底部 footer 的自动加载（按条数触发）不会再触发，会一直转圈。
 */
fun resolveCommentHasMore(reset: Boolean, sizeBefore: Int, sizeAfter: Int, nextOffset: String?): Boolean =
    nextOffset != null && (reset || sizeAfter > sizeBefore)

/** 界面上该一级评论的楼中楼按「已展开」显示的状态；没展开或已收起返回 null（显示预览 +「查看全部」）。 */
fun CommentThreadState.visibleSubReplies(rpid: Long): RemoteListState<ReplyItem>? =
    expandedSubReplies[rpid]?.takeUnless { rpid in collapsedSubReplies }

/** 输入框上方的回复提示：「回复 @昵称：原文」，原文里的换行 / 多余空白压成一个空格；原文为空只写昵称。截断交给界面单行省略。 */
fun replyHintText(target: CommentReplyTarget): String {
    val quote = target.content.replace(Regex("\\s+"), " ").trim()
    return if (quote.isEmpty()) "回复 @${target.uname}" else "回复 @${target.uname}：$quote"
}
