package com.biliseek.core.network

import com.biliseek.core.network.dto.LikeItem
import com.biliseek.core.network.dto.ReplyAtItem
import com.biliseek.core.network.dto.Session
import com.biliseek.core.network.dto.SessionPeer
import com.biliseek.core.network.dto.UserCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/*
 * 消息中心的纯函数：私信 content 解析 + 互动消息跳转目标解析。无副作用，全部单测覆盖。
 *
 * 私信 `content` 是按 `msg_type` 编码的 JSON 字符串（bilibili-API-collect `message/private_msg_content.md`）：
 *   1 文字 {"content"}      2 图片 {"url",...}      5 撤回（content 是被撤回消息的 msg_key）
 *   6 表情 {"url",...}      7 分享 {"title","author",...}
 *   10 通知卡片 {"title","text","jump_text","jump_uri",...}
 *   11 视频推送 {"title","cover",...}  12 专栏推送 {"title",...}  14 新版分享 {"title",...}
 * 旧实现只认 {"content"}，官方通知卡片（UP主小助手等）因此原样漏出整段 JSON。
 */

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseObject(raw: String): JsonObject? =
    runCatching { lenientJson.parseToJsonElement(raw).jsonObject }.getOrNull()

private fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

/**
 * 私信内容转可读文本。
 * @param full false = 会话列表单行预览；true = 聊天气泡全文（通知卡片带正文）。
 */
fun privateMessageText(msgType: Int, content: String, full: Boolean = false): String {
    if (msgType == 5) return "[消息已撤回]"
    val obj = parseObject(content)
    val title = obj?.str("title").orEmpty()
    return when (msgType) {
        1 -> obj?.str("content") ?: content
        2 -> "[图片]"
        6 -> "[表情]"
        7, 14 -> "[分享] $title".trimEnd()
        10 -> {
            val text = obj?.str("text").orEmpty()
            when {
                full && title.isNotBlank() && text.isNotBlank() -> "$title\n$text"
                title.isNotBlank() -> title
                text.isNotBlank() -> text
                else -> "[通知]"
            }
        }
        11 -> "[视频] $title".trimEnd()
        12 -> "[专栏] $title".trimEnd()
        else -> obj?.let { o ->
            o.str("content").ifBlank { title }.ifBlank { o.str("text") }
        }?.takeIf { it.isNotBlank() } ?: "[暂不支持的消息类型]"
    }
}

private val BVID_IN_URI = Regex("BV[0-9A-Za-z]{10}")
private val AV_IN_URI = Regex("""(?:bilibili://video/|/video/av)(\d+)""")

/**
 * 从 `uri`/`native_uri` 里抠视频 bvid。只认 `/video/BVxxx`、`bilibili://video/{aid}`、`/video/av{aid}`，
 * 评论/动态页链接一律 null，避免把 rpid 当 aid 换算出一个不相干的视频。
 */
internal fun videoBvidFromUris(vararg uris: String): String? {
    for (u in uris) {
        if (u.isBlank()) continue
        if (u.contains("/video/")) BVID_IN_URI.find(u)?.let { return it.value }
        AV_IN_URI.find(u)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }?.let { return avToBv(it) }
    }
    return null
}

/** 回复与@ 条目对应的视频 bvid；评论区不在视频下（动态/专栏等）时返回 null。 */
fun ReplyAtItem.videoBvid(): String? =
    if (businessId == 1 && subjectId > 0) avToBv(subjectId) else videoBvidFromUris(uri, nativeUri)

/** 收到喜欢 条目对应的视频 bvid；被赞的是评论时先看链接能否指回视频，否则 null。 */
fun LikeItem.videoBvid(): String? =
    if (type == "video" && itemId > 0) avToBv(itemId) else videoBvidFromUris(uri, nativeUri)

/** 收到喜欢 条目的动作文案："赞了你的评论" / "赞了你的视频" / "赞了你的动态"…… */
fun LikeItem.likeActionText(): String = when (type) {
    "video" -> "赞了你的视频"
    "reply" -> "赞了你的评论"
    "dynamic", "album" -> "赞了你的动态"
    "article" -> "赞了你的专栏"
    else -> business.takeIf { it.isNotBlank() }?.let { "赞了你的$it" } ?: "赞了你的内容"
}

/** 回复与@ 条目是否支持站内回复：目前 `postCommentReply` 只写视频评论区（type=1）。 */
fun ReplyAtItem.canReplyInApp(): Boolean = businessId == 1 && subjectId > 0 && replyParentId > 0

/** 把批量查到的用户卡片并入会话；已有 `account_info` 昵称的会话（官方号）保持原样。 */
internal fun mergeSessionPeers(
    sessions: List<Session>,
    cards: Map<Long, UserCard>,
): List<Session> = sessions.map { s ->
    val card = cards[s.talkerId]
    if (card == null || !s.accountInfo?.name.isNullOrBlank()) s
    else s.copy(accountInfo = SessionPeer(name = card.name, face = card.face, mid = card.mid))
}
