package com.biliseek.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── 榜单（首页快捷入口）───────────────────────────────
// 字段按社区文档整理（bilibili-API-collect：popular.md / precious_videos.md / audio/rank.md），
// 只取用得到的。全部带默认值：缺字段不会让解析崩，只会少显示。

/** `x/web-interface/popular/series/list` —— 每周必看的全部期数。公开接口，不需要登录。 */
@Serializable
data class WeeklySeriesListData(
    val list: List<WeeklySeriesEntry> = emptyList(),
)

@Serializable
data class WeeklySeriesEntry(
    val number: Int = 0,
    val subject: String = "",
    val name: String = "",
)

/** `x/web-interface/popular/series/one` —— 每周必看某一期。 */
@Serializable
data class WeeklySeriesOneData(
    val config: WeeklySeriesConfig = WeeklySeriesConfig(),
    val list: List<PopularVideo> = emptyList(),
)

@Serializable
data class WeeklySeriesConfig(
    val number: Int = 0,
    val subject: String = "",
    /** 形如「2024第279期 07.19 - 07.25」。 */
    val name: String = "",
)

/** `x/web-interface/popular/precious` —— 入站必刷。 */
@Serializable
data class PreciousData(
    val title: String = "",
    /** 官方说明，如「我不允许还有人没看过这98个宝藏视频！」。 */
    val explain: String = "",
    val list: List<PopularVideo> = emptyList(),
)

/** 每周必看 / 入站必刷的视频条目（同视频详情的 data，只取要用的）。 */
@Serializable
data class PopularVideo(
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    /** 秒。 */
    val duration: Long = 0,
    val owner: PopularOwner? = null,
    val stat: PopularStat? = null,
)

@Serializable
data class PopularOwner(
    val name: String = "",
    /** UP 主编号；三点菜单的「查看UP主」要用。 */
    val mid: Long = 0,
)

@Serializable
data class PopularStat(
    val view: Long = 0,
)

/** `x/copyright-music-publicity/toplist/all_period` —— 音频榜单每期列表。 */
@Serializable
data class MusicPeriodsData(
    /** 年份 → 该年的各期，如 `{"2022": [{...}, {...}]}`。 */
    val list: Map<String, List<MusicPeriod>> = emptyMap(),
)

@Serializable
data class MusicPeriod(
    @SerialName("ID") val id: Long = 0,
    /** 期数。字段名按接口原样拼写（`priod`）。 */
    @SerialName("priod") val period: Int = 0,
    /** 秒时间戳。 */
    @SerialName("publish_time") val publishTime: Long = 0,
)

/** `x/copyright-music-publicity/toplist/music_list` —— 某一期榜单的歌曲。 */
@Serializable
data class MusicListData(
    val list: List<MusicSong> = emptyList(),
)

@Serializable
data class MusicSong(
    @SerialName("music_title") val title: String = "",
    val singer: String = "",
    /** 官方 MV；没有 MV 时为空串。 */
    @SerialName("mv_bvid") val mvBvid: String = "",
    @SerialName("mv_cover") val mvCover: String = "",
    /** 名次，1 为最高。 */
    val rank: Int = 0,
    /** 关联稿件（MV 缺失时的兜底）。 */
    @SerialName("creation_bvid") val creationBvid: String = "",
    @SerialName("creation_cover") val creationCover: String = "",
    /** 关联稿件时长，秒。 */
    @SerialName("creation_duration") val creationDuration: Long = 0,
)
