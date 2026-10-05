package com.biliseek.app.ui

import com.biliseek.app.FavoriteTrack
import com.biliseek.app.data.PlaylistTrack
import com.biliseek.core.network.ChartItem
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.dto.SeriesArchive
import com.biliseek.core.network.dto.VideoViewInfo
import com.biliseek.core.network.dto.WatchLaterItem
import com.biliseek.core.player.formatDuration

/**
 * 视频条目三点菜单里的动作（设计稿：docs/superpowers/specs/2026-10-02-video-menu-on-lists-design.md）。
 *
 * [label] 就是菜单上的文字，与搜索结果的三点菜单一致；`ActionListSheet` 按文字对应图标，改文案要连同图标表一起改。
 */
enum class VideoMenuAction(val label: String) {
    AddToPlaylist("加入列表"),
    AppendToQueue("加入队列"),
    PlayNext("下一首播放"),
    ViewUp("查看UP主"),
    CopyLink("复制链接"),
    Download("下载"),
    Summarize("AI 总结"),
    /** 从当前列表移除：只有稍后再看、收藏夹内容页有（`ActionListSheet` 对「删除」开头的项自动标红配图标）。 */
    Remove("删除"),
}

/**
 * 菜单项，顺序同搜索结果。没有 UP 编号（≤ 0）就没法「查看UP主」，去掉这一项；
 * [removable]（宿主给了移除动作）时末尾多一项「删除」；[downloadable] 为 false（「已下载」页的条目）时没有「下载」。
 */
fun videoMenuActions(
    authorMid: Long,
    removable: Boolean = false,
    downloadable: Boolean = true,
): List<VideoMenuAction> =
    VideoMenuAction.entries.filter {
        when (it) {
            VideoMenuAction.ViewUp -> authorMid > 0
            VideoMenuAction.Download -> downloadable
            VideoMenuAction.Remove -> removable
            else -> true
        }
    }

// ── 条目 → 搜索结果模型 ──────────────────────────────
// 与 SpaceVideo / DynamicEntry 同一个做法：映射后动作全部复用 startSingleAction。
// 时长：SearchVideo 存的是展示文本，startSingleAction 再用 parseDurationText 还原成秒。

private fun durationText(sec: Long) = formatDuration(sec * 1000)

/** 榜单条目（每周必看 / 入站必刷 / 热歌榜 / 热门播客 / 活动页）。 */
fun ChartItem.toSearchVideo() = SearchVideo(
    bvid = bvid,
    title = title,
    author = author,
    mid = authorMid,
    duration = durationText(durationSec),
    pic = coverUrl,
)

/** 收藏夹内容条目。 */
fun FavoriteTrack.toSearchVideo() = SearchVideo(
    bvid = bvid,
    title = title,
    author = author,
    mid = authorMid,
    duration = durationText(durationSec),
    pic = coverUrl,
)

/** 稍后再看条目。 */
fun WatchLaterItem.toSearchVideo() = SearchVideo(
    bvid = bvid,
    title = title,
    author = owner?.name.orEmpty(),
    mid = owner?.mid ?: 0,
    duration = durationText(duration),
    pic = pic,
)

/** 本地列表条目。本地没存 UP 编号，菜单里没有「查看UP主」。 */
fun PlaylistTrack.toSearchVideo() = SearchVideo(
    bvid = track.bvid,
    title = track.title,
    author = track.author,
    mid = 0,
    duration = durationText(track.durationSec),
    pic = track.coverUrl,
)

/**
 * 视频详情：搜索框里输入 BV 号 / av 号 / 视频链接时，先拉详情映射成一条搜索结果再让用户点（不直接起播）。
 */
fun VideoViewInfo.toSearchVideo() = SearchVideo(
    bvid = bvid,
    aid = aid,
    title = title,
    author = owner?.name.orEmpty(),
    mid = owner?.mid ?: 0,
    duration = durationText(duration),
    pic = pic,
    play = stat?.view ?: 0,
    danmaku = stat?.danmaku ?: 0,
    pubdate = pubdate,
)

/** 已下载条目（「已下载」页三点菜单）。下载记录里没有 UP 编号，菜单里没有「查看UP主」。 */
fun com.biliseek.app.download.DownloadTask.toSearchVideo() = SearchVideo(
    bvid = bvid,
    title = title,
    author = author,
    mid = 0,
    duration = durationText(durationSec),
    pic = coverUrl,
)

/** 合集内容条目。 */
fun SeriesArchive.toSearchVideo() = SearchVideo(
    bvid = bvid,
    title = title,
    author = author,
    mid = authorMid,
    duration = durationText(duration),
    pic = cover,
)
