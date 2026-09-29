package com.biliseek.core.ai.internal

import com.biliseek.core.ai.Transcript

/**
 * 原文笔记（工单 §3.7.6）：不调用大模型，把原文按章节（无章节则每 5 分钟）分节，每节带可点的时间戳。
 *
 * 用作大模型失败时的出口，也可在风格弹窗里主动选择；未配置大模型时同样可用。
 */
internal object TranscriptNotePolicy {

    fun render(transcript: Transcript, sectionMs: Long = 5 * 60_000L): String {
        val segments = transcript.segments.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        if (segments.isEmpty()) return "_（原文为空）_"
        val durationMs = segments.maxOf { it.endMs }
        val useHours = durationMs >= 3_600_000

        data class Section(val startMs: Long, val title: String?)
        val chapters = transcript.chapters.sortedBy { it.startMs }
        val sections: List<Section> = if (chapters.isNotEmpty()) {
            val head = if (segments.first().startMs < chapters.first().startMs) listOf(Section(0, null)) else emptyList()
            head + chapters.map { Section(it.startMs, it.title) }
        } else {
            (0..durationMs / sectionMs).map { Section(it * sectionMs, null) }
        }

        return buildString {
            append("> 原文笔记，未经 AI 整理。\n")
            sections.forEachIndexed { i, section ->
                val next = sections.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
                val inSection = segments.filter { it.startMs >= section.startMs && it.startMs < next }
                if (inSection.isEmpty()) return@forEachIndexed
                val ts = formatTimestamp(section.startMs, useHours)
                val heading = section.title ?: run {
                    val endTs = formatTimestamp(minOf(next, durationMs), useHours)
                    "$ts – $endTs"
                }
                append("\n## [$ts] $heading\n\n")
                inSection.forEach { seg ->
                    append("[${formatTimestamp(seg.startMs, useHours)}] ${seg.text.trim()}\n")
                    append("\n")
                }
            }
        }.trim()
    }
}
