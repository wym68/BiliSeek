package com.biliseek.app.summary

import com.biliseek.core.ai.AiError

/** 失败类别（落库用）。与 [AiError] 一一对应，外加原文获取失败 [SOURCE]。 */
enum class FailureKind {
    OFFLINE, NETWORK, SERVER, RATE_LIMITED, QUOTA, CONFIG, CONTENT_FILTERED,
    CONTEXT_TOO_LONG, TRUNCATED, BAD_RESPONSE, STREAM_INTERRUPTED, CANCELLED,

    /** 取字幕 / 下载音频 / 本地识别失败。 */
    SOURCE,
    ;

    companion object {
        fun of(error: AiError): FailureKind = when (error) {
            AiError.Offline -> OFFLINE
            is AiError.Network -> NETWORK
            is AiError.Server -> SERVER
            is AiError.RateLimited -> RATE_LIMITED
            is AiError.QuotaExceeded -> QUOTA
            is AiError.Config -> CONFIG
            is AiError.ContentFiltered -> CONTENT_FILTERED
            AiError.ContextTooLong -> CONTEXT_TOO_LONG
            AiError.Truncated -> TRUNCATED
            is AiError.BadResponse -> BAD_RESPONSE
            AiError.StreamInterrupted -> STREAM_INTERRUPTED
            AiError.Cancelled -> CANCELLED
        }

        fun parse(name: String): FailureKind? = entries.firstOrNull { it.name == name }
    }
}

/** 失败后的出路（工单 §3.7.7）。 */
enum class FailureAction(val label: String) {
    RETRY("重试"),
    SWITCH_TO_BACKUP("改用备用模型"),
    USE_OFFICIAL("用 B 站总结"),
    TRANSCRIPT_NOTE("生成原文笔记"),
    OPEN_SETTINGS("去设置"),
}

object FailureActionPolicy {

    /**
     * 通知与阅读页共用。顺序即优先级：通知只取前两个。
     *
     * - 配置错误**不提供**切备用（应先修配置，避免掩盖问题）；
     * - 额度用尽不自动重试，但给「重试」按钮（额度恢复后手动点）；
     * - 原文已就绪时总能「生成原文笔记」（不调用大模型）。
     */
    fun actions(
        kind: FailureKind,
        hasTranscript: Boolean,
        hasBackup: Boolean,
        usingBackup: Boolean,
        officialAvailable: Boolean,
    ): List<FailureAction> {
        val canSwitch = hasBackup && !usingBackup
        val out = mutableListOf<FailureAction>()
        when (kind) {
            FailureKind.CONFIG -> out += FailureAction.OPEN_SETTINGS
            FailureKind.QUOTA, FailureKind.CONTENT_FILTERED -> {
                if (canSwitch) out += FailureAction.SWITCH_TO_BACKUP
                if (kind == FailureKind.QUOTA) out += FailureAction.RETRY
            }
            FailureKind.CONTEXT_TOO_LONG, FailureKind.TRUNCATED -> {
                if (canSwitch) out += FailureAction.SWITCH_TO_BACKUP
            }
            FailureKind.NETWORK, FailureKind.SERVER, FailureKind.OFFLINE, FailureKind.RATE_LIMITED,
            FailureKind.STREAM_INTERRUPTED, FailureKind.BAD_RESPONSE,
            -> {
                out += FailureAction.RETRY
                if (canSwitch && (kind == FailureKind.SERVER || kind == FailureKind.NETWORK)) {
                    out += FailureAction.SWITCH_TO_BACKUP
                }
            }
            FailureKind.SOURCE, FailureKind.CANCELLED -> out += FailureAction.RETRY
        }
        if (officialAvailable) out += FailureAction.USE_OFFICIAL
        if (hasTranscript) out += FailureAction.TRANSCRIPT_NOTE
        return out.distinct()
    }
}

/** 计量网络下是否先确认（工单 AI-5 `SummaryNetworkPolicy`）。 */
sealed interface NetworkDecision {
    data object Proceed : NetworkDecision
    data class ConfirmMetered(val estimatedBytes: Long) : NetworkDecision
}

object SummaryNetworkPolicy {

    /** 本地识别要下载的音频体积估算：按最低音质约 64 kbps。 */
    fun estimateAudioBytes(durationMs: Long, kbps: Int = 64): Long = durationMs / 1000 * kbps * 1000 / 8

    /** 只有「本地识别且需要下载音频」才可能要确认；字幕、已下载的视频不拦。 */
    fun decide(needsAudioDownload: Boolean, isMetered: Boolean, durationMs: Long): NetworkDecision =
        if (needsAudioDownload && isMetered) {
            NetworkDecision.ConfirmMetered(estimateAudioBytes(durationMs))
        } else {
            NetworkDecision.Proceed
        }

    /** 等网络最多多久，超时转失败（不无限挂着前台服务）。 */
    const val MAX_WAIT_NETWORK_MS = 30 * 60_000L
}
