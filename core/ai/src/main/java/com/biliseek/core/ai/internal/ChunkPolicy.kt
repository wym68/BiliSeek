package com.biliseek.core.ai.internal

import com.biliseek.core.ai.Chapter

/** 一块要单独总结的原文。 */
internal data class Chunk(
    val startMs: Long,
    val endMs: Long,
    val chapterTitle: String?,
    val text: String,
)

private const val SENTENCE_ENDINGS = "。！？；.!?;"

internal fun utf8Size(s: String): Int = s.toByteArray(Charsets.UTF_8).size

/**
 * 把压缩后的原文切成若干块，每块渲染后 ≤ [maxBytes]（UTF-8 字节，调用方已扣除提示词开销）。
 *
 * - 有章节 → 先按章节切，超长章节再按体积切（借鉴 bilibili-subtitle）；
 * - 无章节 → 按体积贪心装箱；
 * - 单行超限 → [splitOversized]：只在窗口后半段找句末断开，找不到就按上限硬切（借鉴 AudioNotes）。
 */
internal fun chunkTranscript(
    lines: List<CompactLine>,
    chapters: List<Chapter>,
    maxBytes: Int,
    useHours: Boolean,
): List<Chunk> {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val groups: List<Pair<String?, List<CompactLine>>> =
        if (chapters.isEmpty()) {
            listOf(null to lines)
        } else {
            val sorted = chapters.sortedBy { it.startMs }
            val before = lines.filter { it.startMs < sorted.first().startMs }
            val head = if (before.isEmpty()) emptyList() else listOf<Pair<String?, List<CompactLine>>>(null to before)
            head + sorted.mapIndexed { i, ch ->
                val next = sorted.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
                ch.title to lines.filter { it.startMs >= ch.startMs && it.startMs < next }
            }.filter { it.second.isNotEmpty() }
        }

    val chunks = mutableListOf<Chunk>()
    for ((title, groupLines) in groups) {
        var current = mutableListOf<String>()
        var currentBytes = 0
        var startMs = -1L
        var endMs = 0L

        fun emit() {
            if (current.isEmpty()) return
            chunks += Chunk(startMs, endMs, title, current.joinToString("\n"))
            current = mutableListOf()
            currentBytes = 0
            startMs = -1L
        }

        for (line in groupLines) {
            // 超长行切开后每一片都带上同一个时间戳，模型才知道这段话在哪
            val prefix = "[${formatTimestamp(line.startMs, useHours)}] "
            val budget = (maxBytes - utf8Size(prefix)).coerceAtLeast(1)
            for (piece in splitOversized(line.text, budget).map { prefix + it }) {
                val pieceBytes = utf8Size(piece)
                if (current.isNotEmpty() && currentBytes + 1 + pieceBytes > maxBytes) emit()
                if (startMs < 0) startMs = line.startMs
                currentBytes += if (current.isEmpty()) pieceBytes else pieceBytes + 1
                current += piece
                endMs = line.endMs
            }
        }
        emit()
    }
    return chunks
}

/**
 * 把超过 [maxBytes] 的一行切开。优先在窗口后半段的句末标点处断开（避免切出大量过短的块）；
 * 识别结果可能完全没有标点，此时按字节上限硬切兜底。
 */
internal fun splitOversized(text: String, maxBytes: Int): List<String> {
    if (utf8Size(text) <= maxBytes) return listOf(text)
    val parts = mutableListOf<String>()
    var rest = text
    while (utf8Size(rest) > maxBytes) {
        val limit = prefixLengthWithin(rest, maxBytes).coerceAtLeast(1)
        val searchFrom = limit / 2
        var cut = -1
        for (i in limit - 1 downTo searchFrom) {
            if (rest[i] in SENTENCE_ENDINGS) { cut = i + 1; break }
        }
        if (cut <= 0) cut = limit
        parts += rest.substring(0, cut).trim()
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) parts += rest
    return parts.filter { it.isNotEmpty() }
}

/** [text] 的最长前缀字符数，使其 UTF-8 字节数 ≤ [maxBytes]（不拆代理对）。 */
private fun prefixLengthWithin(text: String, maxBytes: Int): Int {
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val len = when {
            cp < 0x80 -> 1
            cp < 0x800 -> 2
            cp < 0x10000 -> 3
            else -> 4
        }
        if (bytes + len > maxBytes) break
        bytes += len
        i += Character.charCount(cp)
    }
    return i
}

/**
 * 分层合并的下一轮分组（工单 AI-2 `MergePlanPolicy`，借鉴 AudioNotes）。
 *
 * @return null = 全部 [partials] 已能一次最终合并；否则为本轮「压缩合并」的分组（按顺序，每组 ≥1 个下标）。
 */
internal fun planMerge(partials: List<String>, maxBytes: Int): List<List<Int>>? {
    val sizes = partials.map { utf8Size(it) + SEPARATOR_BYTES }
    if (sizes.sum() <= maxBytes) return null
    val groups = mutableListOf<MutableList<Int>>()
    var current = mutableListOf<Int>()
    var bytes = 0
    sizes.forEachIndexed { i, size ->
        if (current.isNotEmpty() && bytes + size > maxBytes) {
            groups += current
            current = mutableListOf()
            bytes = 0
        }
        current += i
        bytes += size
    }
    if (current.isNotEmpty()) groups += current
    // 每组都只有一个（单个分片就超限）时再分组也没有进展：交给调用方按上下文超长处理
    return groups
}

/** 分片之间的分隔（`\n\n---\n\n`）开销。 */
internal const val SEPARATOR_BYTES = 7
