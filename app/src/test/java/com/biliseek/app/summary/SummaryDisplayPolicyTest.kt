package com.biliseek.app.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryDisplayPolicyTest {
    private val header = "# 标题\n\n> UP：木鱼水心 · 时长：50:29 · 链接：https://www.bilibili.com/video/BV1PW411B7FA · " +
        "生成于：2026-09-30 14:28 · 来源：B 站字幕 · 模型：MiniMax-M2.7\n\n## 序章\n\n正文"

    @Test
    fun metaBecomesOneItemPerLineWithShortLink() {
        val out = SummaryDisplayPolicy.forReader(header)
        val quote = out.lines().filter { it.startsWith("> ") }
        assertEquals(6, quote.size)
        assertEquals("> UP：木鱼水心  ", quote[0])
        assertTrue(quote[2].startsWith("> 链接：[BV1PW411B7FA](https://www.bilibili.com/video/BV1PW411B7FA)"))
        assertEquals("> 模型：MiniMax-M2.7", quote.last())
    }

    @Test
    fun bodyAndHeadingsAreUntouched() {
        val out = SummaryDisplayPolicy.forReader(header)
        assertTrue(out.startsWith("# 标题\n\n> UP："))
        assertTrue(out.endsWith("## 序章\n\n正文"))
    }

    @Test
    fun linkWithQueryUsesPathTail() {
        val md = "# T\n\n> 链接：https://www.bilibili.com/video/BV1xx?p=2 · 来源：字幕\n\n正文"
        assertTrue(SummaryDisplayPolicy.forReader(md).contains("[BV1xx](https://www.bilibili.com/video/BV1xx?p=2)"))
    }

    @Test
    fun withoutMetaQuoteOrHeadingReturnsInput() {
        val noMeta = "# T\n\n正文里的 > 引用 · 不动"
        assertEquals(noMeta, SummaryDisplayPolicy.forReader(noMeta))
        val noHeading = "> a · b\n\n正文"
        assertEquals(noHeading, SummaryDisplayPolicy.forReader(noHeading))
    }

    @Test
    fun ordinaryQuoteWithoutSeparatorIsKept() {
        val md = "# T\n\n> ⚠️ 输出可能不完整\n\n正文"
        assertEquals(md, SummaryDisplayPolicy.forReader(md))
    }

    @Test
    fun onlyTheFirstQuoteAfterHeadingIsRewritten() {
        val md = "# T\n\n> 甲：1 · 乙：2\n\n正文\n\n> 另一处 · 引用\n"
        val out = SummaryDisplayPolicy.forReader(md)
        assertTrue(out.contains("> 另一处 · 引用"))
        assertTrue(out.contains("> 甲：1  \n> 乙：2"))
    }
}
