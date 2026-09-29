package com.biliseek.core.asr.internal

import com.biliseek.core.asr.AsrSegment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 已识别片段的增量落盘（工单 AI-7 `AsrCheckpoint`）：每识别完一句追加一行 JSON。
 *
 * 片段即断点：续做起点 = 最后一个完整片段的 endMs。VAD 片段是完整的句子，起点之后到下一句之间
 * 只有静音或未处理的音频，所以不需要回退重叠、也不需要去重。
 */
internal class AsrCheckpoint(private val file: File) {
    @Serializable
    private data class Line(
        @SerialName("s") val startMs: Long,
        @SerialName("e") val endMs: Long,
        @SerialName("t") val text: String,
        @SerialName("l") val lang: String? = null,
    )

    /**
     * 读出已完成的片段。进程被杀时最后一行可能只写了一半：无法解析的行一律丢弃，
     * 并把文件重写成干净的内容（否则下一行会接在残行后面）。
     */
    fun load(): List<AsrSegment> {
        if (!file.exists()) return emptyList()
        val lines = file.readLines()
        val segments = lines.mapNotNull { parse(it) }
        val clean = lines.isEmpty() || (segments.size == lines.size && file.endsWithNewline())
        if (!clean) rewrite(segments)
        return segments
    }

    fun append(segment: AsrSegment) {
        file.parentFile?.mkdirs()
        file.appendText(encode(segment) + "\n")
    }

    fun clear() {
        file.delete()
    }

    private fun rewrite(segments: List<AsrSegment>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(segments.joinToString("") { encode(it) + "\n" })
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun parse(line: String): AsrSegment? {
        if (line.isBlank()) return null
        return runCatching { json.decodeFromString(Line.serializer(), line) }.getOrNull()
            ?.takeIf { it.endMs >= it.startMs && it.startMs >= 0 }
            ?.let { AsrSegment(it.startMs, it.endMs, it.text, it.lang) }
    }

    private fun encode(s: AsrSegment) = json.encodeToString(Line.serializer(), Line(s.startMs, s.endMs, s.text, s.lang))

    private fun File.endsWithNewline(): Boolean {
        if (length() == 0L) return true
        return java.io.RandomAccessFile(this, "r").use { raf ->
            raf.seek(raf.length() - 1)
            raf.read() == '\n'.code
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 续做起点：最后一个完整片段的 endMs；没有则从 0 开始。 */
        fun resumeMs(segments: List<AsrSegment>): Long = segments.lastOrNull()?.endMs ?: 0L
    }
}
