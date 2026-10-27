package com.biliseek.app.collection

import com.biliseek.app.data.PlayHistoryEntity
import com.biliseek.core.player.formatDuration

/** 一个视频最近一次的本地播放记录（`play_history`，多 P 取最近的那一条）。 */
data class EpisodeHistory(val positionMs: Long, val durationMs: Long, val playedAt: Long)

/** 一集的收听情况。 */
sealed interface EpisodeProgress {
    data object None : EpisodeProgress
    data class Partial(val positionMs: Long, val durationMs: Long) : EpisodeProgress
    data object Finished : EpisodeProgress
}

/**
 * 合集里「听到第几集」的纯函数（设计稿 docs/superpowers/specs/2026-10-27-collections-round3-design.md §1）。
 * 判定与续播（`:core:player` ResumePolicy）同口径：离结尾不到 15 秒算听完，不到 5 秒算没听。
 */
object EpisodeProgressPolicy {
    private const val MIN_MS = 5_000L
    private const val TAIL_MS = 15_000L

    /** 按 bvid 取最近一条记录。 */
    fun latestByBvid(rows: List<PlayHistoryEntity>): Map<String, EpisodeHistory> =
        rows.groupBy { it.bvid }.mapValues { (_, list) ->
            val latest = list.maxBy { it.playedAt }
            EpisodeHistory(latest.positionMs, latest.durationSec * 1000, latest.playedAt)
        }

    fun progressOf(h: EpisodeHistory?): EpisodeProgress = when {
        h == null -> EpisodeProgress.None
        h.durationMs > 0 && h.positionMs >= h.durationMs - TAIL_MS -> EpisodeProgress.Finished
        h.positionMs > MIN_MS -> EpisodeProgress.Partial(h.positionMs, h.durationMs)
        else -> EpisodeProgress.None
    }

    /** 行里的状态文字；没听过返回 null（照旧显示原来那一行）。[durationSec] 是列表给的时长。 */
    fun statusLine(p: EpisodeProgress, durationSec: Long): String? = when (p) {
        EpisodeProgress.Finished -> "✓ 已听完 · ${formatDuration(durationSec * 1000)}"
        is EpisodeProgress.Partial ->
            "听到 ${formatDuration(p.positionMs)} / ${formatDuration(if (p.durationMs > 0) p.durationMs else durationSec * 1000)}"
        EpisodeProgress.None -> null
    }

    /**
     * 「继续听」的目标在 [bvids] 里的下标：最近听过的那一集；它已听完就是下一集；
     * 最后一集已听完、或这些集都没有记录时为 null（不显示横条）。
     */
    fun resumeIndex(bvids: List<String>, history: Map<String, EpisodeHistory>): Int? {
        val latest = bvids.withIndex()
            .filter { history[it.value] != null }
            .maxByOrNull { history.getValue(it.value).playedAt } ?: return null
        return if (progressOf(history[latest.value]) == EpisodeProgress.Finished) {
            (latest.index + 1).takeIf { it < bvids.size }
        } else {
            latest.index
        }
    }

    /** 第几集：正序 = 下标 + 1；倒序 = 总数 − 下标；倒序且总数未知 = null。 */
    fun episodeNumber(index: Int, total: Int, reversed: Boolean): Int? = when {
        !reversed -> index + 1
        total > 0 -> total - index
        else -> null
    }

    fun resumeTitle(number: Int?, title: String): String =
        if (number != null) "继续听 · 第 $number 集 $title" else "继续听 · $title"

    /** 横条第二行：听到一半写进度，其他写「从头开始 · 时长」。 */
    fun resumeDetail(p: EpisodeProgress, durationSec: Long): String =
        if (p is EpisodeProgress.Partial) statusLine(p, durationSec)!! else "从头开始 · ${formatDuration(durationSec * 1000)}"
}
