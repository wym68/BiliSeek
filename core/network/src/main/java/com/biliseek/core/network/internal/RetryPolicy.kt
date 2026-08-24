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
