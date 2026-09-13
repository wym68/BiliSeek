package com.biliseek.core.network.internal

import com.biliseek.core.network.DynamicItemType
import com.biliseek.core.network.dto.DynamicBasic
import com.biliseek.core.network.dto.DynamicItem

/**
 * Ch4 §4.1.4：动态评论 oid/type 分流（参照 BiliPai `DynamicInteractionPolicy`）。
 *
 * 规则（按优先级）：
 * - 服务端权威字段 `basic.comment_id_str`/`comment_type`（当 `comment_type > 0` 时）：
 *   直接采用，覆盖视频动态（type=1,avid）/相簿（type=11）/专栏（type=12）等所有情形。
 * - 缺失时按类型猜：DRAW → oid = major.draw.id，type = 11；其他（OPUS / FORWARD / AV / 普通）
 *   → oid = id_str，type = 17（仅对纯文字/分享类动态成立，视频/专栏类会被服务端 -404）。
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
    resolveCommentTargetFromBasic(item.basic)?.let { return it }
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

/** 从服务端权威字段取评论 oid/type；缺失或 `comment_type<=0` 时返回 null 交给调用方降级。 */
private fun resolveCommentTargetFromBasic(basic: DynamicBasic?): Pair<Long, Int>? {
    if (basic == null || basic.commentType <= 0) return null
    val oid = basic.commentIdStr.toLongOrNull()?.takeIf { it > 0L } ?: return null
    return oid to basic.commentType
}

/**
 * 公开给 UI / VM 用的入口：基于 [DynamicEntry] 计算评论 oid/type。
 * 优先用 entry.commentOid/commentType（非 0 时，来自服务端 `basic` 字段）；
 * 否则 DRAW 用 entry.drawId（非 0 时）+type=11；其他走 id_str +type=17。
 */
fun resolveDynamicCommentTargetFromEntry(
    idStr: String,
    rawType: String,
    drawId: Long = 0,
    commentOid: Long = 0,
    commentType: Int = 0,
): Pair<Long, Int> {
    if (commentType > 0 && commentOid > 0L) return commentOid to commentType
    return if (rawType == DynamicItemType.DRAW && drawId > 0L) {
        drawId to 11
    } else {
        idStr.toLongOrNull()?.let { it to 17 } ?: (idStr.hashCode().toLong() to 17)
    }
}

/**
 * 回复目标的 root/parent（B 站 `x/v2/reply/add` 语义）。
 * 回复一级评论：`rootRpid` 为 null，此时 root=parent=该评论 rpid。
 * 回复楼中楼：`rootRpid` 是其所属根评论 rpid，root=rootRpid，parent=该楼中楼 rpid。
 */
fun resolveReplyRootParent(rpid: Long, rootRpid: Long?): Pair<Long, Long> =
    (rootRpid ?: rpid) to rpid