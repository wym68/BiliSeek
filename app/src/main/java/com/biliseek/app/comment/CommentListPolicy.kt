package com.biliseek.app.comment

import com.biliseek.core.network.dto.ReplyItem

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
