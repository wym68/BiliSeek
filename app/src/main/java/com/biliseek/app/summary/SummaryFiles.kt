package com.biliseek.app.summary

import android.content.Context
import com.biliseek.core.ai.Transcript
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一条视频的总结在私有目录里的文件（工单 AI-5，多版本见 [SummaryVersionPolicy]）：
 * - 视频共用（所有版本的原料）：`filesDir/ai_summary/{bvid}_{cid}/` 下 `transcript.json`、
 *   `asr_partial.jsonl`（识别断点）、`audio.m4a`（为识别临时下载，识别完即删）；
 * - 每个版本自己的：`v{id}/summary.md` 与 `v{id}/checkpoint.json`（总结断点，由 `:core:ai` 写）。
 */
class SummaryFiles(root: File, bvid: String, cid: Long) {
    val dir: File = File(root, "${safe(bvid)}_$cid")
    val transcript: File get() = File(dir, "transcript.json")
    val asrPartial: File get() = File(dir, "asr_partial.jsonl")
    val audio: File get() = File(dir, "audio.m4a")

    /** 某个版本自己的目录（总结断点也写在这里）。 */
    fun versionDir(id: Long): File = File(dir, "v$id")

    fun summary(id: Long): File = File(versionDir(id), "summary.md")

    fun readSummary(id: Long): String? = summary(id).takeIf { it.exists() }?.readText()

    fun writeSummary(id: Long, markdown: String) = atomicWrite(summary(id), markdown)

    fun deleteVersion(id: Long) {
        versionDir(id).deleteRecursively()
    }

    fun readTranscript(): Transcript? = transcript.takeIf { it.exists() }?.let { f ->
        runCatching { json.decodeFromString(Transcript.serializer(), f.readText()) }.getOrNull()
    }

    fun writeTranscript(value: Transcript) =
        atomicWrite(transcript, json.encodeToString(Transcript.serializer(), value))

    fun deleteAll() {
        dir.deleteRecursively()
    }

    private fun atomicWrite(target: File, text: String) {
        val parent = target.parentFile ?: dir
        parent.mkdirs()
        val tmp = File(parent, target.name + ".tmp")
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
