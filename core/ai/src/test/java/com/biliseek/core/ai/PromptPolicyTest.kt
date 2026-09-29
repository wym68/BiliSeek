package com.biliseek.core.ai

import com.biliseek.core.ai.internal.Chunk
import com.biliseek.core.ai.internal.PromptPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPolicyTest {

    private val timing = Transcript.Timing.SEGMENT

    @Test
    fun `有参考信息时附上 并声明不是视频内容`() {
        val user = PromptPolicy.single("标题", VideoContext("这是简介", listOf("知识")), SummaryStyle.Brief, timing, "[00:01] 原文")[1].content
        assertTrue(user.contains("【视频标题】标题"))
        assertTrue(user.contains("简介：这是简介"))
        assertTrue(user.contains("分区/标签：知识"))
        assertTrue(user.contains("不是视频内容"))
        assertTrue(user.endsWith("【视频原文】\n[00:01] 原文"))
    }

    @Test
    fun `没有参考信息时不出现参考段`() {
        val user = PromptPolicy.single("标题", null, SummaryStyle.Brief, timing, "x")[1].content
        assertFalse(user.contains("参考信息"))
    }

    @Test
    fun `简介过长截断`() {
        val user = PromptPolicy.single("t", VideoContext("长".repeat(2_000), emptyList()), SummaryStyle.Brief, timing, "x")[1].content
        assertFalse(user.contains("长".repeat(501)))
    }

    @Test
    fun `段落级时间精度时换用对应规则`() {
        val system = PromptPolicy.baseRules(Transcript.Timing.CHUNK)
        assertTrue(system.contains("只标在小节标题上"))
    }

    @Test
    fun `自定义要求写入系统提示`() {
        val system = PromptPolicy.single("t", null, SummaryStyle.Custom("用三句话概括"), timing, "x")[0].content
        assertTrue(system.contains("用三句话概括"))
    }

    @Test
    fun `分块提示 带序号与章节 要求保留全部事实`() {
        val messages = PromptPolicy.chunk("t", null, timing, Chunk(0, 1, "第一章", "[00:00] a"), 2, 5)
        assertTrue(messages[0].content.contains("第 2/5 段"))
        assertTrue(messages[0].content.contains("完整保留所有事实"))
        assertTrue(messages[1].content.contains("【本段所属章节】第一章"))
    }

    @Test
    fun `最终合并带风格 压缩合并不带`() {
        val final = PromptPolicy.finalMerge("t", null, SummaryStyle.TimestampOutline, timing, listOf("a", "b"))[0].content
        assertTrue(final.contains("分段大纲"))
        val condense = PromptPolicy.condense("t", timing, listOf("a", "b"))[0].content
        assertFalse(condense.contains("分段大纲"))
        assertTrue(PromptPolicy.condense("t", timing, listOf("a", "b"))[1].content.contains("【第 2 段笔记】"))
    }
}
