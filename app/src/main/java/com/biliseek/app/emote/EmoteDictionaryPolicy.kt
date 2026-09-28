package com.biliseek.app.emote

import com.biliseek.core.network.dto.Emote
import com.biliseek.core.network.dto.EmotePackage

/**
 * 表情字典的纯函数：面板 → 字典、字典合并、输入框插入。
 */
object EmoteDictionaryPolicy {

    /** 字典上限：防止长时间刷评论把见过的表情无限堆积。 */
    const val MAX_ENTRIES = 2_000

    /** 表情包 → 字典。颜文字等无图表情（url 为空）不进字典——它们本身就是文字。 */
    fun fromPackages(packages: List<EmotePackage>): Map<String, Emote> =
        packages.asSequence()
            .flatMap { it.emote.orEmpty().asSequence() }
            .filter { it.text.isNotBlank() && it.url.isNotBlank() }
            .associateBy { it.text }

    /** 合并：[current] 已有的键不被 [incoming] 覆盖；超过上限时丢弃新来的。 */
    fun merge(current: Map<String, Emote>, incoming: Map<String, Emote>): Map<String, Emote> {
        if (current.size >= MAX_ENTRIES) return current
        val additions = incoming.filterKeys { it !in current && it.isNotBlank() }
            .filterValues { it.url.isNotBlank() }
        if (additions.isEmpty()) return current
        val room = MAX_ENTRIES - current.size
        return current + additions.entries.take(room).associate { it.key to it.value }
    }

    /** 面板里是否是纯文本表情（颜文字）：插入正文而不是插入占位串。 */
    fun isTextOnly(emote: Emote): Boolean = emote.url.isBlank()

    /**
     * 在光标处插入表情，返回新文本与新光标位置。
     * [selectionStart]/[selectionEnd] 越界时夹到合法范围；有选区时替换选区。
     */
    fun insert(text: String, selectionStart: Int, selectionEnd: Int, token: String): Pair<String, Int> {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val newText = text.substring(0, start) + token + text.substring(end)
        return newText to (start + token.length)
    }

    /**
     * 退格删除：光标前紧挨着一个完整的 `[xxx]` 且它在 [known] 里时整体删掉，否则删一个字符。
     * 返回新文本与新光标位置。
     */
    fun backspace(text: String, cursor: Int, known: Set<String>): Pair<String, Int> {
        val c = cursor.coerceIn(0, text.length)
        if (c == 0) return text to 0
        if (text[c - 1] == ']') {
            val open = text.lastIndexOf('[', c - 1)
            if (open >= 0) {
                val token = text.substring(open, c)
                if (token in known) return (text.substring(0, open) + text.substring(c)) to open
            }
        }
        // 代理对（emoji 等）整体删除
        val step = if (c >= 2 && Character.isSurrogatePair(text[c - 2], text[c - 1])) 2 else 1
        return (text.substring(0, c - step) + text.substring(c)) to (c - step)
    }
}
