package com.biliseek.core.ai

import com.biliseek.core.ai.internal.MarkdownPolicy
import com.biliseek.core.ai.internal.TranscriptNotePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPolicyTest {

    @Test
    fun `剥掉代码块外壳`() {
        assertEquals("## 标题\n- 点", MarkdownPolicy.normalize("```markdown\n## 标题\n- 点\n```", null))
    }

    @Test
    fun `各种括号的时间戳归一`() {
        val out = MarkdownPolicy.normalize("- (3:12) 甲\n- 【03:15】乙\n- （04:01）丙", null)
        assertEquals("- [03:12] 甲\n- [03:15]乙\n- [04:01]丙", out)
    }

    @Test
    fun `秒数写法转成时间戳`() {
        assertEquals("- [00:12] 开场\n- [02:05] 重点", MarkdownPolicy.normalize("- 12.4 - 开场\n- 125 - 重点", null))
    }

    @Test
    fun `超出时长的时间戳去掉`() {
        val out = MarkdownPolicy.normalize("- [01:00] 有效\n- [59:00] 编的", durationMs = 120_000)
        assertEquals("- [01:00] 有效\n- 编的", out)
    }

    @Test
    fun `长视频统一带小时`() {
        assertEquals("[0:03:12] a", MarkdownPolicy.normalize("[03:12] a", durationMs = 4_000_000))
    }

    @Test
    fun `头部 元信息 模型 截断提示`() {
        val md = MarkdownPolicy.compose(
            title = "视频",
            meta = linkedMapOf("UP" to "某人", "时长" to "10:00", "空" to ""),
            models = listOf("A", "B"),
            body = "正文",
            truncated = true,
        )
        assertTrue(md.startsWith("# 视频\n\n> UP：某人 · 时长：10:00 · 模型：A、B\n\n正文"))
        assertFalse(md.contains("空："))
        assertTrue(md.contains("输出可能不完整"))
    }
}

class TranscriptNotePolicyTest {

    private fun seg(s: Int, t: String) = TranscriptSegment(s * 1000L, s * 1000L + 3000, t)

    @Test
    fun `无章节时每 5 分钟一节`() {
        val md = TranscriptNotePolicy.render(Transcript(listOf(seg(10, "甲"), seg(320, "乙"))))
        assertTrue(md, md.contains("## [00:00] 00:00 – 05:00"))
        assertTrue(md, md.contains("[00:10] 甲"))
        assertTrue(md, md.contains("## [05:00] 05:00 – 05:23"))
        assertTrue(md.startsWith("> 原文笔记"))
    }

    @Test
    fun `有章节时按章节分节 章节前的内容单独一节`() {
        val md = TranscriptNotePolicy.render(
            Transcript(listOf(seg(5, "前言"), seg(70, "正题")), chapters = listOf(Chapter(60_000, 120_000, "正题章"))),
        )
        assertTrue(md, md.contains("## [00:00]"))
        assertTrue(md, md.contains("## [01:00] 正题章"))
    }

    @Test
    fun `空原文`() {
        assertEquals("_（原文为空）_", TranscriptNotePolicy.render(Transcript(emptyList())))
    }
}
