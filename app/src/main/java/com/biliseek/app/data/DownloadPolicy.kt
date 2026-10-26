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

// ── 下载中途断开：从断点接着下（设计稿：2026-10-04-download-improvements）──────────
// 视频文件大，常在下到一半时被 B 站视频服务器断开（unexpected end of stream）。用同一个地址从已写的位置接着下，
// 和播放器边播边下的做法一样；不重新请求取流接口（「不对 B 站请求加重试」针对的是接口）。

/** 连续几次没有新数据就算失败。 */
const val MAX_STALLED_RESUMES = 3

/** 连续没有新数据的次数：这一轮写进了新数据就清零，否则加一。 */
fun nextStalledCount(current: Int, madeProgress: Boolean): Int = if (madeProgress) 0 else current + 1

/** 断开后能不能接着下：HTTP 状态错误（403 / 404 …）不是传输中断，不续；连续 [MAX_STALLED_RESUMES] 次没进展也不续。 */
fun shouldResumeDownload(isHttpStatusError: Boolean, stalledCount: Int): Boolean =
    !isHttpStatusError && stalledCount < MAX_STALLED_RESUMES

/** `Content-Range: bytes 100-999/1000` 里的总长度；总长度是 `*` 或格式不对时为 null。 */
fun contentRangeTotal(header: String?): Long? =
    header?.substringAfterLast('/', missingDelimiterValue = "")?.trim()?.toLongOrNull()

/**
 * 续传前核对：半成品旁边记的总长度和这次响应的总长度对不上，说明地址背后换了文件（重新取流后档位变了等），
 * 半成品要丢掉从头下，否则会拼出损坏的文件。任一边不知道总长度时无从比较，照常续传。
 */
fun isPartialStale(recordedTotal: Long?, responseTotal: Long?): Boolean =
    recordedTotal != null && responseTotal != null && recordedTotal != responseTotal

/**
 * 暂停时显示的进度：按半成品已下的字节算（`已下 to 总长`，视频是音轨、视频轨两段）。
 * 有一段不知道总长（还没开始下）就返回 0，界面只写「已暂停」不写百分比。
 */
fun stagedProgress(parts: List<Pair<Long, Long?>>): Float {
    if (parts.isEmpty() || parts.any { (_, total) -> total == null || total <= 0 }) return 0f
    val written = parts.sumOf { (done, _) -> done }
    val total = parts.sumOf { (_, total) -> total ?: 0L }
    return (written.toFloat() / total).coerceIn(0f, 1f)
}

/** 合成 MP4 时读一帧的缓冲区下限：高码率视频的关键帧常超过 64KB。 */
const val MIN_SAMPLE_BUFFER = 1 shl 20

/** 缓冲区上限：再大的帧就不是正常视频了。 */
const val MAX_SAMPLE_BUFFER = 64 shl 20

/** 合成时读一帧的缓冲区：按轨道格式里的最大帧大小（`KEY_MAX_INPUT_SIZE`），至少 [MIN_SAMPLE_BUFFER]。 */
fun sampleBufferSize(maxInputSize: Int?): Int = maxOf(maxInputSize ?: 0, MIN_SAMPLE_BUFFER)

/** 这一帧仍放不下：缓冲区翻倍，最多 [MAX_SAMPLE_BUFFER]；已到上限返回 null。 */
fun grownSampleBufferSize(current: Int): Int? =
    if (current >= MAX_SAMPLE_BUFFER) null else (current.toLong() * 2).coerceAtMost(MAX_SAMPLE_BUFFER.toLong()).toInt()

/**
 * 合成时这一帧写不写。样本按解码顺序读出，时间戳是显示时间：
 * 视频轨有 B 帧时本来就会倒退，必须全写（MediaMuxer 自 API 25 起支持乱序显示时间）——
 * 早先一律丢掉不递增的帧，B 帧全被丢，下载的视频只剩约四分之一的帧，播放卡顿。
 * 音频轨没有重排，重复 / 倒挂的样本丢掉即可。
 */
fun shouldWriteSample(isVideo: Boolean, timeUs: Long, lastTimeUs: Long): Boolean =
    isVideo || timeUs > lastTimeUs

/** 合成时下一帧从哪条轨取。 */
enum class MuxPick { AUDIO, VIDEO }

/**
 * 音视频按时间交错写进 MP4：谁的下一帧早先写谁，同一时刻先音频；一条读完（null）只写另一条，都读完返回 null。
 * 早先先写完整条音频再写视频，文件里音视频不交错，边下边播（拷到别处、网盘预览）要来回跳着读。
 */
fun nextMuxPick(audioTimeUs: Long?, videoTimeUs: Long?): MuxPick? = when {
    audioTimeUs == null && videoTimeUs == null -> null
    videoTimeUs == null -> MuxPick.AUDIO
    audioTimeUs == null -> MuxPick.VIDEO
    audioTimeUs <= videoTimeUs -> MuxPick.AUDIO
    else -> MuxPick.VIDEO
}

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
