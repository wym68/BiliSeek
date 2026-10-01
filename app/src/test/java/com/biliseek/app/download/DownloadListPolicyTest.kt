package com.biliseek.app.download

import com.biliseek.app.data.DownloadContent
import com.biliseek.app.data.DownloadLocation
import com.biliseek.app.data.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadListPolicyTest {

    private fun task(id: Long, status: String) = DownloadTask(
        id = id, bvid = "BV$id", cid = id, title = "视频$id", author = "", coverUrl = "",
        content = DownloadContent.AUDIO, location = DownloadLocation.PRIVATE,
        status = status, progress = 0f, error = "", fileSize = 0,
    )

    @Test
    fun `按状态分三段，下载中在排队前`() {
        val tasks = listOf(
            task(1, DownloadStatus.QUEUED),
            task(2, DownloadStatus.DONE),
            task(3, DownloadStatus.DOWNLOADING),
            task(4, DownloadStatus.FAILED),
            task(5, DownloadStatus.QUEUED),
            task(6, DownloadStatus.DONE),
        )
        val s = DownloadListPolicy.sections(tasks)
        assertEquals(listOf(3L, 1L, 5L), s.active.map { it.id })
        assertEquals(listOf(4L), s.failed.map { it.id })
        assertEquals(listOf(2L, 6L), s.done.map { it.id })
    }

    @Test
    fun `没有任务时为空`() {
        assertTrue(DownloadListPolicy.sections(emptyList()).isEmpty)
    }
}
