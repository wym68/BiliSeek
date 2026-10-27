package com.biliseek.app.collection

import com.biliseek.core.network.SpaceCollectionsPage
import com.biliseek.core.network.dto.VideoViewInfo

/** 合集的纯函数：播放页的合集信息与弹层、UP 空间的合集列表。无副作用，配单测。 */
object CollectionPolicy {

    /**
     * 视频详情 → 播放页的合集。不属于合集、合集编号无效、一集都没有时为 null（不显示入口行）。
     * 各集的 UP 主取这个视频的作者（合集是 UP 主自己建的）。
     */
    fun fromView(view: VideoViewInfo): VideoCollection? {
        val season = view.ugcSeason ?: return null
        if (season.id <= 0) return null
        val author = view.owner?.name.orEmpty()
        val authorMid = view.owner?.mid ?: 0
        val sections = season.sections.map { section ->
            CollectionSection(
                title = section.title,
                episodes = section.episodes.filter { it.bvid.isNotBlank() }.map { ep ->
                    CollectionEpisode(
                        bvid = ep.bvid,
                        title = ep.title,
                        cover = ep.arc?.pic.orEmpty(),
                        durationSec = ep.arc?.duration ?: 0,
                        author = author,
                        authorMid = authorMid,
                    )
                },
            )
        }.filter { it.episodes.isNotEmpty() }
        if (sections.isEmpty()) return null
        val index = sections.flatMap { it.episodes }.indexOfFirst { it.bvid == view.bvid }
        return VideoCollection(
            seasonId = season.id,
            title = season.title,
            cover = season.cover.ifBlank { sections.first().episodes.first().cover },
            sections = sections,
            currentIndex = index.takeIf { it >= 0 },
        )
    }

    /** 入口行的进度：「第 3/24 集」；找不到当前集时「共 24 集」。 */
    fun progressLabel(collection: VideoCollection): String {
        val total = collection.episodes.size
        return collection.currentIndex?.let { "第 ${it + 1}/$total 集" } ?: "共 $total 集"
    }

    /** 弹层的行。只有一个小节时不显示小节标题（B 站默认小节名多是「正片」，没有信息量）。 */
    fun sheetRows(collection: VideoCollection): List<CollectionSheetRow> {
        val showHeaders = collection.sections.size > 1
        var index = 0
        return buildList {
            collection.sections.forEach { section ->
                if (showHeaders) add(CollectionSheetRow.Header(section.title))
                section.episodes.forEach { add(CollectionSheetRow.Episode(index++, it)) }
            }
        }
    }

    /** 弹层打开时滚到哪一行：当前这集所在的行；没有当前集就从头。 */
    fun currentRowIndex(rows: List<CollectionSheetRow>, currentIndex: Int?): Int {
        if (currentIndex == null) return 0
        return rows.indexOfFirst { it is CollectionSheetRow.Episode && it.index == currentIndex }.coerceAtLeast(0)
    }

    /** 点弹层里的第 [index] 集要不要起播：点的是正在播的那集就只关弹层，不重新播。 */
    fun shouldPlayEpisode(collection: VideoCollection, index: Int, playingBvid: String): Boolean {
        val bvid = collection.episodes.getOrNull(index)?.bvid ?: return false
        return bvid != playingBvid
    }

    /** UP 空间一页 → 「合集」视图的行：合集在前、系列在后。 */
    fun spaceCollections(page: SpaceCollectionsPage, mid: Long, upName: String): List<SpaceCollection> =
        page.seasons.map {
            SpaceCollection(CollectionRef.Season(mid, it.seasonId, upName), it.name, it.cover, it.total)
        } + page.series.map {
            SpaceCollection(CollectionRef.Series(mid, it.seriesId, upName), it.name, it.cover, it.total)
        }

    /** 「合集」视图每行的第二行：「合集 · 24 个视频」/「系列 · 24 个视频」。 */
    fun countLabel(item: SpaceCollection): String =
        "${if (item.isSeries) "系列" else "合集"} · ${item.total} 个视频"
}
