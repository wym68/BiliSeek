package com.biliseek.app.data

import com.biliseek.core.player.OfflineMedia
import org.junit.Assert.assertEquals
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
}
