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
}
