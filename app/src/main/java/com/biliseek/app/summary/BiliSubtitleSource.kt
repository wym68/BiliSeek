package com.biliseek.app.summary

import com.biliseek.core.ai.Chapter
import com.biliseek.core.ai.Transcript
import com.biliseek.core.ai.TranscriptSource
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.dto.SubtitleTrack

/** 字幕探查结果：选中的字幕与章节。只在用户点「AI 总结」时查一次（工单 AI-4）。 */
data class SubtitleProbe(
    val pick: SubtitlePick,
    val chapters: List<Chapter>,
    /** 查询本身失败（网络 / 风控冷却）。按无字幕处理，但不缓存，下次可再查。 */
    val failed: Boolean,
)

suspend fun probeSubtitles(repository: BiliRepository, bvid: String, cid: Long, durationMs: Long?): SubtitleProbe {
    val data = repository.playerSubtitles(bvid, cid).getOrElse {
        return SubtitleProbe(SubtitlePick.None, emptyList(), failed = true)
    }
    return SubtitleProbe(
        pick = SubtitlePickPolicy.pick(data.subtitle?.subtitles.orEmpty()),
        chapters = SubtitleMapping.chapters(data.viewPoints.orEmpty(), durationMs),
        failed = false,
    )
}

/** 用 B 站字幕作为原文（工单 AI-4）。 */
class BiliSubtitleSource(
    private val repository: BiliRepository,
    private val track: SubtitleTrack,
    private val chapters: List<Chapter>,
) : TranscriptSource {
    override suspend fun load(): Transcript {
        val body = repository.subtitleBody(track.subtitleUrl).getOrThrow()
        return Transcript(
            segments = SubtitleMapping.segments(body.body),
            chapters = chapters,
            language = track.lan.removePrefix("ai-").ifBlank { null },
            timing = Transcript.Timing.SEGMENT,
        )
    }
}
