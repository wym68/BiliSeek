package com.biliseek.app.comment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionInputPolicyTest {

    @Test
    fun `刚打出 @ 时是空关键词`() {
        assertEquals(ActiveMention(3, ""), resolveActiveMention("你好 @", 4))
    }

    @Test
    fun `@ 后面打的字是关键词，只算到光标为止`() {
        assertEquals(ActiveMention(0, "小明"), resolveActiveMention("@小明", 3))
        assertEquals(ActiveMention(0, "小"), resolveActiveMention("@小明", 2))
    }

    @Test
    fun `打完空格就结束，不再弹候选`() {
        assertNull(resolveActiveMention("@小明 ", 4))
        assertNull(resolveActiveMention("@小明 你好", 7))
    }

    @Test
    fun `没有 @、光标在开头或关键词太长时不弹`() {
        assertNull(resolveActiveMention("你好", 2))
        assertNull(resolveActiveMention("@小明", 0))
        assertNull(resolveActiveMention("@" + "字".repeat(MAX_MENTION_QUERY + 1), MAX_MENTION_QUERY + 2))
    }

    @Test
    fun `两个 @ 时看离光标最近的那个`() {
        assertEquals(ActiveMention(5, "小"), resolveActiveMention("@小明 你@小", 7))
    }

    @Test
    fun `选人后替换成 @昵称加空格，光标放在空格后`() {
        val text = "你好 @小 再见"
        val mention = ActiveMention(3, "小")
        val (out, cursor) = insertMention(text, mention, cursor = 5, name = "小明")
        assertEquals("你好 @小明  再见", out)
        assertEquals(7, cursor)
    }
}
