package com.biliseek.core.player.internal

import java.util.concurrent.ConcurrentHashMap

/** 连不上的主机要避开多久。 */
internal const val CDN_FAILURE_WINDOW_MS = 10 * 60_000L

/**
 * 最近连不上的 CDN 主机（进程级）。
 *
 * 播放服务在分片加载失败时记下主机，取流组装 manifest 时把它们排到最后（见 [orderCdnUrls]）。
 * 服务与 PlayerConnection 必然同进程（PlaybackService 的硬性约束），一份内存状态即可共享；
 * 进程重启即清空——换了网络、过了一阵，节点可能早就好了。
 */
internal object CdnHostHealth {

    private val failedAt = ConcurrentHashMap<String, Long>()

    fun recordFailure(host: String, nowMs: Long = System.currentTimeMillis()) {
        failedAt[host.lowercase()] = nowMs
    }

    fun recentlyFailed(nowMs: Long = System.currentTimeMillis()): Set<String> =
        activeCdnFailures(failedAt, nowMs, CDN_FAILURE_WINDOW_MS)
}
