package com.biliseek.core.ai

import com.biliseek.core.ai.internal.MarkdownPolicy
import com.biliseek.core.ai.internal.formatTimestamp

/**
 * 总结 Markdown 的统一格式（头部、时间戳写法）。
 *
 * 公开给 `:app`：B 站官方总结（AI-4b）等不经过 [Summarizer] 的来源也要产出同样格式的 md，
 * 阅读页的时间戳跳播才不用区分来源。
 */
object SummaryMarkdown {

    fun compose(
        title: String,
        meta: Map<String, String>,
        models: List<String>,
        body: String,
        truncated: Boolean = false,
    ): String = MarkdownPolicy.compose(title, meta, models, body, truncated)

    /** 时间戳归一为 `[mm:ss]` / `[h:mm:ss]`，超出 [durationMs] 的去掉。 */
    fun normalize(body: String, durationMs: Long?): String = MarkdownPolicy.normalize(body, durationMs)

    /** `mm:ss`；[useHours] 时 `h:mm:ss`。 */
    fun timestamp(ms: Long, useHours: Boolean): String = formatTimestamp(ms, useHours)
}
