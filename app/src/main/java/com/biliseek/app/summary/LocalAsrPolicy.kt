package com.biliseek.app.summary

import com.biliseek.core.ai.Chapter
import com.biliseek.core.ai.Transcript
import com.biliseek.core.ai.TranscriptSegment
import com.biliseek.core.asr.AsrError
import com.biliseek.core.asr.AsrSegment
import kotlin.math.ceil

/** 本地识别前要不要提醒（工单 AI-8 `LocalAsrPolicy`）。 */
sealed interface AsrPrompt {
    data object NoPrompt : AsrPrompt

    /** 长视频：提醒大约要多久（分钟，含解码与 VAD 开销）。 */
    data class ConfirmLong(val estimatedMinutes: Int) : AsrPrompt
}

object LocalAsrPolicy {
    /** 超过 30 分钟提醒。 */
    const val LONG_VIDEO_MS = 30 * 60_000L

    /** 没有实测值时的 RTF：android-offline-transcribe 在 Galaxy S10 上实测 SenseVoice 约 0.06，取保守值。 */
    const val DEFAULT_RTF = 0.1f

    /** 解码与 VAD 开销，按识别耗时的 20% 估（实测后修正）。 */
    private const val OVERHEAD = 1.2f

    fun decide(durationMs: Long, lastMeasuredRtf: Float?): AsrPrompt =
        if (durationMs > LONG_VIDEO_MS) {
            AsrPrompt.ConfirmLong(estimateMinutes(durationMs, lastMeasuredRtf))
        } else {
            AsrPrompt.NoPrompt
        }

    /** 预估耗时（分钟，至少 1）。实测的 RTF 已包含解码与 VAD，不再加开销。 */
    fun estimateMinutes(durationMs: Long, lastMeasuredRtf: Float?): Int {
        val factor = lastMeasuredRtf?.takeIf { it > 0f } ?: (DEFAULT_RTF * OVERHEAD)
        return ceil(durationMs * factor / 60_000.0).toInt().coerceAtLeast(1)
    }

    /** 「已下载」里的文件：优先纯音频，其次视频（MP4 里有音轨）。空串视为没有。 */
    fun pickLocalAudio(audioFile: String, videoFile: String): String? =
        audioFile.takeIf { it.isNotBlank() } ?: videoFile.takeIf { it.isNotBlank() }

    /** 识别结果 → 原文。章节仍来自 B 站 view_points（取不到不影响）。 */
    fun toTranscript(segments: List<AsrSegment>, chapters: List<Chapter>): Transcript = Transcript(
        segments = segments.filter { it.text.isNotBlank() }.map { TranscriptSegment(it.startMs, it.endMs, it.text) },
        chapters = chapters,
        language = segments.mapNotNull { it.lang }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
        timing = Transcript.Timing.SEGMENT,
    )

    /** 识别失败 → 给用户看的一句话。 */
    fun describe(error: AsrError): String = when (error) {
        AsrError.ModelNotReady -> "离线识别模型未就绪，请在设置里下载或导入"
        is AsrError.DecodeFailed -> "音频无法解码：${error.message}"
        is AsrError.EngineFailed -> "离线识别出错：${error.message}"
        AsrError.OutOfMemory -> "内存不足，离线识别中止；可关闭其他应用后重试"
    }
}
