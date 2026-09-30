package com.biliseek.core.ai

import com.biliseek.core.ai.internal.SseParser
import com.biliseek.core.ai.internal.parseSse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {

    @Test
    fun `空行派发事件 data 前缀的空格可有可无`() {
        assertEquals(listOf("a", "b"), parseSse("data: a\n\ndata:b\n\n"))
    }

    @Test
    fun `注释与心跳行忽略 其他字段忽略`() {
        assertEquals(listOf("x"), parseSse(": ping\nevent: message\nid: 1\ndata: x\n\n"))
    }

    @Test
    fun `同一事件多行 data 用换行拼接`() {
        assertEquals(listOf("line1\nline2"), parseSse("data: line1\ndata: line2\n\n"))
    }

    @Test
    fun `CRLF 行尾`() {
        assertEquals(listOf("a", "[DONE]"), parseSse("data: a\r\n\r\ndata: [DONE]\r\n\r\n"))
    }

    @Test
    fun `末尾没有空行也能取出最后一个事件`() {
        assertEquals(listOf("a", "tail"), parseSse("data: a\n\ndata: tail"))
    }

    @Test
    fun `连续空行不产生空事件`() {
        val parser = SseParser()
        assertNull(parser.feedLine(""))
        assertNull(parser.feedLine(""))
        assertNull(parser.finish())
    }
}
