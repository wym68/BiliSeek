package com.biliseek.core.ai.internal

/**
 * 模型输出 → 规整的 Markdown（工单 AI-2 `MarkdownPolicy`）。
 *
 * - 剥掉 ```` ```markdown ```` 外壳；
 * - 时间戳归一为 `[mm:ss]` / `[h:mm:ss]`：`(03:12)`、`【03:12】`、`（3:12）`、行首 `- 12.4 - ` 秒数写法；
 * - 超出视频时长的时间戳去掉括号，只留文字（模型编的时间点不能让用户点了乱跳）。
 */
internal object MarkdownPolicy {

    private val FENCE = Regex("""^\s*```[a-zA-Z]*\s*\n([\s\S]*?)\n?```\s*$""")
    private val BRACKETED = Regex("""[\[(（【]\s*(\d{1,2}):(\d{2})(?::(\d{2}))?\s*[\])）】]""")
    private val SECONDS_BULLET = Regex("""(?m)^(\s*[-*]\s*)(\d+(?:\.\d+)?)\s+-\s+""")

    fun normalize(raw: String, durationMs: Long?): String {
        var text = raw.trim()
        FENCE.matchEntire(text)?.let { text = it.groupValues[1].trim() }
        val useHours = (durationMs ?: 0) >= 3_600_000

        text = SECONDS_BULLET.replace(text) { m ->
            val ms = (m.groupValues[2].toDouble() * 1000).toLong()
            m.groupValues[1] + "[${formatTimestamp(ms, useHours)}] "
        }
        text = BRACKETED.replace(text) { m ->
            val (a, b, c) = m.destructured
            val ms = if (c.isEmpty()) {
                (a.toLong() * 60 + b.toLong()) * 1000
            } else {
                (a.toLong() * 3600 + b.toLong() * 60 + c.toLong()) * 1000
            }
            if (durationMs != null && ms > durationMs + TOLERANCE_MS) {
                ""
            } else {
                "[${formatTimestamp(ms, useHours)}]"
            }
        }
        // 去掉时间戳后可能留下的「-  文本」双空格
        return text.replace(Regex("""(?m)^(\s*[-*]) {2,}"""), "$1 ").trim()
    }

    /**
     * 拼 md 头部：`# 标题`、元信息引用行（调用方给的顺序 + 模型 + 来源），正文，截断提示。
     */
    fun compose(
        title: String,
        meta: Map<String, String>,
        models: List<String>,
        body: String,
        truncated: Boolean,
    ): String = buildString {
        appendLine("# ${title.trim()}")
        appendLine()
        val items = meta.filterValues { it.isNotBlank() }.map { (k, v) -> "$k：$v" }.toMutableList()
        if (models.isNotEmpty()) items += "模型：${models.joinToString("、")}"
        if (items.isNotEmpty()) {
            appendLine("> ${items.joinToString(" · ")}")
            appendLine()
        }
        append(body.trim())
        appendLine()
        if (truncated) {
            appendLine()
            appendLine("> ⚠️ 输出可能不完整：模型在续写后仍被截断。")
        }
    }

    private const val TOLERANCE_MS = 5_000L
}
