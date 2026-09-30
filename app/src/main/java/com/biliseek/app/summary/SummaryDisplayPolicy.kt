package com.biliseek.app.summary

/**
 * 阅读页显示前的 Markdown 整理（纯函数，只影响显示，不改磁盘上的 summary.md，导出 / 分享仍是原文）。
 *
 * 落盘的头部是 `# 标题` 加一行 `> UP：… · 时长：… · 链接：<很长的 URL> · 生成于：… · 来源：… · 模型：…`。
 * 手机上这一行会折成又窄又长的一段，链接还把版面撑乱，所以显示时改成一项一行，
 * 链接缩成 BV 号（仍可点开）。
 */
object SummaryDisplayPolicy {
    private val URL = Regex("""^https?://\S+$""")

    fun forReader(markdown: String): String {
        val lines = markdown.lines()
        val h1 = lines.indexOfFirst { it.startsWith("# ") }
        if (h1 < 0) return markdown
        // 元信息行：H1 之后（允许隔一个空行）第一条以 "> " 开头、且含 " · " 分隔的引用
        var idx = h1 + 1
        while (idx < lines.size && lines[idx].isBlank() && idx <= h1 + 2) idx++
        val line = lines.getOrNull(idx) ?: return markdown
        if (!line.startsWith("> ") || " · " !in line) return markdown

        val items = line.removePrefix("> ").split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        val block = items.joinToString("  \n") { "> ${beautify(it)}" }
        return (lines.take(idx) + block + lines.drop(idx + 1)).joinToString("\n")
    }

    /** `链接：https://www.bilibili.com/video/BV1xx` → `链接：[BV1xx](https://…)`。 */
    private fun beautify(item: String): String {
        val sep = item.indexOf('：')
        if (sep < 0) return item
        val key = item.substring(0, sep)
        val value = item.substring(sep + 1).trim()
        if (!URL.matches(value)) return item
        val label = value.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { value }
        return "$key：[$label]($value)"
    }
}
