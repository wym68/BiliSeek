package com.biliseek.app.summary

import com.biliseek.core.ai.SummaryMarkdown
import com.biliseek.core.network.ConclusionResult
import com.biliseek.core.network.dto.ConclusionModelResult

/** 官方总结的查询状态（工单 AI-4b）。风格弹窗据此显示「使用 B 站 AI 总结（免费）」这一项。 */
sealed interface OfficialSummary {
    /** 尚未查询 / 查询中。 */
    data object Loading : OfficialSummary

    data class Available(val result: ConclusionModelResult) : OfficialSummary

    data class Unavailable(val reason: Reason) : OfficialSummary

    enum class Reason(val text: String) {
        NOT_LOGGED_IN("登录后可使用 B 站 AI 总结"),
        QUEUED("B 站总结生成中，可稍后再试，或直接自己生成"),
        NO_SPEECH("B 站未识别到可总结的语音"),
        NO_SUMMARY("这个视频没有 B 站 AI 总结"),
        UNSUPPORTED("这个视频不支持 B 站 AI 总结"),
        FAILED("B 站 AI 总结查询失败"),
    }
}

/** 状态表照 BiliPai `diagnoseAiSummaryResponse`；排队中**不轮询**、失败**不自动重试**。 */
object OfficialSummaryPolicy {

    /** 未登录直接跳过，**不发请求**。 */
    fun shouldQuery(isLoggedIn: Boolean): Boolean = isLoggedIn

    fun diagnose(result: Result<ConclusionResult>): OfficialSummary {
        val response = result.getOrElse { return OfficialSummary.Unavailable(OfficialSummary.Reason.FAILED) }
        if (response.rootCode == -101) return OfficialSummary.Unavailable(OfficialSummary.Reason.NOT_LOGGED_IN)
        if (response.rootCode != 0) return OfficialSummary.Unavailable(OfficialSummary.Reason.FAILED)
        val data = response.data ?: return OfficialSummary.Unavailable(OfficialSummary.Reason.NO_SUMMARY)
        val model = data.modelResult
        val hasContent = model != null && (model.summary.isNotBlank() || model.outline.isNotEmpty())
        val stid = data.stid.trim()
        return when {
            data.code == 0 && hasContent -> OfficialSummary.Available(model!!)
            data.code == 1 && stid == "0" -> OfficialSummary.Unavailable(OfficialSummary.Reason.QUEUED)
            data.code == 1 && stid.isEmpty() -> OfficialSummary.Unavailable(OfficialSummary.Reason.NO_SPEECH)
            data.code == -1 -> OfficialSummary.Unavailable(OfficialSummary.Reason.UNSUPPORTED)
            else -> OfficialSummary.Unavailable(OfficialSummary.Reason.NO_SUMMARY)
        }
    }
}

/** 官方结果 → 与自有流程一致的 md 正文（思路同 BiliPai `AiSummaryNoteDraftPolicy`）。 */
object OfficialSummaryMarkdownPolicy {

    fun body(result: ConclusionModelResult, durationMs: Long?): String {
        val useHours = (durationMs ?: 0) >= 3_600_000
        fun ts(seconds: Long) = "[${SummaryMarkdown.timestamp(seconds * 1000, useHours)}]"
        return buildString {
            if (result.summary.isNotBlank()) {
                append("## 一句话总结\n\n")
                append(result.summary.trim())
                append("\n")
            }
            if (result.outline.isNotEmpty()) {
                append("\n## 分段大纲\n")
                result.outline.forEach { outline ->
                    append("\n### ${ts(outline.timestamp)} ${outline.title.trim()}\n\n")
                    outline.partOutline.filter { it.content.isNotBlank() }.forEach { part ->
                        append("- ${ts(part.timestamp)} ${part.content.trim()}\n")
                    }
                }
            }
        }.trim()
    }

    /** 完整 md：头部与自有流程一致，来源标注「B 站官方 AI 总结」。 */
    fun markdown(title: String, meta: Map<String, String>, result: ConclusionModelResult, durationMs: Long?): String =
        SummaryMarkdown.compose(
            title = title,
            meta = meta + ("来源" to "B 站官方 AI 总结"),
            models = emptyList(),
            body = SummaryMarkdown.normalize(body(result, durationMs), durationMs),
        )
}
