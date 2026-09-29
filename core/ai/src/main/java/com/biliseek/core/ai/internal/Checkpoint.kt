package com.biliseek.core.ai.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** 一块原文的总结结果，记下是哪个模型做的（换模型后沿用，工单 §3.7.4）。 */
@Serializable
internal data class PartialResult(val index: Int, val text: String, val presetId: String, val model: String)

/**
 * 总结的断点：已完成的分块总结 + 已完成的分层合并轮次。
 *
 * [signature] = hash(原文 + 风格 + 分块参数)，**不含模型**：换模型继续做时已完成的分块照用。
 */
@Serializable
internal data class CheckpointData(
    val signature: String,
    val partials: List<PartialResult> = emptyList(),
    /** 每一轮压缩合并的输出（按轮次顺序）。 */
    val mergeRounds: List<List<String>> = emptyList(),
    val mergeModels: List<String> = emptyList(),
)

internal class CheckpointStore(private val dir: File?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file: File? get() = dir?.let { File(it, FILE_NAME) }

    /** 读取与 [signature] 匹配的断点；不存在、损坏或签名不符都返回空断点。 */
    fun load(signature: String): CheckpointData {
        val f = file ?: return CheckpointData(signature)
        if (!f.exists()) return CheckpointData(signature)
        val data = runCatching { json.decodeFromString(CheckpointData.serializer(), f.readText()) }.getOrNull()
        return if (data?.signature == signature) data else CheckpointData(signature)
    }

    /** 先写临时文件再改名，进程在写一半时被杀也不会留下半个 JSON。 */
    fun save(data: CheckpointData) {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(CheckpointData.serializer(), data))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    fun clear() {
        file?.delete()
    }

    companion object {
        const val FILE_NAME = "checkpoint.json"
    }
}

internal fun sha256Hex(vararg parts: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    parts.forEach {
        md.update(it.toByteArray(Charsets.UTF_8))
        md.update(0)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
