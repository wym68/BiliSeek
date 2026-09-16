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

/** `x/msgfeed/reply` 单条（回复与@ tab）。 */
@Serializable
data class ReplyAtItem(
    @SerialName("id") val id: Long = 0,
    @SerialName("user") val user: MsgfeedUser? = null,
    /** 业务类型：1=视频回复，5=动态回复，7=评论回复等。 */
    @SerialName("business_id") val businessId: Int = 0,
    /** 资源 oid（视频=aid，动态=id_str）。 */
    @SerialName("source_id") val sourceId: Long = 0,
    @SerialName("source_content") val sourceContent: String = "",
    @SerialName("root_id") val rootId: Long = 0,
    @SerialName("parent_id") val parentId: Long = 0,
    @SerialName("reply_time") val replyTime: Long = 0,
    @SerialName("content") val content: String = "",
)

/** `x/msgfeed/like` 单条（收到喜欢 tab）。 */
@Serializable
data class LikeItem(
    @SerialName("id") val id: Long = 0,
    @SerialName("user") val user: MsgfeedUser? = null,
    @SerialName("source_id") val sourceId: Long = 0,
    @SerialName("business_id") val businessId: Int = 0,
    @SerialName("title") val title: String = "",
    @SerialName("like_time") val likeTime: Long = 0,
)

@Serializable
data class MsgfeedUser(
    @SerialName("mid") val mid: Long = 0,
    @SerialName("name") val name: String = "",
    @SerialName("face") val face: String = "",
)

@Serializable
data class ReplyFeedData(
    @SerialName("items") val items: List<ReplyAtItem> = emptyList(),
    @SerialName("has_more") val hasMore: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
)

@Serializable
data class LikeFeedData(
    @SerialName("items") val items: List<LikeItem> = emptyList(),
    @SerialName("has_more") val hasMore: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
)

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

/** `x/relation/followers` 单条。 */
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
