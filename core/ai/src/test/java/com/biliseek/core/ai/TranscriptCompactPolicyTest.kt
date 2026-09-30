package com.biliseek.core.ai

import com.biliseek.core.ai.internal.compactTranscript
import com.biliseek.core.ai.internal.formatTimestamp
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptCompactPolicyTest {

    private fun seg(startS: Int, text: String) = TranscriptSegment(startS * 1000L, startS * 1000L + 2000, text)

    @Test
    fun `按时间窗合并成一行`() {
        val lines = compactTranscript(
            listOf(seg(0, "大家好"), seg(3, "今天讲"), seg(10, "离线识别"), seg(30, "第二段")),
            windowMs = 25_000,
        )
        assertEquals(2, lines.size)
        assertEquals("[00:00] 大家好今天讲离线识别", lines[0].render(false))
        assertEquals("[00:30] 第二段", lines[1].render(false))
    }

    @Test
    fun `英文单词之间补空格`() {
        val lines = compactTranscript(listOf(seg(0, "hello"), seg(1, "world"), seg(2, "你好")))
        assertEquals("hello world你好", lines.single().text)
    }

    @Test
    fun `去掉无信息量的行`() {
        val lines = compactTranscript(listOf(seg(0, "[音乐]"), seg(1, "♪♪"), seg(2, "  "), seg(3, "（笑）"), seg(4, "正文")))
        assertEquals("正文", lines.single().text)
    }

    @Test
    fun `章节边界处强制断行`() {
        val lines = compactTranscript(
            listOf(seg(0, "开场"), seg(5, "第一章内容")),
            chapterStartsMs = listOf(5_000),
        )
        assertEquals(listOf("开场", "第一章内容"), lines.map { it.text })
    }

    @Test
    fun `时间戳格式 超过一小时带小时`() {
        assertEquals("03:12", formatTimestamp(192_000, false))
        assertEquals("1:02:03", formatTimestamp(3_723_000, false))
        assertEquals("0:03:12", formatTimestamp(192_000, true))
    }
}
