package com.biliseek.core.network.internal

import com.biliseek.core.network.DynamicItemType
import com.biliseek.core.network.dto.DynamicItem

/**
 * Ch4 §4.1.4：动态评论 oid/type 分流（参照 BiliPai `DynamicInteractionPolicy`）。
 *
 * 规则：
 * - DRAW：oid = major.draw.id，type = 11
 * - 其他（OPUS / FORWARD / AV / 普通）：oid = id_str，type = 17
 *
 * DRAW 的 `major.draw.id` 不一定存在，缺失时安全降级为 `(id_str, 17)`，与"普通图文"
 * 一致——服务端可能就拒了，但前端拿到一致的请求形状便于排查日志。
 *
 * 返回 `(oid, type)`。oid 是 Long（comment 接口 oid 字段一般是数字），DRAW 缺失 id 时
 * 用 id_str 的 hashCode 兜底（理论上 id_str 已是数字字符串，纯前端拿不到时不应崩）。
 */
internal fun resolveDynamicCommentTarget(
    item: DynamicItem,
): Pair<Long, Int> {
    val major = item.modules?.dynamic?.major
    return when (item.type) {
        DynamicItemType.DRAW -> {
            val drawId = major?.draw?.id ?: 0L
            if (drawId > 0L) drawId to 11
            // 降级：id_str 通常是纯数字（如 "1234567890"），hashCode 仅在前端拿不到 id 时使用
            else item.idStr.toLongOrNull()?.let { it to 17 }
                ?: item.idStr.hashCode().toLong() to 17
        }
        else -> item.idStr.toLongOrNull()?.let { it to 17 }
            ?: item.idStr.hashCode().toLong() to 17
    }
}

/**
 * 公开给 UI / VM 用的入口：基于 [DynamicEntry] 计算评论 oid/type。
 * DRAW 用 entry.drawId（非 0 时）+type=11；其他走 id_str +type=17。
 */
fun resolveDynamicCommentTargetFromEntry(
    idStr: String,
    rawType: String,
    drawId: Long = 0,
): Pair<Long, Int> {
    return if (rawType == DynamicItemType.DRAW && drawId > 0L) {
        drawId to 11
    } else {
        idStr.toLongOrNull()?.let { it to 17 } ?: (idStr.hashCode().toLong() to 17)
    }
}