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
    /** feed/detail 为对象；opus/detail 为 MODULE_TYPE_* 数组（见 [DynamicModulesCompatSerializer]）。 */
    @Serializable(with = DynamicModulesCompatSerializer::class)
    val modules: DynamicModules? = null,
    /** 仅转发（DYNAMIC_TYPE_FORWARD）时存在：内嵌的原动态，结构同 item。 */
    val orig: DynamicItem? = null,
    /** 服务端权威下发的评论区归属，优先于按 type 猜测的 oid/type 分流规则。 */
    val basic: DynamicBasic? = null,
)

/** `items[n].basic` —— 服务端直接下发的评论区 oid/type，见评论区类型代码表。 */
@Serializable
data class DynamicBasic(
    @SerialName("comment_id_str") val commentIdStr: String = "",
    @SerialName("comment_type") val commentType: Int = 0,
    /** 资源 id；detail 拉取失败时按 rid 兜底（bilipai 四级降级第 4 级）。 */
    @SerialName("rid_str") val ridStr: String = "",
)

/** `x/polymer/web-dynamic/v1/detail` / `desktop/v1/detail` / `opus/detail` 的统一响应体。 */
@Serializable
data class DynamicDetailData(
    val item: DynamicItem? = null,
    /** 仅 opus/detail：专栏类动态的 `{id, type}` 回退信息（type=12 → cv 号）。 */
    val fallback: DynamicOpusFallback? = null,
)

/** `opus/detail` 的 fallback 字段。 */
@Serializable
data class DynamicOpusFallback(
    val id: Long = 0,
    val type: Int = 0,
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
    /** 富文本节点：表情图、@、话题都在这里（text 里只有占位文本）。 */
    @SerialName("rich_text_nodes") val richTextNodes: List<DynamicRichTextNode> = emptyList(),
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
    /** DRAW 的服务端 id（用于评论 oid/type=11）。缺失时回退到 id_str/type=17。 */
    val id: Long = 0,
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
    /** 动态标题；B 站无标题时为 null。 */
    val title: String? = null,
    /** 文字摘要。文档形态为对象 `{text, rich_text_nodes}`；兼容旧版裸字符串。 */
    @Serializable(with = OpusSummaryCompatSerializer::class)
    val summary: DynamicOpusSummary? = null,
    /** 图片 URL 列表。文档形态元素为对象 `{url,width,height,size}`；兼容旧版字符串数组。 */
    @Serializable(with = OpusPicsCompatSerializer::class)
    val pics: List<String> = emptyList(),
)

/** `major.opus.summary` —— 文档形态：`text` 为摘要全文，`rich_text_nodes` 为富文本节点。 */
@Serializable
data class DynamicOpusSummary(
    val text: String = "",
    @SerialName("rich_text_nodes") val richTextNodes: List<DynamicRichTextNode> = emptyList(),
)

/** `rich_text_nodes[]` —— 富文本节点。正文兜底拼接用 origText（原始文本，不含表情转义）。 */
@Serializable
data class DynamicRichTextNode(
    val type: String = "",
    val text: String = "",
    @SerialName("orig_text") val origText: String = "",
    /** 仅 `RICH_TEXT_NODE_TYPE_EMOJI`：表情图。 */
    val emoji: DynamicEmoji? = null,
    /** `RICH_TEXT_NODE_TYPE_AT`：被 @ 用户的 mid（字符串）。 */
    val rid: String = "",
)

/** `rich_text_nodes[].emoji`。 */
@Serializable
data class DynamicEmoji(
    val text: String = "",
    @SerialName("icon_url") val iconUrl: String = "",
    /** 1=小 2=大。 */
    val size: Int = 1,
)

/** `x/relation/followings` —— 关注列表（UP 筛选 chips 的数据源）。 */
@Serializable
data class FollowingData(
    val list: List<FollowingUp> = emptyList(),
)

/**
 * `x/relation` 单条关注状态。**字段语义需真机抓包确认**：
 * - `relation` 通常 1 = 已关注 / 0 = 未关注；但 B 站不同端点可能略有差异
 * - `attribute` 未明，按"非 1 即未关注"兜底
 */
@Serializable
data class RelationData(
    val mid: Long = 0,
    val attribute: Int = 0,
    val mtime: Long = 0,
    val tag: String? = null,
    val special: Int = 0,
) {
    /** 从 attribute 字段派生关注状态。 */
    val relationStatus: RelationStatus
        get() = when (attribute) {
            0 -> RelationStatus.NOT_FOLLOWING
            2, 6 -> RelationStatus.FOLLOWING
            else -> RelationStatus.UNKNOWN
        }
}

@Serializable
enum class RelationStatus { FOLLOWING, NOT_FOLLOWING, UNKNOWN }

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

/** `x/dynamic/feed/dyn/thumb` 请求体。该接口要求 JSON body，`csrf` 走 URL 查询参数。 */
@Serializable
data class DynThumbRequest(
    @SerialName("dyn_id_str") val dynIdStr: String,
    val up: Int,
)
