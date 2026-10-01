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
