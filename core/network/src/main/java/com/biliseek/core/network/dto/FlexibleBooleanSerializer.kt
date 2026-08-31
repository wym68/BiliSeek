package com.biliseek.core.network.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * B 站接口同一字段有时返回 int (0/1) 有时返回 bool (false/true)。
 *
 * 典型场景：`module_stat.forward.status` 和 `module_stat.like.status`——
 * 不同端点甚至同端点不同时间会混着用。直接声明成 `Boolean` 会让 0/1 字段解析失败，
 * 直接声明成 `Int` 又会让 false/true 字段解析失败。
 *
 * 解法：JSON 原始值若是 bool 原样读出；若是数字则 0→false、非 0→true；
 * 缺字段或 null 时走 Kotlin 默认值（false）。字符串等其他类型按缺字段处理。
 *
 * 与 BiliPai 的 [com.android.purebilibili.data.model.response.FlexibleBooleanSerializer]
 * 同思路——同一问题同一解。
 */
object FlexibleBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val element = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return false
        element.booleanOrNull?.let { return it }
        // int 0/1 → false/true，非 0 一律算 true（B 站没有 -1 之类状态）
        return (element.intOrNull ?: 0) != 0
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeBoolean(value)
    }
}
