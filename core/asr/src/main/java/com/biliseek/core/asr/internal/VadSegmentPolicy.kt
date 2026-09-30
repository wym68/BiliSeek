package com.biliseek.core.asr.internal

/**
 * Silero VAD 参数（工单 AI-7「识别流水线」）。合并间隔、切长、丢短都交给 sherpa-onnx 的 VAD 做：
 * - [minSilenceSec]：间隔短于它的两段语音会被合并成一段；
 * - [minSpeechSec]：短于它的语音被丢弃；
 * - [maxSpeechSec]：超过它时 VAD 会提高阈值尽快切开（不保证硬切，见 [VadSegmentPolicy.split]）。
 */
internal data class VadParams(
    val threshold: Float = 0.5f,
    val minSilenceSec: Float = 0.3f,
    val minSpeechSec: Float = 0.25f,
    val maxSpeechSec: Float = 20f,
    /** Silero 在 16 kHz 下的窗口，固定 512 样本。 */
    val windowSize: Int = 512,
    /** 兜底硬切：VAD 没切开、仍长于它的片段按等长切开（SenseVoice 单段过长会变慢、易漏字）。 */
    val hardMaxSec: Float = 30f,
)

internal object VadSegmentPolicy {
    val DEFAULT = VadParams()

    /** 样本序号 → 毫秒；[offsetMs] 是本次 VAD 的第 0 个样本在整段音频里的位置（断点续做时非 0）。 */
    fun toMs(sampleIndex: Long, sampleRate: Int, offsetMs: Long): Long =
        offsetMs + sampleIndex * 1000 / sampleRate

    /** 防御性再过滤一次：短于 [VadParams.minSpeechSec] 的丢掉。 */
    fun shouldKeep(sampleCount: Int, sampleRate: Int, params: VadParams = DEFAULT): Boolean =
        sampleCount >= (params.minSpeechSec * sampleRate).toInt()

    /**
     * 兜底切长：返回片段内的 [start, end) 子区间。不超过 [VadParams.hardMaxSec] 原样一段；
     * 否则按等长切成 ceil(n / max) 段，避免最后剩下一小截。
     */
    fun split(sampleCount: Int, sampleRate: Int, params: VadParams = DEFAULT): List<IntRange> {
        if (sampleCount <= 0) return emptyList()
        val max = (params.hardMaxSec * sampleRate).toInt().coerceAtLeast(1)
        if (sampleCount <= max) return listOf(0 until sampleCount)
        val parts = (sampleCount + max - 1) / max
        val size = (sampleCount + parts - 1) / parts
        return (0 until parts).map { p -> (p * size) until minOf(sampleCount, (p + 1) * size) }
    }
}
