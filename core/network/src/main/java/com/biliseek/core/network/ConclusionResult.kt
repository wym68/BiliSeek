package com.biliseek.core.network

import com.biliseek.core.network.dto.ConclusionData

/** B 站官方 AI 总结的原始结果：外层 code（-101 = 未登录等）+ data（工单 AI-4b）。 */
data class ConclusionResult(
    val rootCode: Int,
    val data: ConclusionData?,
)
