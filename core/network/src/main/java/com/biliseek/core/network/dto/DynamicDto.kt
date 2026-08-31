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
    /** 互动数据：转发 / 评论 / 点赞。转发动态里是转发者自己的，与 orig 不同。 */
    @SerialName("module_stat") val stat: DynamicStatModule? = null,
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
    /**
     * 视频统计。[stat.play] 已是 B 站格式化后的字符串（"1.2万"），UI 直接展示，不要再加工。
     * 字段缺失时不报错——展示层据此判断是否渲染 stat 行。
     */
    val stat: DynamicArchiveStat? = null,
)

/** `modules.module_dynamic.major.archive.stat` —— 视频的播放量与弹幕数。 */
@Serializable
data class DynamicArchiveStat(
    val play: String = "0",
    val danmaku: String = "0",
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

/**
 * `modules.module_stat.{comment,forward,like}` —— 互动计数与状态。
 *
 * 三种类型共享同一形状：
 * - `count` 总是 int（B 站已带"万"格式化的字符串不在这里，在 archive.stat.play）
 * - `status`：转发为 0/1（被禁用），点赞为 0/1（已赞 / 未赞），评论无意义但 B 站也带
 *   **B 站部分端点返回 bool（false/true），必须用 [FlexibleBooleanSerializer] 吃下两种**
 * - `forbidden`：转发用，部分用户被禁转时为 true；其他一般缺省 false
 *
 * 字段默认 0 / false，**接口漏字段不崩**。
 */
@Serializable
data class DynamicStatItem(
    val count: Int = 0,
    @Serializable(with = FlexibleBooleanSerializer::class)
    val status: Boolean = false,
    val forbidden: Boolean = false,
)

/** `modules.module_stat` —— 一条动态的转发 / 评论 / 点赞。 */
@Serializable
data class DynamicStatModule(
    val comment: DynamicStatItem = DynamicStatItem(),
    val forward: DynamicStatItem = DynamicStatItem(),
    val like: DynamicStatItem = DynamicStatItem(),
)
