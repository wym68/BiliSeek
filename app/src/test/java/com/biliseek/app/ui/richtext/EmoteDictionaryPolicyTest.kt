package com.biliseek.app.ui.richtext

import com.biliseek.app.emote.EmoteDictionaryPolicy
import com.biliseek.core.network.dto.Emote
import com.biliseek.core.network.dto.EmotePackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EmoteDictionaryPolicyTest {

    private val doge = Emote(text = "[doge]", url = "https://doge.png")
    private val kaomoji = Emote(text = "(=・ω・=)", url = "")

    @Test
    fun `面板转字典_跳过无图颜文字`() {
        val dict = EmoteDictionaryPolicy.fromPackages(
            listOf(EmotePackage(id = 1, emote = listOf(doge, kaomoji)), EmotePackage(id = 2, emote = null)),
        )
        assertEquals(mapOf("[doge]" to doge), dict)
    }

    @Test
    fun `合并不覆盖已有键`() {
        val other = doge.copy(url = "https://other.png")
        val merged = EmoteDictionaryPolicy.merge(mapOf("[doge]" to doge), mapOf("[doge]" to other))
        assertEquals("https://doge.png", merged.getValue("[doge]").url)
    }

    @Test
    fun `没有新增时返回原对象`() {
        val cur = mapOf("[doge]" to doge)
        assertSame(cur, EmoteDictionaryPolicy.merge(cur, mapOf("[doge]" to doge)))
    }

    @Test
    fun `合并有上限`() {
        val full = (0 until EmoteDictionaryPolicy.MAX_ENTRIES).associate { "[e$it]" to doge }
        assertSame(full, EmoteDictionaryPolicy.merge(full, mapOf("[new]" to doge)))
    }

    @Test
    fun `光标处插入`() {
        assertEquals("ab[doge]c" to 8, EmoteDictionaryPolicy.insert("abc", 2, 2, "[doge]"))
    }

    @Test
    fun `有选区时替换选区_越界夹紧`() {
        assertEquals("a[doge]" to 7, EmoteDictionaryPolicy.insert("abc", 3, 1, "[doge]"))
        assertEquals("abc[doge]" to 9, EmoteDictionaryPolicy.insert("abc", 99, 99, "[doge]"))
    }

    @Test
    fun `退格整体删除已知表情`() {
        assertEquals("a" to 1, EmoteDictionaryPolicy.backspace("a[doge]", 7, setOf("[doge]")))
    }

    @Test
    fun `退格遇未知方括号只删一个字符`() {
        assertEquals("a[xx" to 4, EmoteDictionaryPolicy.backspace("a[xx]", 5, setOf("[doge]")))
    }

    @Test
    fun `退格在开头不动_代理对整体删`() {
        assertEquals("abc" to 0, EmoteDictionaryPolicy.backspace("abc", 0, emptySet()))
        assertEquals("a" to 1, EmoteDictionaryPolicy.backspace("a😀", 3, emptySet()))
    }
}
