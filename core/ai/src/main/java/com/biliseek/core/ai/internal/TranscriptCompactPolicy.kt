package com.biliseek.core.ai.internal

import com.biliseek.core.ai.TranscriptSegment

/** 压缩后的一行原文：`[mm:ss] 文本`。 */
internal data class CompactLine(val startMs: Long, val endMs: Long, val text: String) {
    fun render(useHours: Boolean): String = "[${formatTimestamp(startMs, useHours)}] $text"
}

/** `[音乐]`、`♪♪`、`（笑）` 这类没有信息量的行。 */
private val NOISE = Regex("""^[\s\p{Punct}♪♫～~…·、，。！？]*$|^[\[(（【]\s*(音乐|掌声|笑声|笑|music|applause|laughter)\s*[\])）】]$""", RegexOption.IGNORE_CASE)

/**
 * 把逐句原文合并成约 [windowMs] 一行的 `[mm:ss] 文本`（借鉴 BibiGPT）。
 *
 * B 站字幕一句一条、时间戳重复，直接喂给模型浪费大量 token；按时间窗合并后跳转精度仍足够。
 * 章节边界处强制断行，保证 [ChunkPolicy] 能按章节切。
 */
internal fun compactTranscript(
    segments: List<TranscriptSegment>,
    chapterStartsMs: List<Long> = emptyList(),
    windowMs: Long = 25_000,
): List<CompactLine> {
    val boundaries = chapterStartsMs.sorted()
    val out = mutableListOf<CompactLine>()
    var start = -1L
    var end = 0L
    val text = StringBuilder()

    fun flush() {
        if (text.isNotBlank()) out += CompactLine(start, end, text.toString().trim())
        text.setLength(0)
        start = -1L
    }

    for (seg in segments.sortedBy { it.startMs }) {
        val t = seg.text.trim()
        if (t.isEmpty() || NOISE.matches(t)) continue
        val crossesChapter = start >= 0 && boundaries.any { it in (start + 1)..seg.startMs }
        if (start >= 0 && (seg.startMs - start >= windowMs || crossesChapter)) flush()
        if (start < 0) start = seg.startMs
        if (text.isNotEmpty()) text.append(if (t.first().isAsciiLetterOrDigit() && text.last().isAsciiLetterOrDigit()) " " else "")
        text.append(t)
        end = seg.endMs
    }
    flush()
    return out
}

private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/** `mm:ss`，[useHours] 时 `h:mm:ss`。 */
internal fun formatTimestamp(ms: Long, useHours: Boolean): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (useHours || h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
