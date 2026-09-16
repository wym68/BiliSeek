package com.biliseek.core.network.internal

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** 请求限速的分类桶。搜索联想与批量写各自单独调，互不拖累。 */
internal object ThrottleBucket {
    const val SEARCH = "search"
    const val WRITE = "write"
    const val FEED = "feed"
    /** 消息中心（私信会话/历史/互动 tab）：与 FEED 隔离，避免触发风控 */
    const val MESSAGE = "message"
}

/**
 * 按类别（bucket）串行化 + 最小间隔 + jitter，给连发降噪。
 *
 * 时间计算走纯函数 [throttleDelayMs]（已单测）；同 bucket 的串行由 [Mutex] 结构性保证。
 * 时钟以入参注入，便于测试。
 */
internal class RequestThrottle(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val lastAt = ConcurrentHashMap<String, Long>()

    suspend fun <T> gate(bucket: String, minIntervalMs: Long, jitterMs: Long, block: suspend () -> T): T =
        mutexes.getOrPut(bucket) { Mutex() }.withLock {
            val wait = throttleDelayMs(lastAt[bucket], nowMs(), minIntervalMs)
            if (wait > 0) delay(wait + if (jitterMs > 0) Random.nextLong(jitterMs) else 0)
            try {
                block()
            } finally {
                lastAt[bucket] = nowMs()
            }
        }
}
