package com.biliseek.app.download

import com.biliseek.app.data.DownloadContent
import com.biliseek.app.data.DownloadLocation
import com.biliseek.app.data.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadNoticePolicyTest {

    private fun task(id: Long, status: String, title: String = "视频$id") = DownloadTask(
        id = id,
        bvid = "BV$id",
        cid = id,
        title = title,
        author = "",
        coverUrl = "",
        content = DownloadContent.AUDIO,
        location = DownloadLocation.PRIVATE,
        status = status,
        progress = 0f,
        error = "",
        fileSize = 0,
    )

    @Test
    fun `下载中变为已完成时提示标题`() {
        val before = listOf(task(1, DownloadStatus.DOWNLOADING, "李涯"))
        val after = listOf(task(1, DownloadStatus.DONE, "李涯"))
        assertEquals("下载完成：李涯", DownloadNoticePolicy.completionMessage(before, after))
    }

    @Test
    fun `排队中直接变为已完成也算（中间状态被合并）`() {
        val before = listOf(task(1, DownloadStatus.QUEUED))
        val after = listOf(task(1, DownloadStatus.DONE))
        assertEquals(listOf(1L), DownloadNoticePolicy.newlyCompleted(before, after).map { it.id })
    }

    @Test
    fun `启动时首次加载出的已完成记录不提示`() {
        assertNull(DownloadNoticePolicy.completionMessage(emptyList(), listOf(task(1, DownloadStatus.DONE))))
    }

    @Test
    fun `已完成的记录再次刷新不提示`() {
        val done = listOf(task(1, DownloadStatus.DONE))
        assertNull(DownloadNoticePolicy.completionMessage(done, done))
    }

    @Test
    fun `失败与进度变化不提示`() {
        val before = listOf(task(1, DownloadStatus.DOWNLOADING), task(2, DownloadStatus.DOWNLOADING))
        val after = listOf(task(1, DownloadStatus.FAILED), task(2, DownloadStatus.DOWNLOADING))
        assertNull(DownloadNoticePolicy.completionMessage(before, after))
    }

    @Test
    fun `多条同时完成合并成一条提示`() {
        val before = listOf(task(1, DownloadStatus.DOWNLOADING), task(2, DownloadStatus.QUEUED))
        val after = listOf(task(1, DownloadStatus.DONE), task(2, DownloadStatus.DONE))
        assertEquals("2 个下载已完成", DownloadNoticePolicy.completionMessage(before, after))
    }

    @Test
    fun `空标题显示未命名`() {
        val before = listOf(task(1, DownloadStatus.DOWNLOADING, ""))
        val after = listOf(task(1, DownloadStatus.DONE, ""))
        assertEquals("下载完成：未命名", DownloadNoticePolicy.completionMessage(before, after))
    }

    // ── 多 P：一个视频的分 P 全部下完才提醒一次（用户 2026-10-04 选定）──

    private fun part(id: Long, status: String, p: Int) = task(id, status, "合集 P$p 第${p}集").copy(bvid = "BVm")

    @Test
    fun `同一个视频还有分 P 在排队 下载或暂停时 先不提醒`() {
        val before = listOf(part(1, DownloadStatus.DOWNLOADING, 1), part(2, DownloadStatus.QUEUED, 2))
        val after = listOf(part(1, DownloadStatus.DONE, 1), part(2, DownloadStatus.DOWNLOADING, 2))
        assertNull(DownloadNoticePolicy.completionMessage(before, after))
        val paused = listOf(part(1, DownloadStatus.DONE, 1), part(2, DownloadStatus.PAUSED, 2))
        assertNull(DownloadNoticePolicy.completionMessage(before, paused))
    }

    @Test
    fun `最后一个分 P 完成时提醒一次 带分 P 数`() {
        val before = listOf(part(1, DownloadStatus.DONE, 1), part(2, DownloadStatus.DONE, 2), part(3, DownloadStatus.DOWNLOADING, 3))
        val after = listOf(part(1, DownloadStatus.DONE, 1), part(2, DownloadStatus.DONE, 2), part(3, DownloadStatus.DONE, 3))
        assertEquals("下载完成：合集（共 3 个分 P）", DownloadNoticePolicy.completionMessage(before, after))
    }
}
