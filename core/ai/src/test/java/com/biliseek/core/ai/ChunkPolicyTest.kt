package com.biliseek.core.ai

import com.biliseek.core.ai.internal.CompactLine
import com.biliseek.core.ai.internal.chunkTranscript
import com.biliseek.core.ai.internal.planMerge
import com.biliseek.core.ai.internal.splitOversized
import com.biliseek.core.ai.internal.utf8Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPolicyTest {

    private fun line(startS: Int, text: String) = CompactLine(startS * 1000L, startS * 1000L + 20_000, text)

    @Test
    fun `无章节时按体积装箱 每块不超上限`() {
        val lines = (0 until 20).map { line(it * 25, "内容".repeat(20)) } // 每行约 130 字节
        val chunks = chunkTranscript(lines, emptyList(), maxBytes = 500, useHours = false)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(utf8Size(it.text) <= 500) }
        assertEquals(20, chunks.sumOf { it.text.lines().size })
        assertEquals(0L, chunks.first().startMs)
    }

    @Test
    fun `有章节时按章节切 并带章节名`() {
        val lines = listOf(line(0, "前言"), line(60, "第一章"), line(120, "第二章"))
        val chapters = listOf(Chapter(60_000, 120_000, "一"), Chapter(120_000, 180_000, "二"))
        val chunks = chunkTranscript(lines, chapters, maxBytes = 10_000, useHours = false)
        assertEquals(listOf(null, "一", "二"), chunks.map { it.chapterTitle })
        assertEquals("[01:00] 第一章", chunks[1].text)
    }

    @Test
    fun `超长行切开后每片都带时间戳`() {
        val long = "这是一句话。".repeat(60) // 约 1080 字节
        val chunks = chunkTranscript(listOf(line(75, long)), emptyList(), maxBytes = 300, useHours = false)
        assertTrue(chunks.size > 1)
        chunks.flatMap { it.text.lines() }.forEach { assertTrue(it, it.startsWith("[01:15] ")) }
    }

    @Test
    fun `切分优先在窗口后半段的句末`() {
        val text = "甲".repeat(40) + "。" + "乙".repeat(40)
        val parts = splitOversized(text, maxBytes = 150) // 50 个汉字
        assertEquals("甲".repeat(40) + "。", parts[0])
    }

    @Test
    fun `没有标点时按上限硬切`() {
        val parts = splitOversized("字".repeat(100), maxBytes = 90) // 30 个汉字
        assertEquals(listOf(30, 30, 30, 10), parts.map { it.length })
    }

    @Test
    fun `分层合并 放得下返回 null 否则按序分组`() {
        assertNull(planMerge(listOf("a", "b"), maxBytes = 100))
        val parts = List(4) { "x".repeat(40) }
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), planMerge(parts, maxBytes = 100))
    }
}
