package com.biliseek.app.data

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
}
