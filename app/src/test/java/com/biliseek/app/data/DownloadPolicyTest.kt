package com.biliseek.app.data

import com.biliseek.core.player.OfflineMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载策略（M9 §9.8 自动化清单前三条）。
 *
 * 文件名、Range 续传、离线命中都是「错了不会崩、只会静默不对」的逻辑，
 * 落在文件系统 / 播放链路上极难排查，必须纯函数化 + 单测钉住。
 */
class DownloadPolicyTest {

    // ── 文件名与路径推导 ──────────────────────────────────

    @Test
    fun `只音频是 m4a 视频是 mp4`() {
        assertEquals("BV1xx411c7mD_100.m4a", downloadFileName("BV1xx411c7mD", 100, DownloadContent.AUDIO))
        assertEquals("BV1xx411c7mD_100.mp4", downloadFileName("BV1xx411c7mD", 100, DownloadContent.VIDEO))
    }

    @Test
    fun `私有目录路径是 downloads 下`() {
        assertEquals(
            "downloads/BV1xx411c7mD_100.m4a",
            privateDownloadRelPath("BV1xx411c7mD", 100, DownloadContent.AUDIO),
        )
    }

    // ── 断点续传的 Range ──────────────────────────────────

    @Test
    fun `已有字节数大于零才发 Range`() {
        assertNull(resolveRangeHeader(0))
        assertNull(resolveRangeHeader(-1))
        assertEquals("bytes=1024-", resolveRangeHeader(1024))
        assertEquals("bytes=1048576-", resolveRangeHeader(1024 * 1024))
    }

    // ── 离线命中判定 ──────────────────────────────────────

    private fun doneRow(
        content: String = DownloadContent.AUDIO,
        audioFile: String = "file:///data/1.m4a",
        videoFile: String = "",
        location: String = DownloadLocation.PRIVATE,
    ) = DownloadEntity(
        id = 1,
        bvid = "BV1xx411c7mD",
        cid = 100,
        title = "t",
        author = "a",
        coverUrl = "",
        durationSec = 233,
        content = content,
        location = location,
        safTreeUri = "",
        audioFile = audioFile,
        videoFile = videoFile,
        fileSize = 1024,
        status = DownloadStatus.DONE,
        error = "",
        downloadedAt = 0,
    )

    @Test
    fun `完成且文件在则命中`() {
        val hit = resolveOfflineHit(doneRow(), fileExists = { true })
        assertTrue(hit is OfflineLookupResult.Hit)
        val media = (hit as OfflineLookupResult.Hit).media
        assertEquals("file:///data/1.m4a", media.uri)
        assertEquals("audio/mp4", media.mimeType)
        assertEquals(233_000L, media.durationMs)
        assertEquals(false, media.hasVideoTrack)
    }

    @Test
    fun `视频下载优先播视频文件且带视频轨`() {
        val hit = resolveOfflineHit(
            doneRow(content = DownloadContent.VIDEO, videoFile = "file:///data/1.mp4"),
            fileExists = { true },
        )
        val media = (hit as OfflineLookupResult.Hit).media
        assertEquals("file:///data/1.mp4", media.uri)
        assertEquals("video/mp4", media.mimeType)
        assertEquals(true, media.hasVideoTrack)
    }

    @Test
    fun `文件缺失时返回 Stale 供调用方删记录并回落`() {
        val result = resolveOfflineHit(doneRow(), fileExists = { false })
        assertTrue(result is OfflineLookupResult.Stale)
    }

    @Test
    fun `无记录或未完成都算 Miss`() {
        assertTrue(resolveOfflineHit(null, fileExists = { true }) is OfflineLookupResult.Miss)
        val queued = doneRow().copy(status = DownloadStatus.QUEUED)
        assertTrue(resolveOfflineHit(queued, fileExists = { true }) is OfflineLookupResult.Miss)
        val failed = doneRow().copy(status = DownloadStatus.FAILED)
        assertTrue(resolveOfflineHit(failed, fileExists = { true }) is OfflineLookupResult.Miss)
    }

    @Test
    fun `文件引用为空也算 Miss`() {
        val empty = doneRow(audioFile = "", videoFile = "")
        assertTrue(resolveOfflineHit(empty, fileExists = { true }) is OfflineLookupResult.Miss)
    }

    // ── 下载中途断开：从断点接着下（2026-10-04 视频下载失败 unexpected end of stream）──

    @Test
    fun `这一轮写进了新数据 没进展计数清零 否则加一`() {
        assertEquals(0, nextStalledCount(current = 2, madeProgress = true))
        assertEquals(1, nextStalledCount(current = 0, madeProgress = false))
        assertEquals(3, nextStalledCount(current = 2, madeProgress = false))
    }

    @Test
    fun `连接断开接着下 连续三次没进展或 HTTP 状态错误不再续`() {
        assertTrue(shouldResumeDownload(isHttpStatusError = false, stalledCount = 0))
        assertTrue(shouldResumeDownload(isHttpStatusError = false, stalledCount = 2))
        assertFalse(shouldResumeDownload(isHttpStatusError = false, stalledCount = 3))
        assertFalse(shouldResumeDownload(isHttpStatusError = true, stalledCount = 0))
    }

    @Test
    fun `从 Content-Range 取总长度`() {
        assertEquals(1000L, contentRangeTotal("bytes 100-999/1000"))
        assertNull(contentRangeTotal("bytes 100-999/*"))
        assertNull(contentRangeTotal(null))
        assertNull(contentRangeTotal("乱写的"))
    }

    @Test
    fun `半成品记的总长度和这次对不上 说明换了文件 要丢掉重下`() {
        assertTrue(isPartialStale(recordedTotal = 1000, responseTotal = 2000))
        assertFalse(isPartialStale(recordedTotal = 1000, responseTotal = 1000))
        // 任一边不知道总长度时无从比较，照常续传
        assertFalse(isPartialStale(recordedTotal = null, responseTotal = 2000))
        assertFalse(isPartialStale(recordedTotal = 1000, responseTotal = null))
    }

    @Test
    fun `合成缓冲区按最大帧大小 至少 1MB 放不下翻倍 最多 64MB`() {
        assertEquals(1 shl 20, sampleBufferSize(maxInputSize = null))
        assertEquals(1 shl 20, sampleBufferSize(maxInputSize = 65_536))
        assertEquals(3_000_000, sampleBufferSize(maxInputSize = 3_000_000))
        assertEquals(2 shl 20, grownSampleBufferSize(current = 1 shl 20))
        assertEquals(64 shl 20, grownSampleBufferSize(current = 40 shl 20))
        assertNull(grownSampleBufferSize(current = 64 shl 20))
    }

    @Test
    fun `暂停时的进度按已下的部分算 有一段不知道总长就不显示百分比`() {
        assertEquals(0.5f, stagedProgress(listOf(50L to 100L)), 0.001f)
        // 视频：音轨下完、视频轨下了一半
        assertEquals(0.75f, stagedProgress(listOf(100L to 100L, 50L to 100L)), 0.001f)
        // 视频轨还没开始下（不知道总长）
        assertEquals(0f, stagedProgress(listOf(100L to 100L, 0L to null)), 0.001f)
        assertEquals(0f, stagedProgress(emptyList()), 0.001f)
    }
}
