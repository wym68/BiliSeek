package com.biliseek.core.network.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject

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

/** `x/dynamic/feed/draw/upload_bfs` 的返回：上传到 B 站图床的一张图。发带图评论时原样带进 `pictures`。 */
@Serializable
data class UploadedImage(
    @SerialName("image_url") val url: String = "",
    @SerialName("image_width") val width: Int = 0,
    @SerialName("image_height") val height: Int = 0,
    /** 单位 KB。 */
    @SerialName("img_size") val sizeKb: Double = 0.0,
)

// ── 评论读（B5）───────────────────────────────────────────
//
// 字段集：列表展示所需（rpid / 文本 / 作者 / 点赞 / 时间）+ 富文本（表情 / @ / 蓝字关键词）。
// 置顶（top / top_replies / upper.top）、图片（content.pictures）已建模。

/** `x/v2/reply/(wbi/)main` —— 评论列表。 */
@Serializable
data class ReplyData(
    /** 旧版 `x/v2/reply` 与楼中楼 `x/v2/reply/reply` 用页码分页。 */
    val page: ReplyPage = ReplyPage(),
    /** `wbi/main` / `main` 用游标分页：`pagination_reply.next_offset` 是下一页的 offset。 */
    val cursor: ReplyCursor = ReplyCursor(),
    val replies: List<ReplyItem>? = null,
    /** 置顶评论（新版字段，只在第一页出现）。 */
    @SerialName("top_replies") val topReplies: List<ReplyItem>? = null,
    /** 置顶：UP 主 / 管理员 / 投票。 */
    val top: ReplyTop? = null,
    /** 作者：`mid` 用来标「UP」，`top` 是 UP 主置顶的那条（旧字段）。 */
    val upper: ReplyUpper? = null,
)

@Serializable
data class ReplyCursor(
    @SerialName("is_end") val isEnd: Boolean = false,
    @SerialName("all_count") val allCount: Int = 0,
    @SerialName("pagination_reply") val paginationReply: ReplyPaginationReply? = null,
)

@Serializable
data class ReplyPaginationReply(
    @SerialName("next_offset") val nextOffset: String = "",
)

@Serializable
data class ReplyTop(
    val upper: ReplyItem? = null,
    val admin: ReplyItem? = null,
    val vote: ReplyItem? = null,
)

@Serializable
data class ReplyUpper(
    val mid: Long = 0,
    val top: ReplyItem? = null,
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
    /** 楼中楼总数。 */
    val rcount: Int = 0,
    /** 当前用户是否已点赞该评论（1=已赞，0=未赞）。 */
    val action: Int = 0,
    val content: ReplyContent = ReplyContent(),
    val member: ReplyMember = ReplyMember(),
    /** 服务端随主评论内嵌返回的前几条楼中楼（预览用，翻页走 [BiliApi.replyReply]）。 */
    val replies: List<ReplyItem>? = null,
)

@Serializable
data class ReplyContent(
    val message: String = "",
    /** `[doge]` → 表情图。容错解码：结构异常的项直接丢弃。 */
    @Serializable(with = TolerantEmoteMapSerializer::class)
    val emote: Map<String, Emote> = emptyMap(),
    /** 正文里被 @ 的用户（`@昵称` 高亮 + 点击进空间）。容错解码：坏项丢弃。 */
    @Serializable(with = TolerantAtMembersSerializer::class)
    val members: List<ReplyAtMember> = emptyList(),
    /** 被服务端加了蓝字链接的关键词（只取 key，点击走搜索）。 */
    @SerialName("jump_url")
    @Serializable(with = JumpKeywordsSerializer::class)
    val jumpKeywords: List<String> = emptyList(),
    /** 带图评论的图片。 */
    val pictures: List<ReplyPicture>? = null,
)

/** `content.members[]`。mid 在评论接口里是字符串。 */
@Serializable
data class ReplyAtMember(
    val mid: String = "",
    val uname: String = "",
)

/** `content.members` 的容错解码：不是数组 → 空；数组里的坏项（null / 非对象）逐项丢弃。 */
object TolerantAtMembersSerializer : KSerializer<List<ReplyAtMember>> {
    private val delegate = ListSerializer(ReplyAtMember.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<ReplyAtMember> {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val array = jsonDecoder.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { node ->
            if (node !is JsonObject) return@mapNotNull null
            runCatching { jsonDecoder.json.decodeFromJsonElement(ReplyAtMember.serializer(), node) }.getOrNull()
        }
    }

    override fun serialize(encoder: Encoder, value: List<ReplyAtMember>) = delegate.serialize(encoder, value)
}

@Serializable
data class ReplyMember(
    val mid: String = "",
    val uname: String = "",
    val avatar: String = "",
)

/** `content.pictures[]`：只取地址，缩略图和预览都用它。 */
@Serializable
data class ReplyPicture(
    @SerialName("img_src") val imgSrc: String = "",
)
