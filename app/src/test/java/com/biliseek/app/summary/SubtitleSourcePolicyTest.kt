package com.biliseek.app.summary

import com.biliseek.core.ai.Chapter
import com.biliseek.core.network.dto.SubtitleLine
import com.biliseek.core.network.dto.SubtitleTrack
import com.biliseek.core.network.dto.ViewPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/** 工单 AI-4：选字幕、决定来源、字幕与章节映射。 */
class SubtitleSourcePolicyTest {

    private fun track(lan: String, url: String = "//aisubtitle.hdslb.com/$lan.json", ai: Int = 0) =
        SubtitleTrack(lan = lan, subtitleUrl = url, aiType = ai)

    @Test
    fun `人工中文优先于 AI 中文`() {
        val human = track("zh-CN")
        val ai = track("ai-zh", ai = 1)
        assertEquals(SubtitlePick.Use(human), SubtitlePickPolicy.pick(listOf(ai, human)))
    }

    @Test
    fun `没有人工中文时用 AI 中文 再其次任意语言首条`() {
        val ai = track("ai-zh", ai = 1)
        val en = track("en-US")
        assertEquals(SubtitlePick.Use(ai), SubtitlePickPolicy.pick(listOf(en, ai)))
        assertEquals(SubtitlePick.Use(en), SubtitlePickPolicy.pick(listOf(en, track("ja"))))
    }

    @Test
    fun `繁体中文也算中文`() {
        val hant = track("zh-Hant")
        assertEquals(SubtitlePick.Use(hant), SubtitlePickPolicy.pick(listOf(track("en"), hant)))
    }

    @Test
    fun `有轨道但地址都为空 需要登录`() {
        assertEquals(SubtitlePick.NeedsLogin, SubtitlePickPolicy.pick(listOf(track("ai-zh", url = "", ai = 1))))
    }

    @Test
    fun `没有字幕轨`() {
        assertEquals(SubtitlePick.None, SubtitlePickPolicy.pick(emptyList()))
    }

    @Test
    fun `来源决策`() {
        val t = track("zh-CN")
        assertEquals(SummarySource.UseSubtitle(t), SummarySourcePolicy.resolve(SubtitlePick.Use(t), false, false))
        assertEquals(SummarySource.NeedsLoginOrAsr(true), SummarySourcePolicy.resolve(SubtitlePick.NeedsLogin, true, false))
        assertEquals(SummarySource.UseLocalAsr(true), SummarySourcePolicy.resolve(SubtitlePick.None, true, true))
        assertEquals(SummarySource.NeedsAsrModel, SummarySourcePolicy.resolve(SubtitlePick.None, false, true))
    }

    @Test
    fun `字幕行映射 去空行 按时间排序`() {
        val segments = SubtitleMapping.segments(
            listOf(SubtitleLine(3.0, 4.5, "二"), SubtitleLine(0.5, 2.0, " 一 "), SubtitleLine(5.0, 6.0, "  ")),
        )
        assertEquals(listOf(500L, 3000L), segments.map { it.startMs })
        assertEquals("一", segments[0].text)
        assertEquals(4500L, segments[1].endMs)
    }

    @Test
    fun `章节映射 缺结束时间时用下一章开始或视频时长`() {
        val chapters = SubtitleMapping.chapters(
            listOf(ViewPoint(from = 60, to = 0, content = "二"), ViewPoint(from = 0, to = 60, content = "一"), ViewPoint(90, 0, "三")),
            durationMs = 120_000,
        )
        assertEquals(
            listOf(Chapter(0, 60_000, "一"), Chapter(60_000, 90_000, "二"), Chapter(90_000, 120_000, "三")),
            chapters,
        )
    }
}
