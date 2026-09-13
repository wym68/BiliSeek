package com.biliseek.core.network.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * `major.opus.summary` 双形态兼容。
 *
 * B 站文档形态是对象 `{text, rich_text_nodes}`（bilibili-API-collect /
 * bilipai / Bilibili-Evolved 三处印证）；早期/降级响应是裸字符串。
 * 当前项目历史版本曾按裸字符串建模，遇到文档形态会**整页解码失败**——
 * 这里两种都吃，异常形态回退空摘要而不是抛异常。
 */
object OpusSummaryCompatSerializer : KSerializer<DynamicOpusSummary> {
    override val descriptor: SerialDescriptor = DynamicOpusSummary.serializer().descriptor

    override fun deserialize(decoder: Decoder): DynamicOpusSummary {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return DynamicOpusSummary.serializer().deserialize(decoder)
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonObject ->
                jsonDecoder.json.decodeFromJsonElement(DynamicOpusSummary.serializer(), element)
            is JsonPrimitive -> DynamicOpusSummary(text = element.contentOrNull.orEmpty())
            else -> DynamicOpusSummary()
        }
    }

    override fun serialize(encoder: Encoder, value: DynamicOpusSummary) {
        DynamicOpusSummary.serializer().serialize(encoder, value)
    }
}

/**
 * `major.opus.pics` 双形态兼容。
 *
 * 文档形态元素是对象 `{"url": ..., "width": ..., "height": ..., "size": ...}`；
 * 旧版/降级响应是纯 URL 字符串数组。统一收成 URL 字符串列表（下游
 * normalize/pics 逻辑不变），空白与异常元素静默丢弃。
 */
object OpusPicsCompatSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor = ListSerializer(String.serializer()).descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return ListSerializer(String.serializer()).deserialize(decoder)
        val array = jsonDecoder.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { node ->
            when (node) {
                is JsonPrimitive -> node.contentOrNull?.takeIf(String::isNotBlank)
                is JsonObject -> node["url"]
                    ?.let { it as? JsonPrimitive }
                    ?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                else -> null
            }
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        ListSerializer(String.serializer()).serialize(encoder, value)
    }
}
