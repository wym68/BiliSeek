package com.biliseek.core.network.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * B 站 `x/relation` 接口的 `relation` 字段返回 int（1=已关注，0=未关注），
 * 而 [RelationStatus] 是 Kotlin 枚举，默认序列化器无法反序列化整数。
 *
 * 处理规则：1 → FOLLOWING，0 → NOT_FOLLOWING，其它 → UNKNOWN。
 */
object RelationStatusSerializer : KSerializer<RelationStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RelationStatus", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): RelationStatus {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return RelationStatus.UNKNOWN
        val element = jsonDecoder.decodeJsonElement() as? JsonPrimitive
            ?: return RelationStatus.UNKNOWN
        // Handle both int and string representations
        val intValue = element.intOrNull
            ?: element.contentOrNull?.toIntOrNull()
        return when (intValue) {
            1 -> RelationStatus.FOLLOWING
            0 -> RelationStatus.NOT_FOLLOWING
            else -> RelationStatus.UNKNOWN
        }
    }

    override fun serialize(encoder: Encoder, value: RelationStatus) {
        encoder.encodeInt(
            when (value) {
                RelationStatus.FOLLOWING -> 1
                RelationStatus.NOT_FOLLOWING -> 0
                RelationStatus.UNKNOWN -> -1
            },
        )
    }
}
