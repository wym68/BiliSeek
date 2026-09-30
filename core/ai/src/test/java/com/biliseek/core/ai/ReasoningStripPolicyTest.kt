package com.biliseek.core.ai

import com.biliseek.core.ai.internal.ORPHAN_PREBUFFER_CHARS
import com.biliseek.core.ai.internal.stripReasoning
import com.biliseek.core.ai.provider.ReasoningFormat
import com.biliseek.core.ai.provider.ReasoningFormat.AUTO
import com.biliseek.core.ai.provider.ReasoningFormat.THINK_TAG_IN_CONTENT
import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningStripPolicyTest {

    private fun done(raw: String, format: ReasoningFormat = AUTO) = stripReasoning(raw, format, complete = true)
    private fun partial(raw: String, format: ReasoningFormat = AUTO) = stripReasoning(raw, format, complete = false)

    @Test
    fun `无标签原样输出`() {
        assertEquals("# 标题\n正文", done("# 标题\n正文"))
    }

    @Test
    fun `去掉成对思考标签 不区分大小写 可多段`() {
        assertEquals("A\n\nB", done("<think>想一想</think>A\n\n<THINK>再想</THINK>B"))
    }

    @Test
    fun `未闭合的思考 其后不输出`() {
        assertEquals("前文", done("前文<think>还没想完"))
        assertEquals("", partial("<think>思考中……"))
    }

    @Test
    fun `孤立的闭合标签 之前全部丢弃`() {
        assertEquals("正文", done("服务端吃掉了开头标签的思考</think>\n正文"))
    }

    @Test
    fun `孤立闭合标签出现在后面 也整段回收`() {
        val longThinking = "思".repeat(ORPHAN_PREBUFFER_CHARS + 50)
        // 流式开头憋够之后已经输出过思考内容，孤立 </think> 到来后回收
        assertEquals(longThinking, partial(longThinking))
        assertEquals("正文", partial("$longThinking</think>正文"))
    }

    @Test
    fun `流式开头憋一小段 等孤立闭合标签`() {
        assertEquals("", partial("短短的开头"))
        assertEquals("短短的开头", done("短短的开头"))
    }

    @Test
    fun `跨增量切开的标签前缀先不输出`() {
        val prefix = "x".repeat(ORPHAN_PREBUFFER_CHARS)
        assertEquals(prefix, partial("$prefix<thi"))
        assertEquals(prefix, partial("$prefix</thin"))
        assertEquals(prefix + "后文", partial("$prefix<think>内</think>后文"))
        // 普通的小于号不受影响
        assertEquals("${prefix}a < b", partial("${prefix}a < b"))
    }

    @Test
    fun `独立字段格式与无思考格式不处理标签`() {
        assertEquals("<think>x</think>y", done("<think>x</think>y", ReasoningFormat.SEPARATE_FIELD))
        assertEquals("<think>x</think>y", done("<think>x</think>y", ReasoningFormat.NONE))
        assertEquals("y", done("<think>x</think>y", THINK_TAG_IN_CONTENT))
    }
}
