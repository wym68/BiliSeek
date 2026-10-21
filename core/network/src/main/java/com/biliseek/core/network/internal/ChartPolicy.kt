package com.biliseek.core.network.internal

import com.biliseek.core.network.ChartIssue
import com.biliseek.core.network.ChartItem
import com.biliseek.core.network.ChartPage
import com.biliseek.core.network.dto.MusicListData
import com.biliseek.core.network.dto.MusicPeriod
import com.biliseek.core.network.dto.MusicSong
import com.biliseek.core.network.dto.PopularVideo
import com.biliseek.core.network.dto.PreciousData
import com.biliseek.core.network.dto.RankingData
import com.biliseek.core.network.dto.WeeklySeriesEntry
import com.biliseek.core.network.dto.WeeklySeriesOneData
import com.biliseek.core.network.normalizeCoverUrl

/** 每周必看的全部期数：期数大的（最新）在前，不依赖接口返回的顺序；跳过期数无效的，同一期只留一个。 */
internal fun resolveWeeklyIssues(entries: List<WeeklySeriesEntry>): List<ChartIssue> =
    entries.map { it.number }
        .filter { it > 0 }
        .distinct()
        .sortedDescending()
        .map { ChartIssue(id = it.toLong(), number = it) }

/**
 * 热歌榜的全部期（所有年份合并）：发布时间晚的（最新）在前，发布时间相同取期数大的；
 * 跳过 id 无效的，同一个 id 只留一个。
 */
internal fun resolveMusicIssues(byYear: Map<String, List<MusicPeriod>>): List<ChartIssue> =
    byYear.values.flatten()
        .filter { it.id > 0 }
        .distinctBy { it.id }
        .sortedWith(compareByDescending<MusicPeriod> { it.publishTime }.thenByDescending { it.period })
        .map { ChartIssue(id = it.id, number = it.period) }

/** 热歌榜里一首歌实际要播的视频。 */
internal data class SongVideo(
    val bvid: String,
    val coverUrl: String,
    /** 秒。MV 的时长接口不给，为 0，由播放器取到真实时长后回填。 */
    val durationSec: Long,
)

/**
 * 热歌榜每首歌取哪个视频：官方 MV 优先；没有 MV 用关联稿件；两者都没有返回 null（该歌曲不展示）。
 * 封面缺失时用另一个兜底。
 */
internal fun resolveSongVideo(song: MusicSong): SongVideo? = when {
    song.mvBvid.isNotBlank() -> SongVideo(
        bvid = song.mvBvid,
        coverUrl = normalizeCoverUrl(song.mvCover.ifBlank { song.creationCover }),
        durationSec = 0,
    )
    song.creationBvid.isNotBlank() -> SongVideo(
        bvid = song.creationBvid,
        coverUrl = normalizeCoverUrl(song.creationCover.ifBlank { song.mvCover }),
        durationSec = song.creationDuration,
    )
    else -> null
}

internal fun PopularVideo.toChartItem(): ChartItem? =
    if (bvid.isBlank()) {
        null
    } else {
        ChartItem(
            bvid = bvid,
            title = title,
            coverUrl = normalizeCoverUrl(pic),
            author = owner?.name.orEmpty(),
            durationSec = duration,
            playCount = stat?.view?.takeIf { it > 0 },
            rank = null,
            authorMid = owner?.mid ?: 0,
        )
    }

/** 每周必看：页头 =「期名 · 主题」（缺哪个就省哪个）。 */
internal fun WeeklySeriesOneData.toChartPage(): ChartPage = ChartPage(
    subtitle = listOf(config.name, config.subject).filter { it.isNotBlank() }.joinToString(" · "),
    items = list.mapNotNull { it.toChartItem() },
)

/** 入站必刷：页头 = 官方说明。 */
internal fun PreciousData.toChartPage(): ChartPage = ChartPage(
    subtitle = explain,
    items = list.mapNotNull { it.toChartItem() },
)

/** 热歌榜：页头 =「第 N 期」；按名次升序（没有名次的排最后），没有可播视频的歌曲跳过。 */
internal fun MusicListData.toChartPage(number: Int): ChartPage = ChartPage(
    subtitle = if (number > 0) "第 $number 期" else "",
    items = list
        .sortedBy { if (it.rank > 0) it.rank else Int.MAX_VALUE }
        .mapNotNull { song ->
            val video = resolveSongVideo(song) ?: return@mapNotNull null
            ChartItem(
                bvid = video.bvid,
                title = song.title,
                coverUrl = video.coverUrl,
                author = song.singer,
                durationSec = video.durationSec,
                playCount = null,
                rank = song.rank.takeIf { it > 0 },
            )
        },
)

/**
 * 把批量取到的视频卡片按请求顺序整理成条目：按卡片里的 `bvid` 对应（不依赖 map 键的写法），
 * 取不到、值为 null、或没有 `bvid`（已不可用）的跳过。
 */
internal fun resolveCardItems(bvids: List<String>, cards: Map<String, PopularVideo?>): List<ChartItem> {
    val byBvid = cards.values.filterNotNull().filter { it.bvid.isNotBlank() }.associateBy { it.bvid }
    return bvids.mapNotNull { byBvid[it]?.toChartItem() }
}

/** 全站排行榜：页头 = 官方说明；名次 = 在榜单里的位置（跳过的条目不让后面的名次前移）。 */
internal fun RankingData.toChartPage(): ChartPage = ChartPage(
    subtitle = note,
    items = list.mapIndexedNotNull { index, video -> video.toChartItem()?.copy(rank = index + 1) },
)
