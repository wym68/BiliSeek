package com.biliseek.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueuePolicyTest {

    private fun replaceable(oldCid: Long, requestCid: Long?, oldStatus: String = DownloadStatus.FAILED, oldBvid: String = "BV1") =
        isReplaceableFailedRecord(oldBvid, oldCid, oldStatus, "BV1", requestCid)

    // ── 入队时清理哪些旧失败记录 ──

    @Test
    fun `同一 P 的失败记录被顶替`() {
        assertTrue(replaceable(oldCid = 3, requestCid = 3))
    }

    @Test
    fun `别的 P 的失败记录保留`() {
        assertFalse(replaceable(oldCid = 2, requestCid = 3))
        // 分 P 未知的请求也不能清掉已知分 P 的失败记录
        assertFalse(replaceable(oldCid = 2, requestCid = null))
    }

    @Test
    fun `分 P 未知的占位失败记录总是被顶替`() {
        assertTrue(replaceable(oldCid = 0, requestCid = 3))
        assertTrue(replaceable(oldCid = 0, requestCid = null))
    }

    @Test
    fun `非失败记录与别的视频不动`() {
        assertFalse(replaceable(oldCid = 3, requestCid = 3, oldStatus = DownloadStatus.DONE))
        assertFalse(replaceable(oldCid = 3, requestCid = 3, oldStatus = DownloadStatus.QUEUED))
        assertFalse(replaceable(oldCid = 3, requestCid = 3, oldBvid = "BV2"))
    }

    // ── 入队去重 ──

    @Test
    fun `已在排队或下载中的同一条跳过`() {
        assertTrue(shouldSkipEnqueue(DownloadStatus.QUEUED))
        assertTrue(shouldSkipEnqueue(DownloadStatus.DOWNLOADING))
    }

    @Test
    fun `已完成失败或没有记录时照常入队`() {
        assertFalse(shouldSkipEnqueue(DownloadStatus.DONE))
        assertFalse(shouldSkipEnqueue(DownloadStatus.FAILED))
        assertFalse(shouldSkipEnqueue(null))
    }

    // ── 取流节流 ──

    @Test
    fun `第一次取流不等`() {
        assertEquals(0L, downloadResolveDelayMs(lastAtMs = null, nowMs = 10_000))
    }

    @Test
    fun `间隔不足时补足剩余时间`() {
        assertEquals(1_500L, downloadResolveDelayMs(lastAtMs = 10_000, nowMs = 10_500, minIntervalMs = 2_000))
    }

    @Test
    fun `间隔已够或时钟回拨时不等`() {
        assertEquals(0L, downloadResolveDelayMs(lastAtMs = 10_000, nowMs = 12_000, minIntervalMs = 2_000))
        assertEquals(0L, downloadResolveDelayMs(lastAtMs = 10_000, nowMs = 9_000, minIntervalMs = 2_000))
    }

    // ── 同时下载数 ──

    @Test
    fun `同时下载数收进 1 到 3`() {
        assertEquals(1, clampDownloadConcurrency(0))
        assertEquals(1, clampDownloadConcurrency(-5))
        assertEquals(2, clampDownloadConcurrency(2))
        assertEquals(3, clampDownloadConcurrency(3))
        assertEquals(3, clampDownloadConcurrency(10))
        assertEquals(listOf(1, 2, 3), DOWNLOAD_CONCURRENCY_CHOICES)
    }

    // ── 分 P 下载标题 ──

    @Test
    fun `分 P 标题前补视频标题`() {
        assertEquals("潜伏解读 P2 第二集", downloadTitleFor("潜伏解读", "P2 第二集"))
    }

    @Test
    fun `已带视频标题或缺一方时不重复拼`() {
        assertEquals("潜伏解读 P2 第二集", downloadTitleFor("潜伏解读", "潜伏解读 P2 第二集"))
        assertEquals("P2 第二集", downloadTitleFor("", "P2 第二集"))
        assertEquals("潜伏解读", downloadTitleFor("潜伏解读", " "))
    }
}
