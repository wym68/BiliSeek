package com.biliseek.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── AI 总结：字幕与章节（工单 AI-4）─────────────────────────
// 字段一律可空 / 有默认：未公开接口，结构变了也只落默认值，由上层按「无字幕」处理，不崩。

/** `x/player/wbi/v2` —— 播放器信息。这里只取字幕列表与 UP 主章节。 */
@Serializable
data class PlayerV2Data(
    val subtitle: SubtitleInfo? = null,
    @SerialName("view_points") val viewPoints: List<ViewPoint>? = null,
    @SerialName("need_login_subtitle") val needLoginSubtitle: Boolean = false,
)

@Serializable
data class SubtitleInfo(
    val subtitles: List<SubtitleTrack> = emptyList(),
)

/** 一条字幕轨。未登录时 AI 字幕的 [subtitleUrl] 常为空。 */
@Serializable
data class SubtitleTrack(
    val id: Long = 0,
    /** 语言码：`zh-CN`、`ai-zh`、`en-US`… */
    val lan: String = "",
    @SerialName("lan_doc") val lanDoc: String = "",
    @SerialName("subtitle_url") val subtitleUrl: String = "",
    /** 0 = 人工字幕，非 0 = AI 生成。 */
    @SerialName("ai_type") val aiType: Int = 0,
    val type: Int = 0,
)

/** UP 主标注的章节。[from] / [to] 单位秒。 */
@Serializable
data class ViewPoint(
    val from: Long = 0,
    val to: Long = 0,
    val content: String = "",
)

/** 字幕文件（`subtitle_url` 指向的 JSON）。 */
@Serializable
data class SubtitleBody(
    val body: List<SubtitleLine> = emptyList(),
)

/** 字幕一行。[from] / [to] 单位秒（小数）。 */
@Serializable
data class SubtitleLine(
    val from: Double = 0.0,
    val to: Double = 0.0,
    val content: String = "",
)
