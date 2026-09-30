package com.biliseek.core.ai.internal

import com.biliseek.core.ai.SummaryStyle
import com.biliseek.core.ai.Transcript
import com.biliseek.core.ai.VideoContext
import com.biliseek.core.ai.provider.ChatMessage

/**
 * 提示词 = 基础规则 + 风格片段 (+ 自定义要求) + 参考信息 + 原文（工单 AI-2 `PromptPolicy`）。
 *
 * 三套模板：分块总结（保留全部事实，借鉴 AudioNotes）、压缩合并（分层合并的中间轮）、最终合并（按风格组织）。
 * 只有一块原文时直接用「单块」模板一步出最终结果。
 */
internal object PromptPolicy {

    fun baseRules(timing: Transcript.Timing): String = buildString {
        appendLine("你是视频内容整理助手。根据带时间戳的视频原文，用中文写 Markdown 笔记。")
        appendLine("规则：")
        appendLine("1. 只依据原文，不编造原文没有的内容。")
        appendLine("2. 原文来自字幕或语音识别，可能有错别字、同音字，请结合上下文与参考信息改正。")
        appendLine("3. 省略广告、寒暄、口头禅和与主题无关的闲聊。")
        appendLine("4. 专有名词、术语、人名、产品名保留原文写法。")
        if (timing == Transcript.Timing.SEGMENT) {
            appendLine("5. 时间戳一律写成 [mm:ss]（超过一小时写 [h:mm:ss]），取自原文行首的时间，不要编造原文里没有的时间。")
        } else {
            appendLine("5. 原文的时间只精确到段落，时间戳只标在小节标题上，写成 [mm:ss]。")
        }
        appendLine("6. 标题用 ## 或 ###；不要把「1. **标题**」这种加粗编号写成有序列表。")
        append("7. 直接输出 Markdown 正文：不要用代码块包裹，不要开场白和结束语。")
    }

    fun styleFragment(style: SummaryStyle): String = when (style) {
        SummaryStyle.Brief ->
            "输出格式：\n## 一句话总结\n（一句话）\n## 核心要点\n- [mm:ss] 要点（3 到 7 条，每条一句话）"
        SummaryStyle.DetailedNotes ->
            "输出格式：详细笔记。按内容分节，每节标题写成「## [mm:ss] 小节标题」，节内用要点完整记录论点、例子、数据与结论；最后写「## 总结」。"
        SummaryStyle.TimestampOutline ->
            "输出格式：\n## 一句话总结\n（一句话）\n## 分段大纲\n每个大段写成「### [mm:ss] 段落标题」，其下用「- [mm:ss] 要点」列出关键内容。"
        is SummaryStyle.Custom ->
            "输出要求（用户自定义，优先遵守，但仍需遵守上面的规则 1、2、7）：\n${style.prompt.trim()}"
        SummaryStyle.TranscriptNote -> error("原文笔记不调用大模型")
    }

    private fun referenceBlock(title: String, context: VideoContext?): String = buildString {
        appendLine("【视频标题】$title")
        if (context != null && (context.description.isNotBlank() || context.tags.isNotEmpty())) {
            appendLine("【参考信息】仅用于纠正错别字和专有名词，不是视频内容，不要据此补充视频里没说的内容。")
            if (context.description.isNotBlank()) {
                appendLine("简介：${context.description.trim().take(DESCRIPTION_MAX_CHARS)}")
            }
            if (context.tags.isNotEmpty()) appendLine("分区/标签：${context.tags.joinToString("、")}")
        }
    }.trimEnd()

    /** 只有一块原文：一步出最终结果。 */
    fun single(title: String, context: VideoContext?, style: SummaryStyle, timing: Transcript.Timing, chunkText: String) =
        listOf(
            ChatMessage("system", baseRules(timing) + "\n\n" + styleFragment(style)),
            ChatMessage("user", referenceBlock(title, context) + "\n\n【视频原文】\n" + chunkText),
        )

    /** 长视频的第 [index]/[total] 块（1 基）：提炼事实，不做风格化。 */
    fun chunk(
        title: String,
        context: VideoContext?,
        timing: Transcript.Timing,
        chunk: Chunk,
        index: Int,
        total: Int,
    ) = listOf(
        ChatMessage(
            "system",
            baseRules(timing) + "\n\n" +
                "你在处理一个长视频的第 $index/$total 段原文。提炼这一段：完整保留所有事实、观点、数字、日期、专有名词和结论，" +
                "不省略、不补充；每条要点前保留时间戳 [mm:ss]。输出 Markdown 要点列表。",
        ),
        ChatMessage(
            "user",
            referenceBlock(title, context) +
                (chunk.chapterTitle?.let { "\n【本段所属章节】$it" } ?: "") +
                "\n\n【视频原文（第 $index/$total 段）】\n" + chunk.text,
        ),
    )

    /** 分层合并的中间轮：去重压缩，保留全部要素。 */
    fun condense(title: String, timing: Transcript.Timing, partials: List<String>) = listOf(
        ChatMessage(
            "system",
            baseRules(timing) + "\n\n" +
                "下面是同一视频相邻若干段的笔记（按时间顺序）。合并压缩为一份：去掉重复，完整保留事实、数字、日期、" +
                "专有名词、结论与时间戳，不补充新内容。输出 Markdown 要点列表。",
        ),
        ChatMessage("user", "【视频标题】$title\n\n" + joinPartials(partials)),
    )

    /** 最终合并：按所选风格组织。 */
    fun finalMerge(title: String, context: VideoContext?, style: SummaryStyle, timing: Transcript.Timing, partials: List<String>) =
        listOf(
            ChatMessage(
                "system",
                baseRules(timing) + "\n\n" +
                    "下面是同一视频各段的笔记（按时间顺序）。把它们整理成最终笔记：只合并去重，不发明新内容，保留时间戳。\n\n" +
                    styleFragment(style),
            ),
            ChatMessage("user", referenceBlock(title, context) + "\n\n" + joinPartials(partials)),
        )

    /** 最终输出被截断时的续写请求。 */
    fun continuation(previous: List<ChatMessage>, soFar: String) = previous + listOf(
        ChatMessage("assistant", soFar),
        ChatMessage("user", "你的输出被截断了。请从中断处继续，不要重复已经输出的内容。"),
    )

    fun joinPartials(partials: List<String>): String =
        partials.mapIndexed { i, p -> "【第 ${i + 1} 段笔记】\n${p.trim()}" }.joinToString("\n\n---\n\n")

    /** 除原文外的提示词开销上限的估算（字节），用于给原文分块留出预算。 */
    fun overheadBytes(title: String, context: VideoContext?, style: SummaryStyle, timing: Transcript.Timing): Int {
        val styleText = if (style is SummaryStyle.TranscriptNote) "" else styleFragment(style)
        return utf8Size(baseRules(timing)) + utf8Size(styleText) + utf8Size(referenceBlock(title, context)) + OVERHEAD_SLACK_BYTES
    }

    private const val DESCRIPTION_MAX_CHARS = 500

    /** 模板里固定说明文字 + JSON 包装的余量。 */
    private const val OVERHEAD_SLACK_BYTES = 1_024
}
