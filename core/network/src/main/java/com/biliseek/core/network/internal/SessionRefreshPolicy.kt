package com.biliseek.core.network.internal

import com.biliseek.core.network.RefreshOutcome
import retrofit2.HttpException

/*
 * 登录态续期的决策：纯函数、无副作用、可单测。
 * 单飞锁与记忆在 CookieRefresher；编排在 runWithLoginRefresh（读）与 runWriteRequest（写）。
 */

/** 同一份登录态问过 passport 后，多久之内不再问、直接复用结果。 */
internal const val REFRESH_MEMO_MS = 10 * 60_000L

/** 上一次真去问 passport：针对哪份登录态（SESSDATA）、结果、时间。 */
internal data class RefreshMemo(val sessData: String, val outcome: RefreshOutcome, val atMs: Long)

internal sealed interface RefreshDecision {
    /** 不发请求，直接用这个结果。 */
    data class Reuse(val outcome: RefreshOutcome) : RefreshDecision

    /** 真去走一遍 passport 刷新流程。 */
    data object Ask : RefreshDecision
}

/**
 * 拿到刷新锁之后：要不要真去问 passport。
 *
 * 挡住两类重复：同一页面几个请求同时撞 -101（等锁期间第一个已续上 → 直接重打）、
 * 连续下拉刷新（同一登录态 [REFRESH_MEMO_MS] 内复用上次结果）。
 *
 * @param rejectedSessData 被 -101 / -111 拒掉的那次请求所用的 SESSDATA；`null` = 不是被拒触发（启动检查）
 * @param currentSessData 当前本地登录态的 SESSDATA，空 = 未登录
 */
internal fun resolveRefreshDecision(
    rejectedSessData: String?,
    currentSessData: String,
    memo: RefreshMemo?,
    nowMs: Long,
): RefreshDecision = when {
    currentSessData.isBlank() -> RefreshDecision.Reuse(RefreshOutcome.NotLoggedIn)
    rejectedSessData != null && rejectedSessData != currentSessData ->
        RefreshDecision.Reuse(RefreshOutcome.Refreshed)
    memo != null && memo.sessData == currentSessData && nowMs - memo.atMs < REFRESH_MEMO_MS ->
        RefreshDecision.Reuse(memo.outcome)
    else -> RefreshDecision.Ask
}

/** 刷新之后，被拒的请求该怎么办。 */
internal enum class RefreshFollowUp {
    /** 已续上：把被拒的请求重打一次（仅一次） */
    RETRY_ONCE,

    /** passport 确认登录态已失效：登出，等用户重新扫码 */
    LOG_OUT,

    /** 刷新撞了风控：进全局冷却，不重打 */
    COOL_DOWN,

    /** 没续上、也不能断定失效：本次照常失败 */
    GIVE_UP,
}

internal fun resolveRefreshFollowUp(outcome: RefreshOutcome): RefreshFollowUp = when (outcome) {
    RefreshOutcome.Refreshed -> RefreshFollowUp.RETRY_ONCE
    RefreshOutcome.Expired -> RefreshFollowUp.LOG_OUT
    RefreshOutcome.Blocked -> RefreshFollowUp.COOL_DOWN
    RefreshOutcome.NotLoggedIn,
    RefreshOutcome.NotNeeded,
    RefreshOutcome.NotConfigured,
    is RefreshOutcome.Failed -> RefreshFollowUp.GIVE_UP
}

/**
 * passport（`cookie/info`、`cookie/refresh`）非 0 返回码 → 刷新结果。
 * -101 是 passport 自己说「未登录」：登录态在服务端已失效，续期无从谈起。
 */
internal fun resolvePassportFailure(step: String, code: Int, message: String): RefreshOutcome = when {
    resolveRetryAction(code) == RetryAction.BACKOFF_NO_RETRY -> RefreshOutcome.Blocked
    code == -101 -> RefreshOutcome.Expired
    else -> RefreshOutcome.Failed("$step code=$code $message".trimEnd())
}

/** passport 流程某一步 HTTP 非 2xx → 刷新结果。HTTP 412 与 -352 同为风控拦截（同 viewConclusion 的口径）。 */
internal fun resolvePassportHttpFailure(step: String, httpCode: Int): RefreshOutcome =
    if (httpCode == 412) RefreshOutcome.Blocked else RefreshOutcome.Failed("$step HTTP $httpCode")

/** 刷新流程里抛出的异常（取消除外）→ 刷新结果。折成结果而不是往外抛，才能记入记忆。 */
internal fun resolvePassportException(e: Exception): RefreshOutcome =
    if (e is HttpException) {
        resolvePassportHttpFailure("passport", e.code())
    } else {
        RefreshOutcome.Failed(e.message ?: e.javaClass.simpleName)
    }
