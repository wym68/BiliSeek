package com.biliseek.app.ui.richtext

import com.biliseek.core.network.dto.Emote
import com.biliseek.core.network.dto.EmoteMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextPolicyTest {

    private val doge = Emote(text = "[doge]", url = "https://i0.hdslb.com/doge.png")
    private val laugh = Emote(text = "[笑哭]", url = "https://i0.hdslb.com/laugh.png")
    private val big = Emote(text = "[大表情]", url = "https://i0.hdslb.com/big.png", meta = EmoteMeta(size = 2))

    private fun parse(
        text: String,
        emotes: Map<String, Emote> = emptyMap(),
        mentions: Map<String, Long> = emptyMap(),
        keywords: List<String> = emptyList(),
        topics: Boolean = false,
        fallback: Map<String, Emote> = emptyMap(),
    ) = RichTextPolicy.parse(RichTextSource(text, emotes, mentions, keywords, topics), fallback)

    @Test
    fun `纯文本原样一段`() {
        assertEquals(listOf(RichSegment.Plain("你好世界")), parse("你好世界"))
    }

    @Test
    fun `空文本返回空列表`() {
        assertEquals(emptyList<RichSegment>(), parse(""))
    }

    @Test
    fun `表情替换且前后文字保留`() {
        val result = parse("哈哈[doge]好", emotes = mapOf("[doge]" to doge))
        assertEquals(
            listOf(
                RichSegment.Plain("哈哈"),
                RichSegment.EmoteImage("[doge]", doge.url, large = false),
                RichSegment.Plain("好"),
            ),
            result,
        )
    }

    @Test
    fun `相邻与重复表情都能识别`() {
        val emotes = mapOf("[doge]" to doge, "[笑哭]" to laugh)
        val result = parse("[doge][笑哭][doge]", emotes = emotes)
        assertEquals(3, result.size)
        assertTrue(result.all { it is RichSegment.EmoteImage })
        assertEquals("[笑哭]", (result[1] as RichSegment.EmoteImage).key)
    }

    @Test
    fun `未知表情保留原文`() {
        assertEquals(listOf(RichSegment.Plain("[不存在]啊")), parse("[不存在]啊"))
    }

    @Test
    fun `本条缺表情时用进程级字典兜底`() {
        val result = parse("[doge]", fallback = mapOf("[doge]" to doge))
        assertEquals(listOf(RichSegment.EmoteImage("[doge]", doge.url, false)), result)
    }

    @Test
    fun `本条表情表优先于字典`() {
        val other = doge.copy(url = "https://other.png")
        val result = parse("[doge]", emotes = mapOf("[doge]" to other), fallback = mapOf("[doge]" to doge))
        assertEquals("https://other.png", (result.single() as RichSegment.EmoteImage).url)
    }

    @Test
    fun `未闭合或过长的方括号不吞后续文字`() {
        assertEquals(listOf(RichSegment.Plain("[没闭合 [doge")), parse("[没闭合 [doge", emotes = mapOf("[doge]" to doge)))
        val long = "[" + "字".repeat(40) + "]"
        assertEquals(listOf(RichSegment.Plain(long)), parse(long))
    }

    @Test
    fun `方括号里嵌表情_外层失败后内层仍识别`() {
        val result = parse("[a[doge]", emotes = mapOf("[doge]" to doge))
        assertEquals(
            listOf(RichSegment.Plain("[a"), RichSegment.EmoteImage("[doge]", doge.url, false)),
            result,
        )
    }

    @Test
    fun `at 最长匹配`() {
        val result = parse(
            "@小明同学 你好",
            mentions = mapOf("小明" to 1L, "小明同学" to 2L),
        )
        assertEquals(RichSegment.Mention("@小明同学", 2L), result.first())
        assertEquals(RichSegment.Plain(" 你好"), result[1])
    }

    @Test
    fun `没有 at 表时不猜`() {
        assertEquals(listOf(RichSegment.Plain("mail@example.com")), parse("mail@example.com"))
    }

    @Test
    fun `蓝字关键词`() {
        val result = parse("推荐看原神攻略", keywords = listOf("原神"))
        assertEquals(
            listOf(RichSegment.Plain("推荐看"), RichSegment.Keyword("原神"), RichSegment.Plain("攻略")),
            result,
        )
    }

    @Test
    fun `话题只在开关打开时识别`() {
        assertEquals(listOf(RichSegment.Plain("#日常#")), parse("#日常#"))
        assertEquals(listOf(RichSegment.Keyword("#日常#")), parse("#日常#", topics = true))
        // 含空白不算话题
        assertEquals(listOf(RichSegment.Plain("# 不是 #")), parse("# 不是 #", topics = true))
    }

    @Test
    fun `链接到空白或中文标点为止`() {
        val result = parse("看 https://b23.tv/abc。好")
        assertEquals(
            listOf(RichSegment.Plain("看 "), RichSegment.Link("https://b23.tv/abc"), RichSegment.Plain("。好")),
            result,
        )
    }

    @Test
    fun `只有协议头不算链接`() {
        assertEquals(listOf(RichSegment.Plain("https:// 空")), parse("https:// 空"))
    }

    @Test
    fun `大表情标记与单独大表情判定`() {
        val alone = parse(" [大表情] ", emotes = mapOf("[大表情]" to big))
        assertTrue(RichTextPolicy.isStandaloneLargeEmote(alone))
        val mixed = parse("哈[大表情]", emotes = mapOf("[大表情]" to big))
        assertFalse(RichTextPolicy.isStandaloneLargeEmote(mixed))
        val small = parse("[doge]", emotes = mapOf("[doge]" to doge))
        assertFalse(RichTextPolicy.isStandaloneLargeEmote(small))
    }
}
