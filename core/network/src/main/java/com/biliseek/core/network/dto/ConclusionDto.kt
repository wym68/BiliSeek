package com.biliseek.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── B 站官方 AI 总结（工单 AI-4b）───────────────────────────
// `x/web-interface/view/conclusion/get`，未公开接口。字段含义参考 BiliPai（GPL-3.0）
// `AiSummaryResponse` / `AiSummaryFetchPolicy`；全部可空 / 有默认，解析失败按「没有官方总结」处理。

@Serializable
data class ConclusionData(
    /** 0 = 有摘要，1 = 无摘要（看 [stid] 区分排队中 / 无语音），-1 = 不支持。 */
    val code: Int = 0,
    @SerialName("model_result") val modelResult: ConclusionModelResult? = null,
    /** `"0"` = 仍在排队生成；空 = 未识别到语音。 */
    val stid: String = "",
    val status: Int = 0,
)

@Serializable
data class ConclusionModelResult(
    /** 0 = 无，1 = 仅摘要，2 = 摘要 + 大纲。 */
    @SerialName("result_type") val resultType: Int = 0,
    val summary: String = "",
    val outline: List<ConclusionOutline> = emptyList(),
    /** 分段文字。内容形态待真机确认，本期只解析不使用。 */
    val subtitle: List<ConclusionSubtitle> = emptyList(),
)

/** 大纲一节。[timestamp] 单位秒。 */
@Serializable
data class ConclusionOutline(
    val title: String = "",
    val timestamp: Long = 0,
    @SerialName("part_outline") val partOutline: List<ConclusionPartOutline> = emptyList(),
)

@Serializable
data class ConclusionPartOutline(
    val timestamp: Long = 0,
    val content: String = "",
)

@Serializable
data class ConclusionSubtitle(
    val title: String = "",
    val timestamp: Long = 0,
    @SerialName("part_subtitle") val partSubtitle: List<ConclusionPartSubtitle> = emptyList(),
)

@Serializable
data class ConclusionPartSubtitle(
    val content: String = "",
    @SerialName("start_timestamp") val startTimestamp: Long = 0,
    @SerialName("end_timestamp") val endTimestamp: Long = 0,
)
