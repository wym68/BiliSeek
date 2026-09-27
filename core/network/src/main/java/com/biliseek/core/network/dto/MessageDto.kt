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

/**
 * 对方用户卡片（昵称/头像）。注意：`get_sessions` 只给官方号/系统号带这个字段，
 * 普通用户会话里是缺失的——由 `BiliRepository.privateSessions()` 另查 `user/cards` 补齐。
 */
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

/**
 * `x/msgfeed/reply` 单条业务内容，真实响应嵌在 item.item 里，不是平铺在顶层。
 *
 * 字段语义（对照 bilibili-API-collect `message/msg.md`，容易望文生义搞反）：
 * - `subject_id`：评论区所属对象 id（business_id=1 时是视频 aid）——跳转/回复用它当 `oid`
 * - `source_id`：**对方发来的这条回复**的 rpid——站内回复时当 `parent`
 * - `target_id`：对方回复的那条评论（通常是我的评论）的 rpid；直接评论视频时为 0
 * - `root_id`：楼层根评论 rpid；对方发的就是根评论时为 0
 * - `source_content`：**对方回复的正文**（不是视频标题）
 * - `target_reply_content` / `root_reply_content`：被回复的我的评论 / 根评论正文，可能为空
 * - `title`：评论区所属对象的标题（视频标题）
 */
@Serializable
data class ReplyAtContent(
    @SerialName("subject_id") val subjectId: Long = 0,
    @SerialName("root_id") val rootId: Long = 0,
    @SerialName("target_id") val targetId: Long = 0,
    @SerialName("source_id") val sourceId: Long = 0,
    @SerialName("type") val type: String = "",
    @SerialName("business_id") val businessId: Int = 0,
    @SerialName("business") val business: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("uri") val uri: String = "",
    @SerialName("native_uri") val nativeUri: String = "",
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
    /** 对方回复的正文。 */
    val content: String get() = item?.sourceContent.orEmpty()
    /** 被回复的我的那条评论（楼中楼优先，退回根评论）；对方直接评论视频时为空。 */
    val quotedContent: String get() =
        item?.targetReplyContent?.takeIf { it.isNotBlank() } ?: item?.rootReplyContent.orEmpty()
    /** 评论区所属对象标题（视频标题）。 */
    val title: String get() = item?.title.orEmpty()
    val businessId: Int get() = item?.businessId ?: 0
    /** 评论区所属对象 id；business_id=1 时是视频 aid。 */
    val subjectId: Long get() = item?.subjectId ?: 0
    val uri: String get() = item?.uri.orEmpty()
    val nativeUri: String get() = item?.nativeUri.orEmpty()
    /** 站内回复对方时的 `parent`：就是对方这条回复本身。 */
    val replyParentId: Long get() = item?.sourceId ?: 0
    /** 站内回复对方时的 `root`：对方回复挂在某楼下就用该楼，否则它自己就是根。 */
    val replyRootId: Long get() = item?.rootId?.takeIf { it > 0 } ?: replyParentId
}

@Serializable
data class ReplyFeedData(
    @SerialName("items") val items: List<ReplyAtItem> = emptyList(),
    @SerialName("cursor") val cursor: MsgfeedCursor? = null,
) {
    val hasMore: Boolean get() = cursor?.isEnd == false
}

/**
 * `x/msgfeed/like` 单条业务内容，嵌在 item.item 里。
 * 对象 id 字段是 `item_id`（没有 `source_id`）；`type` 区分被赞的是视频还是评论等，
 * 被赞的是评论时 `item_id` 是评论 rpid 而不是 aid，不能直接当视频播放。
 */
@Serializable
data class LikeContent(
    @SerialName("item_id") val itemId: Long = 0,
    @SerialName("type") val type: String = "",
    @SerialName("business") val business: String = "",
    @SerialName("business_id") val businessId: Int = 0,
    @SerialName("title") val title: String = "",
    @SerialName("uri") val uri: String = "",
    @SerialName("native_uri") val nativeUri: String = "",
)

/** `x/msgfeed/like` 单条（收到喜欢 tab）。`users` 是复数——多人点赞合并成一条，`counts` 是总人数。 */
@Serializable
data class LikeItem(
    @SerialName("id") val id: Long = 0,
    @SerialName("users") val users: List<MsgfeedUser> = emptyList(),
    @SerialName("item") val item: LikeContent? = null,
    @SerialName("counts") val counts: Int = 0,
    @SerialName("like_time") val likeTime: Long = 0,
) {
    val firstUser: MsgfeedUser? get() = users.firstOrNull()
    val title: String get() = item?.title.orEmpty()
    val itemId: Long get() = item?.itemId ?: 0
    val type: String get() = item?.type.orEmpty()
    val business: String get() = item?.business.orEmpty()
    val uri: String get() = item?.uri.orEmpty()
    val nativeUri: String get() = item?.nativeUri.orEmpty()
}

@Serializable
data class LikeFeedBucket(
    @SerialName("items") val items: List<LikeItem> = emptyList(),
    @SerialName("cursor") val cursor: MsgfeedCursor? = null,
)

/**
 * `x/msgfeed/like` 响应分两桶：
 * - `latest`：上次查看之后的新点赞（无游标）。在官方 App 或本应用看过一次后通常就空了。
 * - `total`：全部历史点赞，带分页游标。
 * 只读 `latest` 会导致「看过一次后整页暂无内容」，所以两桶合并展示（新的在前，按 id 去重），
 * 分页状态取 `total` 的游标。
 */
@Serializable
data class LikeFeedData(
    @SerialName("latest") val latest: LikeFeedBucket? = null,
    @SerialName("total") val total: LikeFeedBucket? = null,
) {
    val items: List<LikeItem> get() =
        (latest?.items.orEmpty() + total?.items.orEmpty()).distinctBy { it.id }
    val hasMore: Boolean get() = total?.cursor?.isEnd == false
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

// ───────────────── 用户卡片批量查询（api.vc.bilibili.com account/v1/user/cards）─────────────────

/** `account/v1/user/cards` 单条。只取会话列表需要的昵称与头像。 */
@Serializable
data class UserCard(
    @SerialName("mid") val mid: Long = 0,
    @SerialName("name") val name: String = "",
    @SerialName("face") val face: String = "",
)
