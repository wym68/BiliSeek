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
