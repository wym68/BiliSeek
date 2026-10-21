package com.biliseek.core.network.internal

import com.biliseek.core.network.BiliRepository.MessageUnreadSummary
import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.MsgfeedUnreadData
import com.biliseek.core.network.dto.SessionUnreadData

/**
 * 两路未读（互动 `x/msgfeed/unread` + 私信 `single_unread`）合成角标。
 *
 * 只有两路都成功（code=0 且带 data）才给结果；任一路失败返回 null，调用方保留旧角标。
 * 否则失败的那一路按 0 算，角标会忽然变小/消失、下次刷新又冒出来。
 * `null` 入参表示请求本身抛了异常。
 */
internal fun resolveUnreadSummary(
    msgfeed: BiliResponse<MsgfeedUnreadData>?,
    session: BiliResponse<SessionUnreadData>?,
): MessageUnreadSummary? {
    val feed = msgfeed?.takeIf { it.code == 0 }?.data ?: return null
    val chat = session?.takeIf { it.code == 0 }?.data ?: return null
    return MessageUnreadSummary(
        reply = feed.reply,
        at = feed.at,
        like = feed.like,
        sessionUnread = chat.unfollowUnread + chat.followUnread,
    )
}
