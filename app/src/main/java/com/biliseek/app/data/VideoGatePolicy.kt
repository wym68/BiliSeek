package com.biliseek.app.data

/** 要不要放行这次「切看」。 */
enum class VideoGate {
    /** 放行。 */
    ALLOW,

    /** 计量网络下被设置拦住。UI 应给一次性放行的机会，而不是简单地不响应。 */
    BLOCKED_BY_METERED,
}

/**
 * 流量策略：计量网络下要不要拦住视频。
 *
 * 只拦「切看」，**不拦音频**——音频是本 App 的主用途，拦它等于关掉整个 App。
 * 视频码率比音频高一个量级（M0 实测约 160 倍），这条界线画在这里才有意义。
 */
fun resolveVideoGate(settings: AppSettings, isMetered: Boolean): VideoGate = when {
    !isMetered -> VideoGate.ALLOW
    settings.allowVideoOnMetered -> VideoGate.ALLOW
    else -> VideoGate.BLOCKED_BY_METERED
}
