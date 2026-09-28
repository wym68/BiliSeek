package com.biliseek.core.network.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

// ── 表情（评论 / 动态 / 私信共用）──────────────────────────
//
// 三个来源字段名各不相同，统一收成 [Emote]：
// - 评论 `content.emote`：`{"[doge]": {id, package_id, text, url, meta: {size}}}`
// - 动态 `rich_text_nodes[].emoji`：`{text, icon_url, size}`（解析层转换，见 DynamicFeed）
// - 私信 `e_infos[]`：`{text, url, size}`
// - 表情面板 `x/emote/user/panel/web` 的 `packages[].emote[]`：同评论

/** 一个表情。[text] 是正文里的占位串（`[doge]`），[url] 是图片地址。 */
@Serializable
data class Emote(
    val id: Long = 0,
    @SerialName("package_id") val packageId: Long = 0,
    val text: String = "",
    val url: String = "",
    /** 1=普通 2=会员/大表情 3=颜文字（纯文本，无图） 4=… 以抓包为准，只用于面板分组展示。 */
    val type: Int = 0,
    val meta: EmoteMeta = EmoteMeta(),
) {
    /** meta.size=2 的是大表情（官方在评论里按 48dp 左右单独显示）。 */
    val isLarge: Boolean get() = meta.size >= 2
}

@Serializable
data class EmoteMeta(
    /** 1=行内小表情 2=大表情。 */
    val size: Int = 1,
    /** 别名（面板长按提示）。 */
    val alias: String = "",
)

/** `x/emote/user/panel/web?business=reply` —— 当前账号可用的表情包。 */
@Serializable
data class EmotePanelData(
    val packages: List<EmotePackage>? = null,
)

@Serializable
data class EmotePackage(
    val id: Long = 0,
    /** 表情包名（「小黄脸」「颜文字」…）。 */
    val text: String = "",
    /** 表情包图标（面板 tab 用）。 */
    val url: String = "",
    /** 4=颜文字（纯文本）。 */
    val type: Int = 0,
    val emote: List<Emote>? = null,
)

/**
 * 评论 `content.emote` 的容错解码：整体不是对象、或某一项结构异常时**丢掉该项**，
 * 不让一个怪表情把整页评论解码失败（评论 DTO 是列表页的根）。
 */
object TolerantEmoteMapSerializer : KSerializer<Map<String, Emote>> {
    private val delegate = MapSerializer(String.serializer(), Emote.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Map<String, Emote> {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val obj = jsonDecoder.decodeJsonElement() as? JsonObject ?: return emptyMap()
        return buildMap {
            obj.forEach { (key, value) ->
                val emote = runCatching { jsonDecoder.json.decodeFromJsonElement<Emote>(value) }.getOrNull()
                if (emote != null && emote.url.isNotBlank()) {
                    put(key, if (emote.text.isBlank()) emote.copy(text = key) else emote)
                }
            }
        }
    }

    override fun serialize(encoder: Encoder, value: Map<String, Emote>) = delegate.serialize(encoder, value)
}

/**
 * 评论 `content.jump_url` 只取 key（被高亮的关键词 / 链接文本），值结构复杂且多变，一律不解析。
 */
object JumpKeywordsSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val obj = jsonDecoder.decodeJsonElement() as? JsonObject ?: return emptyList()
        return obj.keys.filter { it.isNotBlank() }
    }

    override fun serialize(encoder: Encoder, value: List<String>) = delegate.serialize(encoder, value)
}
