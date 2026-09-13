package com.biliseek.app.data

import com.biliseek.core.network.DynamicEntry

/**
 * 动态卡片正文的展开态（按 idStr 独立维护，允许多张卡片同时展开）。
 * `expanded=false` 且 `entry=null` 是初始态（未展开过，等价于 Map 里没有这个 key）。
 */
data class DynamicExpandState(
    val expanded: Boolean = false,
    val entry: DynamicEntry? = null,
    val error: String? = null,
)

/**
 * 点击"展开/收起"后的下一状态（纯函数，不含网络请求，由调用方按
 * [shouldFetchDynamicDetail] 决定是否发起请求）。
 *
 * - 未展开过 → 标记展开，待拉取。
 * - 已展开且已有正文 → 收起，正文保留在 [DynamicExpandState.entry] 里（收起不清缓存）。
 * - 收起但有缓存正文 → 直接展开，不重新拉取。
 * - 出错态（`error != null`）→ 点击视为"重试"，重新标记展开、清掉错误，待拉取。
 */
fun nextDynamicExpandState(current: DynamicExpandState?): DynamicExpandState = when {
    current == null -> DynamicExpandState(expanded = true)
    current.error != null -> DynamicExpandState(expanded = true)
    current.expanded && current.entry != null -> current.copy(expanded = false)
    current.expanded -> current
    current.entry != null -> current.copy(expanded = true)
    else -> DynamicExpandState(expanded = true)
}

/** [next] 是否需要发起网络请求拉正文：标记为展开、且没有缓存正文、且不在错误态。 */
fun shouldFetchDynamicDetail(next: DynamicExpandState): Boolean =
    next.expanded && next.entry == null && next.error == null
