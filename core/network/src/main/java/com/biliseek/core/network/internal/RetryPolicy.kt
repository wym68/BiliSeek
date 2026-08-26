package com.biliseek.core.network.internal

/** 对 B 站错误码的处置方式。 */
enum class RetryAction {
    /** 刷新凭据后重试一次 */
    REFRESH_AND_RETRY,

    /** 重新种植风控凭据，本次请求直接失败 */
    RESEED_CREDENTIALS,

    /** 直接失败并进入退避，**不得重试** */
    BACKOFF_NO_RETRY,

    /** 正常失败，交给调用方 */
    FAIL,
}

/**
 * 错误码分流 —— 本架构里**做错会主动伤害用户账号**的地方。
 *
 * 见《架构设计篇》§3.2：`-352` 是风控拦截信号而非凭据问题。
 * 碰到它就刷新凭据再打一次，会演变成「越被拦越猛打」，
 * 把临时限流推成长期封禁。
 */
fun resolveRetryAction(code: Int): RetryAction = when (code) {
    -101 -> RetryAction.REFRESH_AND_RETRY // 未登录 / 凭据失效
    -352 -> RetryAction.BACKOFF_NO_RETRY // 风控拦截
    -412 -> RetryAction.RESEED_CREDENTIALS // 请求被拦截，多为指纹缺失
    else -> RetryAction.FAIL
}

/** 便于在拦截器里直接判断。 */
fun shouldRetry(code: Int): Boolean = resolveRetryAction(code) == RetryAction.REFRESH_AND_RETRY

/**
 * 凭据种植的两类重试预算。
 *
 * 拆开的理由是体感而非正确性：后台预热与用户主动请求共用一份「5 次指数退避」预算时，
 * 用户点播放要排在预热之后，错误要约 85 秒才浮现。
 */
enum class SeedingProfile {
    /** 后台预热。用户不在等，慢退避、多试几次，尽量种成。 */
    BACKGROUND,

    /** 用户主动请求。**快失败**——一次不成就报错，让用户看到「重试」而不是干等。 */
    FOREGROUND,
}

/**
 * 一类预算的参数。
 *
 * [attemptTimeoutMs] 同时约束「等锁」和「发请求」：前台请求可能正卡在后台预热持有的锁上，
 * 只限制网络耗时是不够的。
 */
data class RetryBudget(
    val maxAttempts: Int,
    val initialBackoffMs: Long,
    val maxBackoffMs: Long,
    val attemptTimeoutMs: Long,
)

fun retryBudgetFor(profile: SeedingProfile): RetryBudget = when (profile) {
    SeedingProfile.BACKGROUND -> RetryBudget(
        maxAttempts = 5,
        initialBackoffMs = 1_000,
        maxBackoffMs = 30_000,
        attemptTimeoutMs = 45_000,
    )
    SeedingProfile.FOREGROUND -> RetryBudget(
        maxAttempts = 1,
        initialBackoffMs = 0,
        maxBackoffMs = 0,
        // 判据要求断网时 5 秒内报错，留出余量
        attemptTimeoutMs = 4_000,
    )
}

/**
 * 第 [attempt] 次尝试（0 基）失败后应退避多久。
 *
 * 返回 `null` 表示预算已耗尽，**不再重试**——调用方据此结束循环，
 * 而不是靠 `repeat(n)` 走完最后一轮还白等一次退避。
 */
fun nextBackoffMs(budget: RetryBudget, attempt: Int): Long? {
    if (attempt < 0 || attempt >= budget.maxAttempts - 1) return null
    var delay = budget.initialBackoffMs
    repeat(attempt) { delay = (delay * 2).coerceAtMost(budget.maxBackoffMs) }
    return delay.coerceAtMost(budget.maxBackoffMs)
}
