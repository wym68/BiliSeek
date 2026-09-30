package com.biliseek.app.summary

import com.biliseek.core.ai.Chapter
import com.biliseek.core.ai.TranscriptSegment
import com.biliseek.core.network.dto.SubtitleLine
import com.biliseek.core.network.dto.SubtitleTrack
import com.biliseek.core.network.dto.ViewPoint

/** 选字幕的结果（工单 AI-4 `SubtitlePickPolicy`）。 */
sealed interface SubtitlePick {
    data class Use(val track: SubtitleTrack) : SubtitlePick

    /** 有字幕轨，但地址全是空的：多半是 AI 字幕需要登录。 */
    data object NeedsLogin : SubtitlePick

    data object None : SubtitlePick
}

/** 这次总结的原文从哪来（工单 AI-4 `SummarySourcePolicy`）。风格弹窗据此提示。 */
sealed interface SummarySource {
    data class UseSubtitle(val track: SubtitleTrack) : SummarySource

    /** 有字幕但未登录拿不到：弹窗给「去登录」「改用本地识别」两个按钮。 */
    data class NeedsLoginOrAsr(val asrReady: Boolean) : SummarySource

    /** 没有字幕，用本地识别。[hasLocalAudio] = 已下载过音频，不用再下载。 */
    data class UseLocalAsr(val hasLocalAudio: Boolean) : SummarySource

    /** 没有字幕，且离线识别模型还没下载。 */
    data object NeedsAsrModel : SummarySource
}

object SubtitlePickPolicy {

    /** 人工中文 > AI 中文 > 任意中文 > 首条；只在有地址的轨道里挑。 */
    fun pick(tracks: List<SubtitleTrack>): SubtitlePick {
        if (tracks.isEmpty()) return SubtitlePick.None
        val usable = tracks.filter { it.subtitleUrl.isNotBlank() }
        if (usable.isEmpty()) return SubtitlePick.NeedsLogin
        val chosen = usable.firstOrNull { it.isZh() && !it.isAi() }
            ?: usable.firstOrNull { it.isZh() && it.isAi() }
            ?: usable.firstOrNull { it.isZh() }
            ?: usable.first()
        return SubtitlePick.Use(chosen)
    }

    private fun SubtitleTrack.isZh(): Boolean {
        val l = lan.lowercase()
        return l.startsWith("zh") || l.startsWith("ai-zh")
    }

    private fun SubtitleTrack.isAi(): Boolean = aiType != 0 || lan.lowercase().startsWith("ai-")
}

object SummarySourcePolicy {

    /**
     * @param pick 字幕查询结果；查询本身失败（网络 / 风控冷却）时传 [SubtitlePick.None]，按无字幕处理。
     */
    fun resolve(pick: SubtitlePick, asrReady: Boolean, hasLocalAudio: Boolean): SummarySource = when (pick) {
        is SubtitlePick.Use -> SummarySource.UseSubtitle(pick.track)
        SubtitlePick.NeedsLogin -> SummarySource.NeedsLoginOrAsr(asrReady)
        SubtitlePick.None -> if (asrReady) SummarySource.UseLocalAsr(hasLocalAudio) else SummarySource.NeedsAsrModel
    }
}

/** B 站字幕 / 章节 → `:core:ai` 的原文结构（纯函数）。 */
object SubtitleMapping {

    fun segments(lines: List<SubtitleLine>): List<TranscriptSegment> = lines
        .filter { it.content.isNotBlank() }
        .map { TranscriptSegment((it.from * 1000).toLong(), (it.to * 1000).toLong(), it.content.trim()) }
        .sortedBy { it.startMs }

    /** `to` 缺失或不大于 `from` 时，用下一章的开始（最后一章用 [durationMs]）补齐。 */
    fun chapters(points: List<ViewPoint>, durationMs: Long?): List<Chapter> {
        val sorted = points.filter { it.content.isNotBlank() }.sortedBy { it.from }
        return sorted.mapIndexed { i, p ->
            val start = p.from * 1000
            val end = when {
                p.to > p.from -> p.to * 1000
                i + 1 < sorted.size -> sorted[i + 1].from * 1000
                else -> durationMs ?: start
            }
            Chapter(start, end, p.content.trim())
        }
    }
}
