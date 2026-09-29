package com.biliseek.core.ai

import com.biliseek.core.ai.provider.AiEndpointConfig
import kotlinx.serialization.Serializable
import java.io.File

// ── 原文 ──────────────────────────────────────────────────

/** 一句带时间戳的原文（字幕一行、本地识别的一个 VAD 片段）。 */
@Serializable
data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)

/** UP 主标注的章节（B 站 `view_points`）。 */
@Serializable
data class Chapter(val startMs: Long, val endMs: Long, val title: String)

/**
 * 要总结的原文。落盘为 `transcript.json`，重新生成时直接复用——不再打 B 站、不再识别。
 */
@Serializable
data class Transcript(
    val segments: List<TranscriptSegment>,
    val chapters: List<Chapter> = emptyList(),
    val language: String? = null,
    /** 时间戳精度。影响提示词里对时间戳的要求。 */
    val timing: Timing = Timing.SEGMENT,
) {
    enum class Timing {
        /** 逐句（字幕、本地识别）。 */
        SEGMENT,

        /** 仅段落级（以后云端转写降级时）。 */
        CHUNK,
    }
}

/** 内容来源。本模块只认这个接口，不知道 B 站。 */
fun interface TranscriptSource {
    suspend fun load(): Transcript
}

// ── 请求 ──────────────────────────────────────────────────

/** 视频自身的文字信息。只作参考（纠正同音错字），提示词明确要求不得据此补充视频内容。 */
data class VideoContext(
    val description: String,
    /** 本期为分区名；真正的视频标签需另一个接口，暂不请求。 */
    val tags: List<String>,
)

sealed interface SummaryStyle {
    data object Brief : SummaryStyle
    data object DetailedNotes : SummaryStyle
    data object TimestampOutline : SummaryStyle
    data class Custom(val prompt: String) : SummaryStyle

    /** 不调用大模型：原文按章节 / 5 分钟分段带时间戳（工单 §3.7.6）。 */
    data object TranscriptNote : SummaryStyle
}

data class SummaryRequest(
    val title: String,
    /** UP 主、时长、链接等，原样写进 md 头部。 */
    val meta: Map<String, String>,
    val style: SummaryStyle,
    val transcript: Transcript,
    val chat: AiEndpointConfig,
    val context: VideoContext? = null,
    /** null = 不做断点续做。 */
    val checkpointDir: File? = null,
)

// ── 结果与进度 ────────────────────────────────────────────

data class SummaryResult(
    val markdown: String,
    /** 实际参与生成的模型（换过备用模型时不止一个）；原文笔记为空。 */
    val models: List<String>,
    /** 最终输出在续写后仍被截断（md 末尾已标注）。 */
    val truncated: Boolean = false,
)

sealed interface SummaryProgress {
    data class Chunking(val total: Int) : SummaryProgress
    data class ChunkDone(val done: Int, val total: Int) : SummaryProgress

    /** 最后一步（或唯一一块）的流式正文，已去掉思考内容。 */
    data class Streaming(val markdownSoFar: String) : SummaryProgress
    data class Done(val result: SummaryResult) : SummaryProgress
    data class Failed(val error: AiError) : SummaryProgress
}

// ── 错误 ──────────────────────────────────────────────────

/** 由 AiErrorPolicy 从 HTTP / 响应体 / 网络异常映射而来，分类与处理见工单 §3.7.1。 */
sealed interface AiError {
    /** 系统无可用网络：不算失败，任务转「等待网络」。 */
    data object Offline : AiError
    data class Network(val message: String) : AiError
    data class Server(val code: Int) : AiError
    data class RateLimited(val retryAfterMs: Long?) : AiError
    data class QuotaExceeded(val vendorCode: Int?) : AiError
    data class Config(val kind: ConfigErrorKind) : AiError
    data class ContentFiltered(val vendorCode: Int?) : AiError
    data object ContextTooLong : AiError
    data object Truncated : AiError
    data class BadResponse(val message: String) : AiError
    data object StreamInterrupted : AiError
    data object Cancelled : AiError
}

enum class ConfigErrorKind {
    INVALID_KEY,
    WRONG_ENDPOINT,
    MODEL_NOT_FOUND,
    MODEL_NOT_IN_PLAN,

    /** MiniMax 国内版 / 国际版的 Key 与域名不匹配。 */
    REGION_MISMATCH,
}
