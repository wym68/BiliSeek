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

    @Test
    fun `暂停的任务归在下载中段 排在最后`() {
        val tasks = listOf(
            task(1, DownloadStatus.PAUSED),
            task(2, DownloadStatus.QUEUED),
            task(3, DownloadStatus.DOWNLOADING),
        )
        assertEquals(listOf(3L, 2L, 1L), DownloadListPolicy.sections(tasks).active.map { it.id })
    }

    @Test
    fun `段头有在下或排队的显示全部暂停 全是暂停的显示全部继续`() {
        assertEquals(
            ActiveBulkAction.PAUSE_ALL,
            DownloadListPolicy.bulkAction(listOf(task(1, DownloadStatus.PAUSED), task(2, DownloadStatus.QUEUED))),
        )
        assertEquals(
            ActiveBulkAction.RESUME_ALL,
            DownloadListPolicy.bulkAction(listOf(task(1, DownloadStatus.PAUSED), task(2, DownloadStatus.PAUSED))),
        )
        assertEquals(null, DownloadListPolicy.bulkAction(emptyList()))
    }

    // ── 已完成：同一个视频的多个分 P 合成一组 ──────────────────

    private fun done(id: Long, bvid: String, title: String, size: Long = 10) = task(id, DownloadStatus.DONE)
        .copy(bvid = bvid, title = title, fileSize = size)

    @Test
    fun `分 P 标题拆成视频标题和 P几 分P名`() {
        assertEquals("潜伏解读" to "P2 第二集", splitPartTitle("潜伏解读 P2 第二集"))
        assertEquals(
            "【Hi-Res无损】G.E.M.邓紫棋歌曲100首精选！" to "P1 1.多远都要在一起",
            splitPartTitle("【Hi-Res无损】G.E.M.邓紫棋歌曲100首精选！ P1 1.多远都要在一起"),
        )
        assertEquals("视频" to "P3", splitPartTitle("视频 P3"))
        assertEquals("普通视频" to null, splitPartTitle("普通视频"))
    }

    @Test
    fun `同一个视频下完两个及以上分 P 合成一组 按 P 几排序 组的位置在它最新的一条`() {
        val entries = DownloadListPolicy.doneEntries(
            listOf(
                done(1, "BVa", "合集 P2 二", size = 20),
                done(2, "BVb", "单个视频"),
                done(3, "BVa", "合集 P10 十", size = 30),
                done(4, "BVa", "合集 P1 一", size = 50),
            ),
        )
        assertEquals(2, entries.size)
        val group = entries[0] as DoneEntry.Group
        assertEquals("BVa", group.bvid)
        assertEquals("合集", group.title)
        assertEquals(listOf(4L, 1L, 3L), group.parts.map { it.id })
        assertEquals(100L, group.totalSize)
        assertEquals(2L, (entries[1] as DoneEntry.Single).task.id)
    }

    @Test
    fun `一个视频只下了一个分 P 仍是单条`() {
        val entries = DownloadListPolicy.doneEntries(listOf(done(1, "BVa", "合集 P3 三")))
        assertEquals(1L, (entries.single() as DoneEntry.Single).task.id)
    }
}
