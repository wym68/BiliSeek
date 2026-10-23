package com.biliseek.core.network.internal

import com.biliseek.core.network.dto.BiliResponse

/**
 * 评论 WBI 主链路失败时是否降级到兼容链路。
 * -111 / -403（csrf、WBI 相关码）→ 降级重试一次（对齐 bilipai `shouldFallbackCommentRead(-111)`）。
 * -352（风控拦截）与 -412（缺设备指纹）**不降级**：-352 被拦后再打一次会把临时限制升级成长期封禁；
 * -412 要先重置设备凭据，立刻再打没用。两者都原样返回，交给调用方进冷却 / 重置凭据。
 */
internal fun shouldFallbackCommentRead(code: Int): Boolean =
    code == -111 || code == -403

/**
 * 评论读编排：先打主链路（WBI），失败且命中 [shouldFallbackCommentRead] 则降级兼容链路一次。
 * 纯函数（两条链路以 lambda 注入），可离线单测。
 */
internal suspend fun <T> runCommentRead(
    primary: suspend () -> BiliResponse<T>,
    compat: suspend () -> BiliResponse<T>,
): BiliResponse<T> {
    val first = primary()
    return if (first.code != 0 && shouldFallbackCommentRead(first.code)) compat() else first
}
