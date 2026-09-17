package com.biliseek.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ───────────────── 私信会话/消息（api.vc.bilibili.com）─────────────────

/** `get_sessions` 一条会话。 */
@Serializable
data class Session(
    @SerialName("talker_id") val talkerId: Long = 0,
    @SerialName("session_type") val sessionType: Int = 0,
    @SerialName("top_ts") val topTs: Long = 0,
    @SerialName("ack_ts") val ackTs: Long = 0,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("last_msg") val lastMsg: LastMessage? = null,
    @SerialName("account_info") val accountInfo: SessionPeer? = null,
)

/** `last_msg.content` 是 JSON 字符串，按 msg_type 解析。纯文本 (msg_type=1) 直接展示。 */
@Serializable
data class LastMessage(
    @SerialName("sender_uid") val senderUid: Long = 0,
    @SerialName("msg_type") val msgType: Int = 0,
    @SerialName("content") val content: String = "",
    @SerialName("timestamp") val timestamp: Long = 0,
    @SerialName("msg_seqno") val msgSeqno: Long = 0,
)

/** 对方用户卡片（昵称/头像）。 */
@Serializable
data class SessionPeer(
    @SerialName("name") val name: String = "",
    @SerialName("face") val face: String = "",
    @SerialName("mid") val mid: Long = 0,
)

@Serializable
data class SessionListData(
    @SerialName("session_list") val sessionList: List<Session> = emptyList(),
    @SerialName("has_more") val hasMore: Int = 0,
)

/** `fetch_session_msgs` 一条消息。 */
@Serializable
data class PrivateMessage(
    @SerialName("sender_uid") val senderUid: Long = 0,
    @SerialName("receiver_id") val receiverId: Long = 0,
    @SerialName("msg_type") val msgType: Int = 0,
    /** 文本消息是 JSON 字符串：`{"content":"hello"}`。 */
    @SerialName("content") val content: String = "",
    @SerialName("timestamp") val timestamp: Long = 0,
    @SerialName("msg_seqno") val msgSeqno: Long = 0,
)

@Serializable
data class MessageHistoryData(
    @SerialName("messages") val messages: List<PrivateMessage> = emptyList(),
    @SerialName("max_seqno") val maxSeqno: Long = 0,
    @SerialName("min_seqno") val minSeqno: Long = 0,
    @SerialName("has_more") val hasMore: Int = 0,
)

/** `send_msg` 返回。msg_seqno 用于本地立即插入。 */
@Serializable
data class SendMessageData(
    @SerialName("msg_seqno") val msgSeqno: Long = 0,
    @SerialName("msg_key") val msgKey: Long = 0,
)

// ───────────────── 互动消息（api.bilibili.com x/msgfeed）─────────────────

/** 回复/@/点赞通知里的用户信息。真实字段名是 nickname/avatar，不是 name/face。 */
@Serializable
data class MsgfeedUser(
    @SerialName("mid") val mid: Long = 0,
    @SerialName("nickname") val name: String = "",
    @SerialName("avatar") val face: String = "",
)

/** msgfeed 分页游标：`is_end=true` 表示没有更多了。 */
@Serializable
data class MsgfeedCursor(
    @SerialName("id") val id: Long = 0,
    @SerialName("time") val time: Long = 0,
    @SerialName("is_end") val isEnd: Boolean = false,
)

/** `x/msgfeed/reply` 单条业务内容，真实响应嵌在 item.item 里，不是平铺在顶层。 */
@Serializable
data class ReplyAtContent(
    @SerialName("root_id") val rootId: Long = 0,
    @SerialName("target_id") val targetId: Long = 0,
    @SerialName("source_id") val sourceId: Long = 0,
    @SerialName("business_id") val businessId: Int = 0,
    @SerialName("source_content") val sourceContent: String = "",
    @SerialName("root_reply_content") val rootReplyContent: String = "",
    @SerialName("target_reply_content") val targetReplyContent: String = "",
)

/** `x/msgfeed/reply` 单条（回复与@ tab）。 */
@Serializable
data class ReplyAtItem(
    @SerialName("id") val id: Long = 0,
    @SerialName("user") val user: MsgfeedUser? = null,
    @SerialName("item") val item: ReplyAtContent? = null,
    @SerialName("reply_time") val replyTime: Long = 0,
) {
    /** 优先取楼中楼回复内容，取不到时退回一级回复内容。 */
    val content: String get() =
        item?.targetReplyContent?.takeIf { it.isNotBlank() } ?: item?.rootReplyContent.orEmpty()
    val businessId: Int get() = item?.businessId ?: 0
    val sourceId: Long get() = item?.sourceId ?: 0
    val sourceContent: String get() = item?.sourceContent.orEmpty()
    val rootId: Long get() = item?.rootId ?: 0
    /** 站内回复评论时用作 `parent`（被回复的具体那条评论）。 */
    val parentId: Long get() = item?.targetId ?: 0
}

@Serializable
data class ReplyFeedData(
    @SerialName("items") val items: List<ReplyAtItem> = emptyList(),
    @SerialName("cursor") val cursor: MsgfeedCursor? = null,
) {
    val hasMore: Boolean get() = cursor?.isEnd == false
}

/** `x/msgfeed/like` 单条业务内容，嵌在 item.item 里。 */
@Serializable
data class LikeContent(
    @SerialName("source_id") val sourceId: Long = 0,
    @SerialName("business_id") val businessId: Int = 0,
    @SerialName("title") val title: String = "",
)

/** `x/msgfeed/like` 单条（收到喜欢 tab）。`users` 是复数——可能多人点赞合并展示，本期只取第一个。 */
@Serializable
data class LikeItem(
    @SerialName("id") val id: Long = 0,
    @SerialName("users") val users: List<MsgfeedUser> = emptyList(),
    @SerialName("item") val item: LikeContent? = null,
    @SerialName("like_time") val likeTime: Long = 0,
) {
    val firstUser: MsgfeedUser? get() = users.firstOrNull()
    val title: String get() = item?.title.orEmpty()
    val sourceId: Long get() = item?.sourceId ?: 0
    val businessId: Int get() = item?.businessId ?: 0
}

@Serializable
data class LikeFeedBucket(
    @SerialName("items") val items: List<LikeItem> = emptyList(),
    @SerialName("cursor") val cursor: MsgfeedCursor? = null,
)

/** `x/msgfeed/like` 响应按 latest/total 分桶；本期只用 latest（最近点赞），total 桶不解析。 */
@Serializable
data class LikeFeedData(
    @SerialName("latest") val latest: LikeFeedBucket? = null,
) {
    val items: List<LikeItem> get() = latest?.items.orEmpty()
    val hasMore: Boolean get() = latest?.cursor?.isEnd == false
}

/** `x/msgfeed/unread` 返回聚合未读。 */
@Serializable
data class MsgfeedUnreadData(
    @SerialName("at") val at: Int = 0,
    @SerialName("reply") val reply: Int = 0,
    @SerialName("like") val like: Int = 0,
)

/** `session_svr/single_unread` 私信未读聚合。 */
@Serializable
data class SessionUnreadData(
    @SerialName("unfollow_unread") val unfollowUnread: Int = 0,
    @SerialName("follow_unread") val followUnread: Int = 0,
)

// ───────────────── 粉丝列表（x/relation/followers）─────────────────

/** `x/relation/followers` 单条。这一族关系接口是扁平结构，不受本次嵌套修正影响。 */
@Serializable
data class FollowerEntry(
    @SerialName("mid") val mid: Long = 0,
    @SerialName("uname") val uname: String = "",
    @SerialName("face") val face: String = "",
    @SerialName("sign") val sign: String = "",
    @SerialName("mtime") val mtime: Long = 0,
    @SerialName("attribute") val attribute: Int = 0,
)

@Serializable
data class FollowersData(
    @SerialName("list") val list: List<FollowerEntry> = emptyList(),
    @SerialName("total") val total: Int = 0,
)
