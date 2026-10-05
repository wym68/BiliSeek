package com.biliseek.core.network

/** 榜单里的一条可播放视频（首页快捷入口的三个榜单共用）。 */
data class ChartItem(
    val bvid: String,
    val title: String,
    val coverUrl: String,
    /** UP 主（视频榜）或歌手（热歌榜）。 */
    val author: String,
    /** 秒；未知为 0（热歌榜播 MV 时）。 */
    val durationSec: Long,
    /** 播放量；没有该数据（热歌榜、或接口给 0）为 null。 */
    val playCount: Long?,
    /** 名次；只有热歌榜有，其余为 null。 */
    val rank: Int?,
    /** UP 主编号；没有（热歌榜的歌只有歌手）为 0。三点菜单据此决定要不要「查看UP主」。 */
    val authorMid: Long = 0,
)

/** 一份榜单：页头说明 + 条目。 */
data class ChartPage(
    val subtitle: String,
    val items: List<ChartItem>,
) {
    companion object {
        val EMPTY = ChartPage(subtitle = "", items = emptyList())
    }
}

/**
 * 榜单的一期（每周必看、热歌榜；热门播客的期数在 [EraGroup] 里）。
 * [id] 是取这一期内容用的编号：每周必看是期数（`number`），热歌榜是 `list_id`。
 */
data class ChartIssue(
    val id: Long,
    /** 期数，显示成「第 N 期」。接口没给（≤ 0）时 [label] 退成「往期」。 */
    val number: Int,
) {
    val label: String get() = if (number > 0) "第${number}期" else "往期"
}

/** 活动页里的一组视频（页面里的一个标签页，如「第63期」）。 */
data class EraGroup(
    val label: String,
    val bvids: List<String>,
)

/** 活动页内容：页面标题（HTML 的 `<title>`，没有时为空串）+ 按标签页分组的 BV 号。 */
data class EraPageContent(
    val title: String,
    val groups: List<EraGroup>,
)
