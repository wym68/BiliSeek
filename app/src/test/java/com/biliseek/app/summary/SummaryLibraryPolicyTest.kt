package com.biliseek.app.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryLibraryPolicyTest {
    private fun row(
        bvid: String,
        cid: Long = 1,
        status: String = "DONE",
        updatedAt: Long,
        title: String = "标题$bvid",
    ) = SummaryRowInfo(bvid, cid, title, "", "作者", "cover", 60_000, status, updatedAt)

    @Test
    fun versionsOfTheSameVideoMergeIntoOneRowUsingTheNewestFields() {
        val groups = SummaryLibraryPolicy.group(
            listOf(
                row("BV1", updatedAt = 10, title = "旧标题"),
                row("BV1", updatedAt = 30, title = "新标题"),
                row("BV1", updatedAt = 20),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals("新标题", groups[0].title)
        assertEquals(3, groups[0].versionCount)
        assertEquals(3, groups[0].doneCount)
        assertEquals(SummaryGroupState.READY, groups[0].state)
    }

    @Test
    fun differentPartsOfOneVideoAreSeparateRows() {
        val groups = SummaryLibraryPolicy.group(listOf(row("BV1", cid = 1, updatedAt = 1), row("BV1", cid = 2, updatedAt = 2)))
        assertEquals(2, groups.size)
    }

    @Test
    fun sortedByMostRecentlyUpdated() {
        val groups = SummaryLibraryPolicy.group(
            listOf(row("A", updatedAt = 5), row("B", updatedAt = 50), row("C", updatedAt = 20)),
        )
        assertEquals(listOf("B", "C", "A"), groups.map { it.bvid })
    }

    @Test
    fun stateReflectsRunningAndFailed() {
        // 有版本在生成 → RUNNING（哪怕别的版本已完成）
        val running = SummaryLibraryPolicy.group(
            listOf(row("A", status = "DONE", updatedAt = 1), row("A", status = "SUMMARIZING", updatedAt = 2)),
        ).single()
        assertEquals(SummaryGroupState.RUNNING, running.state)
        // 只有失败的 → FAILED
        val failed = SummaryLibraryPolicy.group(listOf(row("B", status = "FAILED", updatedAt = 1))).single()
        assertEquals(SummaryGroupState.FAILED, failed.state)
        // 已有完成的版本，另一个失败 → 仍是 READY（有东西可看）
        val ready = SummaryLibraryPolicy.group(
            listOf(row("C", status = "DONE", updatedAt = 1), row("C", status = "FAILED", updatedAt = 2)),
        ).single()
        assertEquals(SummaryGroupState.READY, ready.state)
        assertEquals(1, ready.doneCount)
    }

    @Test
    fun emptyInputGivesNoGroups() {
        assertTrue(SummaryLibraryPolicy.group(emptyList()).isEmpty())
    }

    @Test
    fun sizeFormatting() {
        assertEquals("320 B", SummaryLibraryPolicy.formatSize(320))
        assertEquals("850 KB", SummaryLibraryPolicy.formatSize(850 * 1024L))
        assertEquals("12.3 MB", SummaryLibraryPolicy.formatSize((12.3 * 1024 * 1024).toLong()))
        assertEquals("0 B", SummaryLibraryPolicy.formatSize(0))
    }

    @Test
    fun subtitleShowsStatusVersionsAndSize() {
        val base = SummaryGroup("B", 1, "t", "", "a", "", 0, versionCount = 1, doneCount = 1, state = SummaryGroupState.READY, updatedAt = 0, sizeBytes = 2048)
        assertEquals("1 个版本 · 2 KB", SummaryLibraryPolicy.subtitle(base))
        assertEquals("3 个版本 · 2 KB", SummaryLibraryPolicy.subtitle(base.copy(versionCount = 3)))
        assertEquals("生成中 · 1 个版本 · 2 KB", SummaryLibraryPolicy.subtitle(base.copy(state = SummaryGroupState.RUNNING)))
        assertEquals("生成失败 · 1 个版本 · 2 KB", SummaryLibraryPolicy.subtitle(base.copy(state = SummaryGroupState.FAILED)))
    }

    @Test
    fun clearAllMessageStatesScale() {
        val groups = listOf(
            SummaryGroup("A", 1, "t", "", "a", "", 0, 2, 2, SummaryGroupState.READY, 0, sizeBytes = 1024 * 1024L),
            SummaryGroup("B", 1, "t", "", "a", "", 0, 1, 1, SummaryGroupState.READY, 0, sizeBytes = 1024 * 1024L),
        )
        val message = SummaryLibraryPolicy.clearAllMessage(groups)
        assertTrue(message, message.contains("2 个视频") && message.contains("3 个总结版本") && message.contains("2.0 MB"))
    }

    @Test
    fun `右滑删除后撤销期内不显示 总占用跟着减`() {
        fun g(bvid: String, size: Long) = SummaryGroup(
            bvid = bvid, cid = 1, title = bvid, pageTitle = "", author = "", coverUrl = "", durationMs = 0,
            versionCount = 1, doneCount = 1, state = SummaryGroupState.READY, updatedAt = 0, sizeBytes = size,
        )
        val state = SummaryLibraryState(listOf(g("BVa", 100), g("BVb", 50)), totalBytes = 150)
        val visible = SummaryLibraryPolicy.withoutHidden(state, setOf(SummaryLibraryPolicy.key(g("BVa", 100))))
        assertEquals(listOf("BVb"), visible.groups.map { it.bvid })
        assertEquals(50L, visible.totalBytes)
        assertEquals(state, SummaryLibraryPolicy.withoutHidden(state, emptySet()))
    }
}
