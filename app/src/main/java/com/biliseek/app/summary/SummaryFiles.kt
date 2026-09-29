package com.biliseek.app.summary

import android.content.Context
import com.biliseek.core.ai.Transcript
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一条总结在私有目录里的文件（工单 AI-5）：
 * `filesDir/ai_summary/{bvid}_{cid}/` 下 `summary.md`、`transcript.json`、`checkpoint.json`（总结断点，
 * 由 `:core:ai` 写）、`asr_partial.jsonl`（识别断点）、`audio.m4a`（为识别临时下载，识别完即删）。
 */
class SummaryFiles(root: File, bvid: String, cid: Long) {
    val dir: File = File(root, "${safe(bvid)}_$cid")
    val summary: File get() = File(dir, "summary.md")
    val transcript: File get() = File(dir, "transcript.json")
    val asrPartial: File get() = File(dir, "asr_partial.jsonl")
    val audio: File get() = File(dir, "audio.m4a")

    fun readSummary(): String? = summary.takeIf { it.exists() }?.readText()

    fun writeSummary(markdown: String) = atomicWrite(summary, markdown)

    fun readTranscript(): Transcript? = transcript.takeIf { it.exists() }?.let { f ->
        runCatching { json.decodeFromString(Transcript.serializer(), f.readText()) }.getOrNull()
    }

    fun writeTranscript(value: Transcript) =
        atomicWrite(transcript, json.encodeToString(Transcript.serializer(), value))

    fun deleteAll() {
        dir.deleteRecursively()
    }

    private fun atomicWrite(target: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun root(context: Context): File = File(context.filesDir, "ai_summary")

        /** bvid 只含字母数字；防御性地去掉路径分隔符。 */
        private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}
