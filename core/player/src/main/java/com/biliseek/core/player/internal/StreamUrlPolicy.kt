package com.biliseek.core.player.internal

/**
 * 一条轨道的全部线路（主线路 + backup_url），按「先稳后险」排好，写进 manifest 时依次对应
 * `dvb:priority` 1、2、3……——当前线路回 403/404/5xx 时 ExoPlayer 自己换下一条，不必重新取流。
 *
 * - **明文 http 直接丢掉**（只要还有 https 可用）：本应用没放行 cleartext，这类链接根本发不出去，
 *   排在后面也可能在其他线路都被排除时被选中，直接判死。
 * - **PCDN / mcdn / IP 直连殿后**：B 站的 base_url 常落在第三方设备分担流量的节点上，
 *   切网、跨运营商时最容易连不上；backup_url 多是官方 upos 镜像。
 * - **[avoidHosts]（最近连不上的主机）排在最后**，比险路还靠后：险路至少没刚失败过。
 *   重新取流拿到的线路顺序与上次相同，不避开的话自动恢复会再撞一次同一个坏节点
 *   （实测：海外网络下主线路 Akamai 连上即断，备用的腾讯海外却从没被试过）。
 *
 * 排序稳定：同一档内保持接口给出的原始顺序。
 */
internal fun orderCdnUrls(
    primary: String,
    backups: List<String>,
    avoidHosts: Set<String> = emptySet(),
): List<String> {
    val all = (listOf(primary) + backups).filter { it.isNotBlank() }.distinct()
    val https = all.filter { it.startsWith("https://", ignoreCase = true) }
    return (https.ifEmpty { all }).sortedWith(
        compareBy<String>({ cdnHostOf(it) in avoidHosts }, { isRiskyCdnUrl(it) }),
    )
}

internal fun isRiskyCdnUrl(url: String): Boolean {
    if (!url.startsWith("https://", ignoreCase = true)) return true
    val host = cdnHostOf(url)
    return "mcdn" in host || "pcdn" in host || IPV4_HOST.matches(host)
}

/** 链接里的主机名（小写、不含端口）。 */
internal fun cdnHostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()

private val IPV4_HOST = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/** 失败记录里还在 [windowMs] 窗口内的主机。 */
internal fun activeCdnFailures(failedAt: Map<String, Long>, nowMs: Long, windowMs: Long): Set<String> =
    failedAt.filterValues { nowMs - it < windowMs }.keys

/** 直链里的过期时刻（B 站 CDN 链接的 `deadline` 参数，unix 秒）。没有这个参数返回 null。 */
internal fun playUrlDeadlineMs(url: String): Long? =
    DEADLINE_PARAM.find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)

private val DEADLINE_PARAM = Regex("""[?&]deadline=(\d+)""")

/**
 * 缓存的 playurl 还能不能直接用：要保证**从现在播到结尾**直链都不过期，而不只是「此刻还没过期」。
 *
 * 早期只看「取流满 100 分钟就作废」：缓存到第 99 分钟时拿出来播一个 40 分钟的视频，
 * 播到一半直链过期，CDN 回 403——「某些（长）视频播着播着就卡住」。
 * 过期时刻取直链里的 `deadline` 与「取流时刻 + [maxAgeMs]」中较早的一个；
 * [marginMs] 给暂停、缓冲留余量。
 */
internal fun shouldReusePlayUrl(
    fetchedAtMs: Long,
    deadlineMs: Long?,
    durationMs: Long,
    nowMs: Long,
    maxAgeMs: Long,
    marginMs: Long,
): Boolean {
    val expiresAt = minOf(deadlineMs ?: Long.MAX_VALUE, fetchedAtMs + maxAgeMs)
    return nowMs + durationMs.coerceAtLeast(0L) + marginMs < expiresAt
}
