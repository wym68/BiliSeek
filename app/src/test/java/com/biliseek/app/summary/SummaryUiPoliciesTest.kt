package com.biliseek.app.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工单 AI-6：入口决策、时间戳链接、导出文件名。 */
class SummaryUiPoliciesTest {

    @Test
    fun `有记录开阅读页 没有开风格弹窗`() {
        assertEquals(EntryDecision.OPEN_READER, SummaryEntryPolicy.decide(hasRecord = true))
        assertEquals(EntryDecision.OPEN_STYLE_SHEET, SummaryEntryPolicy.decide(hasRecord = false))
    }

    @Test
    fun `时间戳改写成链接`() {
        assertEquals(
            "## [03:12](biliseek-ts://192000) 标题\n- [1:02:03](biliseek-ts://3723000) 要点",
            TimestampLinkPolicy.linkify("## [03:12] 标题\n- [1:02:03] 要点"),
        )
    }

    @Test
    fun `已是链接的与普通方括号不处理`() {
        val md = "[03:12](https://x) 与 [注释] 与 [3:1]"
        assertEquals(md, TimestampLinkPolicy.linkify(md))
    }

    @Test
    fun `解析链接`() {
        assertEquals(192_000L, TimestampLinkPolicy.parse("biliseek-ts://192000"))
        assertNull(TimestampLinkPolicy.parse("https://www.bilibili.com"))
        assertNull(TimestampLinkPolicy.parse("biliseek-ts://abc"))
        assertNull(TimestampLinkPolicy.parse("biliseek-ts://-5"))
    }

    @Test
    fun `同一视频同一 P 才原地跳`() {
        assertTrue(TimestampLinkPolicy.shouldSeekInPlace("BV1", 10, "BV1", 10))
        assertFalse(TimestampLinkPolicy.shouldSeekInPlace("BV1", 11, "BV1", 10))
        assertFalse(TimestampLinkPolicy.shouldSeekInPlace("BV2", 10, "BV1", 10))
        assertFalse(TimestampLinkPolicy.shouldSeekInPlace("", 0, "", 0))
    }

    @Test
    fun `导出文件名去掉非法字符`() {
        assertEquals("a b c.md", SummaryFileNamePolicy.fileName("a/b:c"))
        assertEquals("AI 总结.md", SummaryFileNamePolicy.fileName("  "))
        assertEquals(63, SummaryFileNamePolicy.fileName("字".repeat(100)).length)
    }
}
