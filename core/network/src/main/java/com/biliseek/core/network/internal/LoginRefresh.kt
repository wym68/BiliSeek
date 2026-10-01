package com.biliseek.core.network.internal

import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.RefreshOutcome
import com.biliseek.core.network.dto.BiliResponse

/**
 * 读请求的 -101 编排：登录态可能刚过期 → 续期一次，续上了把这个请求重打一次（**仅一次，绝不循环**）。
 *
 * 只认 -101（[RetryAction.REFRESH_AND_RETRY]）；-352 / -412 等原样交回调用点现有分流——
 * -352 永远不会触发刷新。重打的结果原样返回，无论再回什么都不再重打。
 *
 * 协作者全部以函数入参注入，无 Android 依赖、可离线单测；
 * [com.biliseek.core.network.BiliRepository] 的 `refreshingOn101` 是它的薄封装。
 */
internal suspend fun <T> runWithLoginRefresh(
    currentSession: () -> BiliSession,
    refresh: suspend (rejected: BiliSession) -> RefreshOutcome,
    logOut: suspend () -> Unit,
    coolDown: () -> Unit,
    fire: suspend () -> BiliResponse<T>,
): BiliResponse<T> {
    // 先记下这次请求用的登录态：等刷新锁期间别人已续上的话，据此直接重打，不必再问 passport
    val used = currentSession()
    val first = fire()
    if (resolveRetryAction(first.code) != RetryAction.REFRESH_AND_RETRY) return first
    return when (resolveRefreshFollowUp(refresh(used))) {
        RefreshFollowUp.RETRY_ONCE -> fire()
        RefreshFollowUp.LOG_OUT -> first.also { logOut() }
        RefreshFollowUp.COOL_DOWN -> first.also { coolDown() }
        RefreshFollowUp.GIVE_UP -> first
    }
}
