package com.biliseek.app.summary

/** 点「AI 总结」之后去哪（工单 AI-6 `SummaryEntryPolicy`）。 */
enum class EntryDecision { OPEN_READER, OPEN_STYLE_SHEET }

object SummaryEntryPolicy {
    /**
     * 已有记录（完成 / 进行中 / 失败）→ 阅读页（失败时阅读页显示出路）；没有 → 风格弹窗。
     *
     * 与工单初稿的差异：未配置大模型时**也打开弹窗**而不是直接跳设置页——弹窗里仍可选
     * 「B 站 AI 总结」「原文笔记」，大模型相关选项置灰并给出「去设置」。
     */
    fun decide(hasRecord: Boolean): EntryDecision =
        if (hasRecord) EntryDecision.OPEN_READER else EntryDecision.OPEN_STYLE_SHEET
}

/** 时间戳链接（工单 AI-6 `TimestampLinkPolicy`）：`[mm:ss]` ↔ `biliseek-ts://{ms}`。 */
object TimestampLinkPolicy {
    const val SCHEME = "biliseek-ts://"

    /** `[03:12]` / `[1:02:03]`，且后面不是 `(`（已经是链接的不重复处理）。 */
    private val TIMESTAMP = Regex("""\[(\d{1,2}):(\d{2})(?::(\d{2}))?](?!\()""")

    /** 把正文里的时间戳改写成可点击的 Markdown 链接。 */
    fun linkify(markdown: String): String = TIMESTAMP.replace(markdown) { m ->
        val ms = toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        "[${m.value.removePrefix("[").removeSuffix("]")}]($SCHEME$ms)"
    }

    /** 链接地址 → 毫秒；不是时间戳链接返回 null。 */
    fun parse(uri: String): Long? =
        uri.takeIf { it.startsWith(SCHEME) }?.removePrefix(SCHEME)?.toLongOrNull()?.takeIf { it >= 0 }

    /** 正在播同一个视频（同一 P）→ 原地跳；否则从该处开始播放（呈现态照常默认只听）。 */
    fun shouldSeekInPlace(currentBvid: String, currentCid: Long, bvid: String, cid: Long): Boolean =
        currentBvid == bvid && currentCid == cid && bvid.isNotBlank()

    private fun toMs(a: String, b: String, c: String): Long =
        if (c.isEmpty()) {
            (a.toLong() * 60 + b.toLong()) * 1000
        } else {
            (a.toLong() * 3600 + b.toLong() * 60 + c.toLong()) * 1000
        }
}

object SummaryFileNamePolicy {
    /** 另存为的默认文件名：去掉文件系统不允许的字符，限制长度。 */
    fun fileName(title: String): String {
        val safe = title.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").trim().take(60)
        return safe.ifBlank { "AI 总结" } + ".md"
    }
}
