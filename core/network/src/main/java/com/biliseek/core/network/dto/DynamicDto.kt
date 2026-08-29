package com.biliseek.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── 动态（M11-C）─────────────────────────────────────────

/**
 * `x/polymer/web-dynamic/v1/feed/all` / `feed/space` 的统一外壳。
 *
 * 分页不是页码而是 [offset]（服务端下发的偏移串）+ [hasMore]，
 * 下拉刷新 = offset 置空重拉第 1 页。
 */
@Serializable
data class DynamicFeedData(
    val items: List<DynamicItem> = emptyList(),
    val offset: String = "",
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** 一条动态。结构深度嵌套（modules/major），是 M11 解析最易翻车的一块。 */
@Serializable
data class DynamicItem(
    @SerialName("id_str") val idStr: String = "",
    val type: String = "",
    val modules: DynamicModules? = null,
    /** 仅转发（DYNAMIC_TYPE_FORWARD）时存在：内嵌的原动态，结构同 item。 */
    val orig: DynamicItem? = null,
)

@Serializable
data class DynamicModules(
    @SerialName("module_author") val author: DynamicAuthor? = null,
    @SerialName("module_dynamic") val dynamic: DynamicContent? = null,
)

@Serializable
data class DynamicAuthor(
    val mid: Long = 0,
    val name: String = "",
    val face: String = "",
    @SerialName("pub_ts") val pubTs: Long = 0,
)

@Serializable
data class DynamicContent(
    val desc: DynamicDesc? = null,
    val major: DynamicMajor? = null,
)

@Serializable
data class DynamicDesc(
    val text: String = "",
)

@Serializable
data class DynamicMajor(
    val type: String = "",
    val archive: DynamicArchive? = null,
    val draw: DynamicDraw? = null,
    val article: DynamicArticle? = null,
    val opus: DynamicOpus? = null,
)

/** 视频投稿（DYNAMIC_TYPE_AV）。注意时长是文本（"03:27"）而非秒。 */
@Serializable
data class DynamicArchive(
    val bvid: String = "",
    val title: String = "",
    val cover: String = "",
    @SerialName("duration_text") val durationText: String = "",
)

/** 图文动态（DYNAMIC_TYPE_DRAW）。 */
@Serializable
data class DynamicDraw(
    val items: List<DynamicDrawItem> = emptyList(),
)

@Serializable
data class DynamicDrawItem(
    val src: String = "",
    val width: Int = 0,
    val height: Int = 0,
)

/** 专栏动态（DYNAMIC_TYPE_ARTICLE）。 */
@Serializable
data class DynamicArticle(
    val id: Long = 0,
    val title: String = "",
    val covers: List<String> = emptyList(),
)

/** 图文（新版，DYNAMIC_TYPE_OPUS）。 */
@Serializable
data class DynamicOpus(
    val title: String = "",
    val summary: String = "",
    val pics: List<String> = emptyList(),
)

/** `x/relation/followings` —— 关注列表（UP 筛选 chips 的数据源）。 */
@Serializable
data class FollowingData(
    val list: List<FollowingUp> = emptyList(),
)

@Serializable
data class FollowingUp(
    val mid: Long = 0,
    val uname: String = "",
    val face: String = "",
)
