package com.biliseek.core.network

import android.util.Log
import com.biliseek.core.network.dto.DynamicItem
import com.biliseek.core.network.dto.DynamicStatModule

/** 动态类型筛选（feed/all 的 type 参数）。 */
object DynamicType {
    const val ALL = "all"
    const val VIDEO = "video"
}

/** 一条可展示的动态（UI 形状，无网络细节）。 */
data class DynamicEntry(
    val idStr: String,
    val authorName: String,
    val authorFace: String,
    val pubTs: Long,
    val descText: String,
    /** 原始类型：DYNAMIC_TYPE_AV / FORWARD / DRAW / ARTICLE / OPUS … */
    val rawType: String,
    /** 视频投稿（AV 或转发内嵌 AV）。null = 图文类，仅展示不可播。 */
    val video: DynamicVideo?,
    /** 封面或首图（normalize 后）。 */
    val coverUrl: String,
    /** OPUS 图文标题（无标题为空串）。转发取 orig 的标题。 */
    val opusTitle: String = "",
    /**
     * 多图：DRAW.items / OPUS.pics / ARTICLE.covers 全集（normalize 后）。
     * 视频投稿与单图文为空列表；转发里以原动态的多图为准（与封面同源）。
     */
    val pics: List<String> = emptyList(),
    /** DRAW 服务端 id（用于评论 oid/type=11）。非 DRAW 始终 0。 */
    val drawId: Long = 0,
    /** 服务端下发的评论区归属（`basic.comment_id_str`/`comment_type`）。缺失时为 0。 */
    val commentOid: Long = 0,
    val commentType: Int = 0,
    /** `basic.rid_str`：详情四级降级第 4 级的 rid 兜底。 */
    val ridStr: String = "",
    /**
     * 转发 / 评论 / 点赞。转发动态里是**转发者自己的**互动数，与 orig 不同——
     * 与 BiliPai 语义一致："这条转发有多少人转了"是用户的视角，不是原作者的。
     * 接口漏 module_stat 时为 null，UI 据此判断是否渲染底部互动栏。
     */
    val stat: DynamicStatModule? = null,
    /** UP 主 mid，用于头像/昵称点击跳转空间页；来自 `module_author.mid`。 */
    val authorMid: Long = 0,
    /**
     * 正文里出现的表情：`[doge]` → 图片。收自 desc / opus.summary 的 rich_text_nodes
     * （转发时含 orig 的），UI 按占位串替换成行内图片。
     */
    val emotes: Map<String, com.biliseek.core.network.dto.Emote> = emptyMap(),
    /**
     * 长图文正文段落（文字 / 图片，按原文顺序），展开后图文穿插显示。
     * 只有 opus/detail 拉到的详情才有；feed 卡片始终为空。
     */
    val body: List<com.biliseek.core.network.dto.DynamicBlock> = emptyList(),
    /**
     * 充电专属动态（没充电看不到正文）的锁定标题，如「××专属动态」；空串 = 不是锁定动态。
     * 来自 `major.blocked`，转发时取 orig 的。
     */
    val lockedTitle: String = "",
    /** 锁定动态的解锁提示，如「加入当前UP主的「30元档包月充电」即可解锁观看」。 */
    val lockedHint: String = "",
)

data class DynamicVideo(
    val bvid: String,
    val title: String,
    val coverUrl: String,
    /** 展示文本（"03:27"），非秒。 */
    val durationText: String,
    /** 视频播放量（"1.2万"）—— B 站已格式化，UI 直接展示 */
    val playText: String = "",
    /** 弹幕数（"324"） */
    val danmakuText: String = "",
    /** 充电专属视频（封面「充电」角标），见 [isChargeExclusiveArchive]。 */
    val chargeExclusive: Boolean = false,
    /** 正在限时免费的充电视频（封面「限免」角标），见 [isLimitedFreeArchive]。 */
    val limitedFree: Boolean = false,
)

/**
 * 动态里的视频是不是充电专属：角标文字含「充电」（普通视频是「投稿视频」，不能只看有没有角标），
 * 或 `is_charging_arc` / `elec_arc_type == 1`（对齐 bilipai `resolveDynamicArchiveBadgeLabel`）。
 */
internal fun isChargeExclusiveArchive(archive: com.biliseek.core.network.dto.DynamicArchive): Boolean =
    archive.badge?.text?.contains("充电") == true || archive.isChargingArc || archive.elecArcType == 1

/**
 * 动态里的视频是不是正在限时免费的充电视频：角标文字是「限时免费」
 * （2026-10-05 真机日志：BV1jjHp6jEk7；文字里没有「充电」，所以不算充电专属）。
 */
internal fun isLimitedFreeArchive(archive: com.biliseek.core.network.dto.DynamicArchive): Boolean =
    archive.badge?.text?.let { "限时免费" in it || "限免" in it } == true

/** 动态类型常量（接口返回的 type 枚举值）。 */
object DynamicItemType {
    const val AV = "DYNAMIC_TYPE_AV"
    const val FORWARD = "DYNAMIC_TYPE_FORWARD"
    const val DRAW = "DYNAMIC_TYPE_DRAW"
    const val ARTICLE = "DYNAMIC_TYPE_ARTICLE"
    const val OPUS = "DYNAMIC_TYPE_OPUS"
    /** 旧版纯文字动态（无 major），只有 desc.text。 */
    const val WORD = "DYNAMIC_TYPE_WORD"
}

/**
 * OPUS 正文抽取：`summary.text` 优先；为空时拼接 rich_text_nodes
 * （origText 是原始文本，不含表情图片转义，比 text 更适合纯文本展示）。
 */
private fun opusBodyText(opus: com.biliseek.core.network.dto.DynamicOpus?): String {
    if (opus == null) return ""
    opus.summary?.text?.takeIf { it.isNotBlank() }?.let { return it }
    return opus.summary?.richTextNodes.orEmpty()
        .joinToString("") { node -> node.origText.ifBlank { node.text } }
}

/** 从一条动态（及其 orig）的全部富文本节点里收集表情。 */
internal fun collectDynamicEmotes(item: DynamicItem?): Map<String, com.biliseek.core.network.dto.Emote> {
    if (item == null) return emptyMap()
    val dynamic = item.modules?.dynamic
    val nodes = dynamic?.desc?.richTextNodes.orEmpty() +
        dynamic?.major?.opus?.summary?.richTextNodes.orEmpty()
    val own = nodes.mapNotNull { node ->
        val emoji = node.emoji ?: return@mapNotNull null
        val key = emoji.text.ifBlank { node.text }
        if (key.isBlank() || emoji.iconUrl.isBlank()) return@mapNotNull null
        key to com.biliseek.core.network.dto.Emote(
            text = key,
            url = normalizeCoverUrl(emoji.iconUrl),
            meta = com.biliseek.core.network.dto.EmoteMeta(size = emoji.size),
        )
    }.toMap()
    return collectDynamicEmotes(item.orig) + own
}

/**
 * 把接口返回的 items 解析为展示条目（M11-C）。
 *
 * 规则：
 * - AV：直接取 major.archive 作视频
 * - FORWARD：取 orig 内嵌的 AV（转发者文案 + 原视频）；orig 是图文则仅展示，
 *   转发者与 orig 都有文案时按 `转发者文案\n\n//@原作者 orig文案` 追加
 * - DRAW / ARTICLE / OPUS：图文，仅展示首图，video = null
 * - WORD：旧版纯文字动态，仅展示 desc.text（空文案丢弃）
 * - 其他类型（live / pgc / ugc_season / music …）：丢弃
 * - 脏数据（无 idStr / 无 author / 无法识别）丢弃
 *
 * 「全部」视图展示图文但不可播；「仅视频」由服务端 type=video 过滤，
 * 但转发视频动态仍可能出现——解析层不额外过滤，统一按 [video] 是否为空判定可播性。
 */
fun parseDynamicItems(items: List<DynamicItem>): List<DynamicEntry> =
    items.mapNotNull { parseDynamicItem(it) }

fun parseDynamicItem(item: DynamicItem): DynamicEntry? {
    val author = item.modules?.author ?: return null
    if (item.idStr.isBlank()) return null
    val major = item.modules?.dynamic?.major
    // desc 来源优先级：dynamic.desc.text > opus.summary(rich_text_nodes 兜底) > article.title
    // ——OPUS 经常正文空、把全文写在 summary；summary.text 为空时退到 rich_text_nodes 拼接。
    val descText = listOf(
        item.modules?.dynamic?.desc?.text.orEmpty(),
        opusBodyText(major?.opus),
        major?.article?.title.orEmpty(),
    ).firstOrNull { it.isNotBlank() } ?: ""
    /** 转发者自己的互动数据。转发动态里不要混 orig 的数据（M11-C 决策）。 */
    val stat = item.modules?.stat

    val base = DynamicEntry(
        idStr = item.idStr,
        authorName = author.name,
        authorFace = normalizeCoverUrl(author.face),
        authorMid = author.mid,
        pubTs = author.pubTs,
        descText = descText,
        rawType = item.type,
        video = null,
        coverUrl = "",
        commentOid = item.basic?.commentIdStr?.toLongOrNull() ?: 0L,
        commentType = item.basic?.commentType ?: 0,
        ridStr = item.basic?.ridStr.orEmpty(),
        stat = stat,
        emotes = collectDynamicEmotes(item),
        body = major?.opus?.blocks.orEmpty(),
        lockedTitle = major?.blocked?.title.orEmpty(),
        lockedHint = major?.blocked?.hintMessage.orEmpty(),
    )

    // 多图抽取：去空 src、normalizeCoverUrl
    fun normalizeUrls(urls: List<String>): List<String> =
        urls.mapNotNull { if (it.isBlank()) null else normalizeCoverUrl(it) }

    return when (item.type) {
        DynamicItemType.AV -> {
            val archive = major?.archive ?: return null
            val cover = normalizeCoverUrl(archive.cover)
            base.copy(
                video = if (archive.bvid.isNotBlank()) {
                    DynamicVideo(
                        bvid = archive.bvid,
                        title = stripHighlight(archive.title),
                        coverUrl = cover,
                        durationText = archive.durationText,
                        playText = archive.stat?.play.orEmpty(),
                        danmakuText = archive.stat?.danmaku.orEmpty(),
                        chargeExclusive = isChargeExclusiveArchive(archive),
                        limitedFree = isLimitedFreeArchive(archive),
                    )
                } else null,
                coverUrl = cover,
            )
        }

        DynamicItemType.FORWARD -> {
            val orig = item.orig
            val origMajor = orig?.modules?.dynamic?.major
            val origArchive = origMajor?.archive
            // 转发里多图取自 orig：按 major 里实际非空的子字段判断（而非 orig.type）——
            // B 站新版图文动态顶层 type 也标 DYNAMIC_TYPE_DRAW，但内容在 major.opus。
            val origPics = when {
                origMajor?.opus != null -> normalizeUrls(origMajor.opus.pics)
                origMajor?.draw != null -> normalizeUrls(origMajor.draw.items.map { it.src })
                orig?.type == DynamicItemType.ARTICLE -> normalizeUrls(origMajor?.article?.covers.orEmpty())
                else -> emptyList()
            }
            // orig 自己的文案：转发者没写文案时用 orig 兜底；两边都有则追加展示。
            // 原视频标题不进 desc（已在下方视频行渲染），只取图文类文案。
            val origDescText = listOf(
                orig?.modules?.dynamic?.desc?.text.orEmpty(),
                opusBodyText(origMajor?.opus),
            ).firstOrNull { it.isNotBlank() } ?: ""
            val forwarderText = item.modules?.dynamic?.desc?.text.orEmpty()
            val mergedDesc = when {
                forwarderText.isBlank() -> origDescText
                origDescText.isBlank() -> forwarderText
                else -> "$forwarderText\n\n//@${orig?.modules?.author?.name.orEmpty()} $origDescText"
            }
            base.copy(
                descText = mergedDesc,
                opusTitle = origMajor?.opus?.title.orEmpty(),
                video = if (origArchive != null && origArchive.bvid.isNotBlank()) {
                    DynamicVideo(
                        bvid = origArchive.bvid,
                        title = stripHighlight(origArchive.title),
                        coverUrl = normalizeCoverUrl(origArchive.cover),
                        durationText = origArchive.durationText,
                        // 转发内嵌视频的统计仍取原视频的（B 站埋点）
                        playText = origArchive.stat?.play.orEmpty(),
                        danmakuText = origArchive.stat?.danmaku.orEmpty(),
                        chargeExclusive = isChargeExclusiveArchive(origArchive),
                        limitedFree = isLimitedFreeArchive(origArchive),
                    )
                } else null,
                coverUrl = when {
                    origArchive != null -> normalizeCoverUrl(origArchive.cover)
                    else -> origPics.firstOrNull().orEmpty()
                },
                pics = origPics,
                lockedTitle = origMajor?.blocked?.title.orEmpty(),
                lockedHint = origMajor?.blocked?.hintMessage.orEmpty(),
            )
        }

        DynamicItemType.DRAW -> {
            // B 站新版图文动态顶层 type 仍是 DYNAMIC_TYPE_DRAW，但内容搬到了 major.opus
            // （major.draw 为 null）——按 major 里实际非空的子字段判断，而非 item.type。
            val opus = major?.opus
            if (opus != null) {
                val pics = normalizeUrls(opus.pics)
                base.copy(
                    coverUrl = pics.firstOrNull().orEmpty(),
                    pics = pics,
                    opusTitle = opus.title.orEmpty(),
                )
            } else {
                val pics = normalizeUrls(major?.draw?.items?.map { it.src }.orEmpty())
                base.copy(
                    coverUrl = pics.firstOrNull().orEmpty(),
                    pics = pics,
                    drawId = major?.draw?.id ?: 0L,
                )
            }
        }

        DynamicItemType.ARTICLE -> {
            val pics = normalizeUrls(major?.article?.covers.orEmpty())
            base.copy(coverUrl = pics.firstOrNull().orEmpty(), pics = pics)
        }

        DynamicItemType.OPUS -> {
            val pics = normalizeUrls(major?.opus?.pics.orEmpty())
            base.copy(
                coverUrl = pics.firstOrNull().orEmpty(),
                pics = pics,
                opusTitle = major?.opus?.title.orEmpty(),
            )
        }

        DynamicItemType.WORD ->
            // 旧版纯文字动态：无 major 无可播内容，仅当文案非空（或是锁定动态）才保留，避免出空卡片。
            if (descText.isNotBlank() || base.lockedTitle.isNotBlank() || base.lockedHint.isNotBlank()) base else null

        else -> null
    }
}
