package com.biliseek.core.network.dto

import kotlinx.serialization.Serializable

// ── 分区列表（时光机式，`x/web-interface/newlist_rank`）──────────────
// 后台是搜索服务（返回 `rqt_type: "search"`）。字段按 10-21 真实返回整理，只取用得到的，全部带默认值。

/** `newlist_rank` 的 data。`result` 可能为 null（没有结果）。 */
@Serializable
data class NewlistRankData(
    val result: List<NewlistRankVideo>? = null,
    val page: Int = 0,
    val numPages: Int = 0,
    val numResults: Int = 0,
)

@Serializable
data class NewlistRankVideo(
    val bvid: String = "",
    val title: String = "",
    /** `//` 开头的封面地址。 */
    val pic: String = "",
    val author: String = "",
    val mid: Long = 0,
    /** 秒。 */
    val duration: Long = 0,
    /** 播放量，接口给的是**字符串**（如 "2134234"）。 */
    val play: String = "",
)
