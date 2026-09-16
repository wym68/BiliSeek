package com.biliseek.core.network.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 播放量容错解析。B 站接口怪癖：UP 主隐藏播放量时 `play` 返回字符串 `"--"`，
 * 直接按 Long 反序列化会让整条请求失败——非数字一律按 0 处理。
 */
object TolerantLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TolerantLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long =
        (decoder.decodeSerializableValue(JsonElement.serializer()) as? JsonPrimitive)
            ?.content?.toLongOrNull() ?: 0L

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

// ── UP 主搜索与空间投稿（M11-D）──────────────────────────

/** `x/web-interface/wbi/search/type`（search_type=bili_user）的响应。 */
@Serializable
data class UserSearchData(
    val page: Int = 0,
    @SerialName("numResults") val numResults: Int = 0,
    @SerialName("numPages") val numPages: Int = 0,
    val result: List<SearchUser> = emptyList(),
)

@Serializable
data class SearchUser(
    val mid: Long = 0,
    val uname: String = "",
    /** 个性签名。 */
    val usign: String = "",
    /** 头像，`//i0.hdslb.com/...` 需补协议。 */
    val upic: String = "",
    val fans: Long = 0,
    val videos: Int = 0,
)

/** `x/web-interface/card` —— 用户卡片信息（头像、粉丝数等）。 */
@Serializable
data class UserCardData(
    val card: UserCardInfo? = null,
)

@Serializable
data class UserCardInfo(
    val mid: Long = 0,
    val name: String = "",
    /** 头像，`//i0.hdslb.com/...` 需补协议。 */
    val face: String = "",
    /** 签名。 */
    val sign: String = "",
    /** 粉丝数。 */
    val fans: Long = 0,
)

/** `x/space/wbi/arc/search` —— UP 主空间投稿（分页）。 */
@Serializable
data class SpaceArchiveData(
    val list: SpaceList? = null,
    val page: SpacePage? = null,
)

@Serializable
data class SpaceList(
    val vlist: List<SpaceVideo> = emptyList(),
)

@Serializable
data class SpacePage(
    val count: Int = 0,
    val pn: Int = 0,
    val ps: Int = 0,
)

@Serializable
data class SpaceVideo(
    val bvid: String = "",
    /** 可能含 `<em>` 高亮标签。 */
    val title: String = "",
    /** 封面，`//i0.hdslb.com/...` 需补协议。 */
    val pic: String = "",
    /**
     * 时长是展示文本（"3:51"），**不是秒**——arc/search 的 vlist 用 `length` 字段。
     * 需要秒数时用 [com.biliseek.core.network.parseDurationText] 转换。
     */
    @SerialName("length") val length: String = "",
    val author: String = "",
    val created: Long = 0,
    /** 播放量。UP 主隐藏时为字符串 "--"，容错解析为 0。 */
    @Serializable(with = TolerantLongSerializer::class)
    val play: Long = 0,
)
