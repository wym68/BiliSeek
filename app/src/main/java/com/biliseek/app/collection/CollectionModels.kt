package com.biliseek.app.collection

/**
 * 打开一个合集内容页时的来源，决定用哪个接口取内容
 * （设计稿 docs/superpowers/specs/2026-10-26-collections-design.md）。
 */
sealed interface CollectionRef {
    /**
     * 「列表 → 合集」里我订阅的合集。有 [mid] 时走 `seasons_archives_list`（可倒序）；
     * 没有时退回 `x/space/fav/season/list`（要登录、不能排序）。
     */
    data class Subscribed(val seasonId: Long, val mid: Long = 0, val upName: String = "") : CollectionRef

    /** UP 空间里的合集：`seasons_archives_list`。接口条目不带 UP 主名，用 [upName] 补。 */
    data class Season(val mid: Long, val seasonId: Long, val upName: String) : CollectionRef

    /** UP 空间里的旧版系列：`x/series/archives`。 */
    data class Series(val mid: Long, val seriesId: Long, val upName: String) : CollectionRef
}

/** 合集内容页挂在哪：「列表」tab 里一层，或 UP 空间页上一层。各管各的，互不覆盖。 */
enum class CollectionSlot { LIBRARY, UP_SPACE }

/** UP 空间「合集」视图的一行（合集或系列）。 */
data class SpaceCollection(
    val ref: CollectionRef,
    val name: String,
    /** 原始封面地址，展示前 normalizeCoverUrl。 */
    val cover: String,
    /** 视频数。 */
    val total: Int,
) {
    val isSeries: Boolean get() = ref is CollectionRef.Series

    /** 列表 key：合集和系列的编号可能相同，带上类型。 */
    val key: String
        get() = when (ref) {
            is CollectionRef.Season -> "season:${ref.seasonId}"
            is CollectionRef.Series -> "series:${ref.seriesId}"
            is CollectionRef.Subscribed -> "subscribed:${ref.seasonId}"
        }
}

/** 播放页：当前视频所属的合集（出自视频详情的 `ugc_season`）。 */
data class VideoCollection(
    val seasonId: Long,
    val title: String,
    /** 合集封面（原始地址，展示前 normalizeCoverUrl）；合集没封面时是第一集的封面。入口卡片用。 */
    val cover: String = "",
    /** 至少一个小节，每个小节至少一集（空的在 [CollectionPolicy.fromView] 里去掉了）。 */
    val sections: List<CollectionSection>,
    /** 当前视频在 [episodes] 里的位置；找不到为 null。 */
    val currentIndex: Int?,
) {
    /** 各小节按顺序拼成的全部集数。 */
    val episodes: List<CollectionEpisode> = sections.flatMap { it.episodes }
}

data class CollectionSection(
    val title: String,
    val episodes: List<CollectionEpisode>,
)

data class CollectionEpisode(
    val bvid: String,
    val title: String,
    /** 原始封面地址（可能 `//` 开头），展示和起播前 normalizeCoverUrl。 */
    val cover: String,
    val durationSec: Long,
    val author: String,
    val authorMid: Long,
)

/** 合集弹层的一行：小节标题，或第 [Episode.index] 集（[Episode.index] 是在 [VideoCollection.episodes] 里的位置）。 */
sealed interface CollectionSheetRow {
    data class Header(val title: String) : CollectionSheetRow
    data class Episode(val index: Int, val episode: CollectionEpisode) : CollectionSheetRow
}
