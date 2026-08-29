package com.biliseek.app.data

import com.biliseek.core.player.OfflineMedia

/**
 * 下载相关的纯函数策略。与 `ReorderPolicy` / `VideoGatePolicy` 同一惯例——
 * 落盘路径、断点续传、离线命中判定都是「错了不会崩、只会静默不对」的逻辑，
 * 必须能被单测钉住。
 */

/**
 * 下载文件名：`{bvid}_{cid}` + 扩展名。
 *
 * 只音频是 `.m4a`（DASH 音频轨即完整 M4A，**改个扩展名就能播，不转码**）；
 * 视频是 `.mp4`（音频轨 + 视频轨 mux 的产物）。
 */
fun downloadFileName(bvid: String, cid: Long, content: String): String {
    val ext = if (content == DownloadContent.VIDEO) "mp4" else "m4a"
    return "${bvid}_${cid}.$ext"
}

/** 私有目录下的相对路径：`downloads/{文件名}`。 */
fun privateDownloadRelPath(bvid: String, cid: Long, content: String): String =
    "downloads/${downloadFileName(bvid, cid, content)}"

/**
 * 断点续传的 `Range` 请求头。
 *
 * 已下 [existingBytes] 字节时续传 `bytes={n}-`；从零开始不需要 Range（
 * 某些 CDN 对 Range 的兼容性不如整取）。`existingBytes` 必须与文件实际大小一致——
 * 不一致的续传会拼出一份损坏的文件，调用方负责保证。
 */
fun resolveRangeHeader(existingBytes: Long): String? =
    if (existingBytes > 0) "bytes=$existingBytes-" else null

/** 离线命中检查的结果。 */
sealed interface OfflineLookupResult {
    /** 本地文件可播。 */
    data class Hit(val media: OfflineMedia) : OfflineLookupResult

    /** 没有这条下载，或记录未完成。回落到在线取流。 */
    data object Miss : OfflineLookupResult

    /**
     * 记录在，但文件被用户在系统里删了。
     *
     * 调用方**必须删掉这条记录**（表行标为失效）后按 [Miss] 处理——
     * 不能只报「播放失败」，也不能每次都撞一遍这个检查。
     */
    data object Stale : OfflineLookupResult
}

/**
 * 从下载记录推导可播放的本地文件。
 *
 * 视频下载优先播视频文件（音视频轨都在，「切看」照常工作）；
 * 只下了音频的 m4a 只有音轨——看模式无画面由 `PlaybackMode` 结构兜底，不特判。
 *
 * [fileExists] 由调用方注入（file:// 走 `File.exists`，content:// 走 ContentResolver），
 * 策略本身保持纯函数。
 */
fun resolveOfflineHit(
    entity: DownloadEntity?,
    fileExists: (String) -> Boolean,
): OfflineLookupResult {
    val row = entity ?: return OfflineLookupResult.Miss
    if (row.status != DownloadStatus.DONE) return OfflineLookupResult.Miss
    if (row.audioFile.isBlank() && row.videoFile.isBlank()) return OfflineLookupResult.Miss

    val isVideo = row.videoFile.isNotBlank()
    val uri = if (isVideo) row.videoFile else row.audioFile
    if (!fileExists(uri)) return OfflineLookupResult.Stale

    return OfflineLookupResult.Hit(
        OfflineMedia(
            uri = uri,
            mimeType = if (isVideo) "video/mp4" else "audio/mp4",
            durationMs = (row.durationSec * 1000).coerceAtLeast(0L),
            hasVideoTrack = isVideo,
        ),
    )
}
