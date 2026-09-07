package com.biliseek.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `x/v2/reply/add` —— 发评论后返回的 rpid。
 *
 * 与工单原稿的 `internal` 不同，这里是 `public`：[com.biliseek.core.network.BiliApi]
 * 是公开接口，其方法返回 `BiliResponse<AddReplyData>`，公开 API 无法暴露 internal 类型
 * （编译期报错）。与所有同级 DTO 保持一致，均为 public。
 */
@Serializable
data class AddReplyData(
    val rpid: Long = 0,
    @SerialName("rpid_str") val rpidStr: String = "",
)

// ── 评论读（B5）───────────────────────────────────────────
//
// ⚠️ 最小字段集：仅覆盖列表展示所需（rpid / 文本 / 作者 / 点赞 / 时间）。真实响应结构远更丰富
// （楼中楼、表情、@、置顶等），以当日抓包为准逐步补齐——标「未验证」。

/** `x/v2/reply/(wbi/)main` —— 评论列表。 */
@Serializable
data class ReplyData(
    val page: ReplyPage = ReplyPage(),
    val replies: List<ReplyItem>? = null,
)

@Serializable
data class ReplyPage(
    val count: Int = 0,
    val num: Int = 0,
    val size: Int = 0,
)

@Serializable
data class ReplyItem(
    val rpid: Long = 0,
    val oid: Long = 0,
    val mid: Long = 0,
    val ctime: Long = 0,
    val like: Int = 0,
    val content: ReplyContent = ReplyContent(),
    val member: ReplyMember = ReplyMember(),
)

@Serializable
data class ReplyContent(val message: String = "")

@Serializable
data class ReplyMember(
    val mid: String = "",
    val uname: String = "",
    val avatar: String = "",
)
