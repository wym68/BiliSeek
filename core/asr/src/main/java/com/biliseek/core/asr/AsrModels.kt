package com.biliseek.core.asr

/** 一句识别结果。起止即 VAD 片段的起止（句子级时间戳），相对音频开头。 */
data class AsrSegment(val startMs: Long, val endMs: Long, val text: String, val lang: String?)

sealed interface AsrProgress {
    /** 已处理到音频的哪个位置；UI 显示「12:30 / 58:00」。至多每 500ms 或每完成一句发射一次。 */
    data class Running(val processedMs: Long, val totalMs: Long, val newSegments: List<AsrSegment>) : AsrProgress

    /** [segments] 含断点之前已识别的部分；[rtf] = 本次耗时 / 本次处理的音频时长，回填给预估。 */
    data class Done(val segments: List<AsrSegment>, val rtf: Float) : AsrProgress

    data class Failed(val error: AsrError) : AsrProgress
}

sealed interface AsrError {
    data object ModelNotReady : AsrError

    /** 音频格式不支持 / 文件损坏。 */
    data class DecodeFailed(val message: String) : AsrError

    /** 原生层异常（含原生库加载失败）。 */
    data class EngineFailed(val message: String) : AsrError

    data object OutOfMemory : AsrError
}

/** 一个离线识别模型（一组文件）。任何来源下载或导入的文件都必须过同一组 SHA-256。 */
data class AsrModelSpec(
    /** 稳定 id，用作目录名；换模型版本 = 换 id。 */
    val id: String,
    val displayName: String,
    /** 需要下载 / 导入的文件（VAD 模型随 APK 附带，不在此列）。 */
    val files: List<ModelFile>,
    /** 同一组文件的多个下载来源。 */
    val sources: List<ModelSource>,
    val license: String,
    val licenseUrl: String,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
}

data class ModelFile(val name: String, val bytes: Long, val sha256: String)

/** 文件 URL = [baseUrl] + 文件名。 */
data class ModelSource(val id: String, val displayName: String, val baseUrl: String)

sealed interface ModelState {
    data object NotInstalled : ModelState

    /** 只在整数百分比变化时发射。 */
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : ModelState

    data object Verifying : ModelState
    data object Ready : ModelState

    /** 校验失败 / 文件缺失 / 下载失败；[reason] 直接给用户看。 */
    data class Broken(val reason: String) : ModelState
}
