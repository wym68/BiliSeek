package com.biliseek.core.ai.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * OpenAI 兼容 `GET /models` 的响应 `{"data":[{"id":"..."}]}` → 去重、按名排序的模型名。
 *
 * 没有 `data` 数组返回 null（不是模型列表）；条目缺 `id` 的跳过。
 */
internal fun parseModelIds(json: JsonObject): List<String>? {
    val data = json["data"] as? JsonArray ?: return null
    return data.mapNotNull { item ->
        ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
}
