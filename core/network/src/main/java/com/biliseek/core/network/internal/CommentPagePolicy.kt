package com.biliseek.core.network.internal

import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.dto.ReplyCursor
import com.biliseek.core.network.dto.ReplyData
import com.biliseek.core.network.dto.ReplyItem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 界面排序（1 热度 / 0 时间）→ `wbi/main` 的 mode（3 热度 / 2 时间）。 */
internal fun resolveCommentMode(sort: Int): Int = if (sort == 0) 2 else 3

/**
 * 主评论列表参数（`wbi/main` 与兼容链路 `main` 共用）。`wbi/main` 不认 `pn/sort`：
 * 排序走 `mode`，翻页走 `pagination_str = {"offset": 上一页的 next_offset}`，第一页 offset 为空串。
 */
internal fun commentListParams(oid: Long, type: Int, sort: Int, offset: String): Map<String, String> = mapOf(
    "oid" to oid.toString(),
    "type" to type.toString(),
    "mode" to resolveCommentMode(sort).toString(),
    "ps" to "20",
    "plat" to "1",
    "web_location" to "1315875",
    "pagination_str" to buildJsonObject { put("offset", offset) }.toString(),
)

/** 置顶评论：`top.upper/admin/vote` + `upper.top` + `top_replies`，按 rpid 去重。 */
internal fun collectTopReplies(data: ReplyData): List<ReplyItem> =
    listOfNotNull(data.top?.upper, data.top?.admin, data.top?.vote, data.upper?.top)
        .plus(data.topReplies.orEmpty())
        .filter { it.rpid > 0 }
        .distinctBy { it.rpid }

/** 下一页游标：服务端说到底了、或没给 next_offset 就没有下一页（免得反复拉同一页）。 */
internal fun resolveNextOffset(cursor: ReplyCursor): String? =
    cursor.paginationReply?.nextOffset?.takeIf { !cursor.isEnd && it.isNotBlank() }

/** 响应 → [BiliRepository.CommentPage]。置顶只在第一页取。 */
internal fun toCommentPage(data: ReplyData?, firstPage: Boolean): BiliRepository.CommentPage =
    BiliRepository.CommentPage(
        items = data?.replies.orEmpty(),
        count = data?.cursor?.allCount ?: 0,
        topReplies = if (firstPage && data != null) collectTopReplies(data) else emptyList(),
        upMid = data?.upper?.mid ?: 0L,
        nextOffset = data?.cursor?.let(::resolveNextOffset),
    )
