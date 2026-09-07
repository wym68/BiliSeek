package com.biliseek.core.network.internal

/**
 * 连续第 [consecutiveHits] 次(1 基)风控后应冷却多久。指数退避 + 上限。
 *
 * 把 [nextBackoffMs] 的思路从「凭据种植」提升到「全局请求闸门」：撞到 -352 就主动闭嘴，
 * 撞得越多闭得越久，绝不硬顶。
 */
internal fun riskCooldownMs(consecutiveHits: Int, baseMs: Long = 60_000, maxMs: Long = 30 * 60_000): Long {
    if (consecutiveHits <= 0) return 0
    var d = baseMs
    repeat(consecutiveHits - 1) { d = (d * 2).coerceAtMost(maxMs) }
    return d.coerceAtMost(maxMs)
}

/**
 * 限速闸门要等待多久才轮到本次请求。纯函数——把 [RequestThrottle] 的时间计算抽出来单测，
 * 不必依赖真实时钟或 coroutines-test。
 *
 * @return 需要等待的毫秒（≥0）。`lastAtMs` 为 null（该 bucket 首次请求）时不等待。
 */
internal fun throttleDelayMs(lastAtMs: Long?, nowMs: Long, minIntervalMs: Long): Long {
    if (lastAtMs == null) return 0
    return (lastAtMs + minIntervalMs - nowMs).coerceAtLeast(0)
}
