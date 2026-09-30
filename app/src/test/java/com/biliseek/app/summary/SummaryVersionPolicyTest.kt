package com.biliseek.app.summary

import com.biliseek.app.data.AiSummarySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SummaryVersionPolicyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun selectsRequestedVersionElseNewest() {
        val ids = listOf(30L, 20L, 10L) // 新的在前
        assertEquals(30L, SummaryVersionPolicy.select(ids, null))
        assertEquals(20L, SummaryVersionPolicy.select(ids, 20L))
        // 指定的版本已被删除 → 退回最新
        assertEquals(30L, SummaryVersionPolicy.select(ids, 99L))
        assertNull(SummaryVersionPolicy.select(emptyList(), 5L))
    }

    @Test
    fun ordinalCountsFromOldestSoNewestIsBiggest() {
        val ids = listOf(30L, 20L, 10L)
        assertEquals(3, SummaryVersionPolicy.ordinal(ids, 30L))
        assertEquals(1, SummaryVersionPolicy.ordinal(ids, 10L))
        assertEquals(0, SummaryVersionPolicy.ordinal(ids, 7L))
    }

    @Test
    fun labelUsesStyleNameOrOfficial() {
        val style = SummaryVersionPolicy.label(StyleChoice.OUTLINE.name, AiSummarySource.SUBTITLE, 0L)
        assertTrue(style, style.startsWith(StyleChoice.OUTLINE.label + " · "))
        assertTrue(SummaryVersionPolicy.label("OUTLINE", AiSummarySource.OFFICIAL, 0L).startsWith("B 站官方总结 · "))
        // 未知风格（以后删掉的枚举值）直接显示原名，不崩
        assertTrue(SummaryVersionPolicy.label("OLD_STYLE", AiSummarySource.SUBTITLE, 0L).startsWith("OLD_STYLE · "))
    }

    @Test
    fun transcriptDiscardedOnlyWhenSourceChanges() {
        assertFalse(SummaryVersionPolicy.shouldDiscardTranscript(null, AiSummarySource.SUBTITLE))
        assertFalse(SummaryVersionPolicy.shouldDiscardTranscript(AiSummarySource.SUBTITLE, AiSummarySource.SUBTITLE))
        assertTrue(SummaryVersionPolicy.shouldDiscardTranscript(AiSummarySource.SUBTITLE, AiSummarySource.LOCAL_ASR))
        assertTrue(SummaryVersionPolicy.shouldDiscardTranscript(AiSummarySource.LOCAL_ASR, AiSummarySource.SUBTITLE))
        // 批量（来源执行时才定）与来源未定的现存原文都沿用
        assertFalse(SummaryVersionPolicy.shouldDiscardTranscript(AiSummarySource.SUBTITLE, AiSummarySource.AUTO))
        assertFalse(SummaryVersionPolicy.shouldDiscardTranscript(AiSummarySource.AUTO, AiSummarySource.LOCAL_ASR))
    }

    @Test
    fun officialVersionIsReplacedInPlace() {
        val versions = listOf(
            30L to AiSummarySource.SUBTITLE,
            20L to AiSummarySource.OFFICIAL,
            10L to AiSummarySource.OFFICIAL,
        )
        assertEquals(20L, SummaryVersionPolicy.officialVersionToReplace(versions))
        assertNull(SummaryVersionPolicy.officialVersionToReplace(listOf(30L to AiSummarySource.SUBTITLE)))
        assertNull(SummaryVersionPolicy.officialVersionToReplace(emptyList()))
    }

    @Test
    fun legacyFilesMoveIntoVersionDirectory() {
        val video = tmp.newFolder("BV1x_10")
        File(video, "summary.md").writeText("旧总结")
        File(video, "checkpoint.json").writeText("{}")
        File(video, "transcript.json").writeText("原文") // 视频共用，不动
        val version = File(video, "v7")

        assertTrue(SummaryLayoutMigration.moveLegacy(video, version))

        assertEquals("旧总结", File(version, "summary.md").readText())
        assertEquals("{}", File(version, "checkpoint.json").readText())
        assertFalse(File(video, "summary.md").exists())
        assertTrue(File(video, "transcript.json").exists())
    }

    @Test
    fun legacyMigrationIsIdempotentAndNeverOverwrites() {
        val video = tmp.newFolder("BV1y_11")
        val version = File(video, "v8").apply { mkdirs() }
        File(version, "summary.md").writeText("新版本的正文")
        // 视频目录里没有旧文件：什么也不做
        assertFalse(SummaryLayoutMigration.moveLegacy(video, version))
        // 目标已存在时不覆盖（即使源还在）
        File(video, "summary.md").writeText("旧的")
        assertFalse(SummaryLayoutMigration.moveLegacy(video, version))
        assertEquals("新版本的正文", File(version, "summary.md").readText())
    }
}
