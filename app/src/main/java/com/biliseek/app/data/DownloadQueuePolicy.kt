package com.biliseek.app.data

/*
 * 下载队列的纯函数策略：入队去重、取流节流、并发数。与 DownloadPolicy（落盘 / 离线命中）分开，
 * 无 Android / Room 依赖，配单测。
 */

/**
 * 加入下载时，一条已有记录是否是「这次请求要顶替掉的旧失败记录」。
 *
 * 只清同一视频**同一 P** 的失败记录，以及分 P 未知（cid = 0，取流失败时落的占位）的失败记录——
 * 后者会占着 (bvid, 0) 的唯一索引。其他 P 的失败记录必须保留：此前按 bvid 一刀切，
 * 下 P3 会把 P2 的失败记录一起删掉，用户再也看不到、也没法重试。
 */
fun isReplaceableFailedRecord(
    oldBvid: String,
    oldCid: Long,
    oldStatus: String,
    requestBvid: String,
    requestCid: Long?,
): Boolean {
    if (oldStatus != DownloadStatus.FAILED || oldBvid != requestBvid) return false
    if (oldCid <= 0L) return true
    return requestCid != null && requestCid > 0L && oldCid == requestCid
}

/**
 * 同一 (bvid, cid) 已有记录时，这次入队要不要跳过：已在排队或正在下载就跳过——
 * 再写一行会把进行中的任务顶掉（唯一索引 REPLACE 换了 id），下完写回时对不上号。
 * 已完成 / 失败 / 没有记录 → 照常入队（已完成的视为重新下载）。
 */
fun shouldSkipEnqueue(existingStatus: String?): Boolean =
    existingStatus == DownloadStatus.QUEUED || existingStatus == DownloadStatus.DOWNLOADING

/** 下载取流（playurl）的最小间隔。批量下载时取流按这个节奏一条条来，不连发。 */
const val DOWNLOAD_RESOLVE_MIN_INTERVAL_MS = 2_000L

/**
 * 下一次下载取流前还要等多久（ms）。
 *
 * 取流放在「轮到下载时」才做（不在入队时做）：入队 20 个分 P 不会瞬间打出 20 次 playurl，
 * 直链也不会在排队期间过期。并发下载时多个任务的取流也经由这里排队。
 *
 * @param lastAtMs 上一次取流的时刻；null = 还没取过
 */
fun downloadResolveDelayMs(lastAtMs: Long?, nowMs: Long, minIntervalMs: Long = DOWNLOAD_RESOLVE_MIN_INTERVAL_MS): Long {
    if (lastAtMs == null) return 0L
    val elapsed = nowMs - lastAtMs
    return if (elapsed in 0 until minIntervalMs) minIntervalMs - elapsed else 0L
}

/** 同时下载数的上限。下载走 B 站视频 CDN，不是业务接口；再多收益不大，还会抢占播放带宽。 */
const val MAX_DOWNLOAD_CONCURRENCY = 3

/** 设置页可选的同时下载数。 */
val DOWNLOAD_CONCURRENCY_CHOICES: List<Int> = (1..MAX_DOWNLOAD_CONCURRENCY).toList()

/** 存储里读出的同时下载数（可能是旧值 / 被改过）收进 1..[MAX_DOWNLOAD_CONCURRENCY]。 */
fun clampDownloadConcurrency(value: Int): Int = value.coerceIn(1, MAX_DOWNLOAD_CONCURRENCY)
