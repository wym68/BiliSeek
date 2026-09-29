package com.biliseek.app.ui

/**
 * 一次下拉刷新的进度。
 *
 * 只对「用户亲手下拉、而且真的转起圈来」的那次刷新给结果提示——首次进页面的加载、翻页、
 * 别处触发的刷新都不打扰。早期各页面各写一份 LaunchedEffect：成功提示的判定条件写反了、
 * 永远出不来；收藏夹每次进页面还会冒一句假的「已刷新」。
 */
internal data class PullRefreshTracker(
    /** 用户下拉过、结果还没提示。 */
    val pulled: Boolean = false,
    /** 这次下拉之后见到过刷新中。没见到就结束，说明请求被挡掉了（已在加载 / 未登录），不提示。 */
    val sawRefreshing: Boolean = false,
)

/** 刷新结束后的提示。 */
internal data class RefreshToast(val message: String, val isError: Boolean)

/** 用户下拉。下拉时已经在刷新，就把这次刷新的结果当作本次的结果。 */
internal fun PullRefreshTracker.onPull(isRefreshing: Boolean): PullRefreshTracker =
    PullRefreshTracker(pulled = true, sawRefreshing = isRefreshing)

/** 刷新状态变化。返回推进后的进度，以及（本次下拉刚好结束时）要弹的提示。 */
internal fun PullRefreshTracker.onRefreshingChanged(
    isRefreshing: Boolean,
    errorMessage: String?,
    successText: String,
): Pair<PullRefreshTracker, RefreshToast?> = when {
    !pulled -> this to null
    isRefreshing -> copy(sawRefreshing = true) to null
    !sawRefreshing -> this to null
    errorMessage != null -> PullRefreshTracker() to RefreshToast("刷新失败：$errorMessage", isError = true)
    else -> PullRefreshTracker() to RefreshToast(successText, isError = false)
}
