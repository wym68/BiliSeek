package com.biliseek.core.network

import com.biliseek.core.network.dto.DynamicBlock
import com.biliseek.core.network.dto.DynamicItem

/**
 * 图文动态详情（点开看全文）的纯函数策略，对齐 bilipai
 * `DynamicDetailFallbackPolicy` 的可渲染性判定 / 内容评分 / 候选合并。
 *
 * 四级降级在 [BiliRepository.dynamicDetail] 编排，这里只做无副作用的判定与合并，
 * 方便本地 mock 单测（不访问 B 站）。
 */

/** 折叠动态的链接占位文案：出现它等于"没有正文"，应触发兜底。 */
internal fun isFoldedLinkPlaceholder(text: String): Boolean = text.trim() == "网页链接"

/**
 * 候选是否含可渲染内容：有正文（非占位）、有 major 主体、或本身是转发。
 * 全部没有说明解析结构不兼容，需要降级到下一级详情源。
 */
internal fun hasRenderableContent(item: DynamicItem): Boolean {
    val content = item.modules?.dynamic ?: return false
    val descText = content.desc?.text.orEmpty()
    val hasDesc = descText.isNotBlank() && !isFoldedLinkPlaceholder(descText)
    return hasDesc || content.major != null || item.orig != null
}

/**
 * 是否需要追加 opus/detail（第 2 级）：
 * web 详情是 OPUS/DRAW 主体、动态本身是 DRAW，或评论区归属是相簿（type=11）时——
 * 正文在 opus/detail 的 paragraphs 里。
 */
internal fun shouldRequestOpusDetail(item: DynamicItem?): Boolean {
    if (item == null) return true
    val major = item.modules?.dynamic?.major
    if (major?.type == "MAJOR_TYPE_OPUS" || major?.opus != null) return true
    if (major?.type == "MAJOR_TYPE_DRAW" || major?.draw != null) return true
    if (item.type.equals(DynamicItemType.DRAW, ignoreCase = true)) return true
    return item.basic?.commentType == 11
}

/**
 * 候选内容评分：pics 越多、正文越长越优。
 * 长文 paragraphs 已被 [DynamicModulesCompatSerializer] 归一化进 desc/opus。
 *
 * 带原文段落（opus/detail）的候选总是优先（对齐 bilipai 的 10 万分加成）：
 * feed / web 详情的 summary 是截断摘要，图片位置只剩「[图片]」占位，长度反而可能更长。
 */
internal fun detailContentScore(item: DynamicItem): Int {
    val content = item.modules?.dynamic
    val opus = content?.major?.opus
    if (opus != null) {
        if (opus.blocks.isNotEmpty()) {
            return BLOCKS_SCORE_BONUS + opus.blocks.sumOf { block ->
                when (block) {
                    is DynamicBlock.Text -> block.text.length
                    is DynamicBlock.Images -> block.pics.size
                }
            }
        }
        return opus.pics.size + (opus.summary?.text?.length ?: 0)
    }
    return content?.desc?.text?.length ?: 0
}

private const val BLOCKS_SCORE_BONUS = 100_000

/** [parseDynamicItem] 认得的动态类型。 */
private val KNOWN_DYNAMIC_TYPES = setOf(
    DynamicItemType.AV,
    DynamicItemType.FORWARD,
    DynamicItemType.DRAW,
    DynamicItemType.ARTICLE,
    DynamicItemType.OPUS,
    DynamicItemType.WORD,
)

/** 从候选里选最优：优先可渲染，再按 [detailContentScore] 取最大。 */
internal fun pickBestDetailItem(candidates: List<DynamicItem>): DynamicItem? {
    val renderable = candidates.filter(::hasRenderableContent)
    if (renderable.isEmpty()) return candidates.firstOrNull()
    return renderable.maxByOrNull(::detailContentScore) ?: renderable.first()
}

/**
 * 把最优详情候选合并成展示条目（seed = feed 卡片条目）：
 * - 详情的 `type` 认不出（opus/detail 可能下发数字）时按 seed 的类型解析，没有 seed 按 OPUS；
 * - 详情带正文段落时正文以详情为准（feed 摘要含「[图片]」占位，可能反而更长）；
 *   否则正文取**更长**者（detail 的 paragraphs 全文 vs feed 摘要）；
 * - 图片取**并集**（detail 可能只有正文没有预览图，bilipai 注：丢图是回归）；
 * - 互动数据以 feed 为准（detail 常缺 module_stat）；
 * - 视频/标题/封面缺啥补啥。
 */
internal fun mergeDetailItem(item: DynamicItem, seed: DynamicEntry?): DynamicEntry {
    val typed = if (item.type in KNOWN_DYNAMIC_TYPES) item else item.copy(type = seed?.rawType ?: DynamicItemType.OPUS)
    val parsed = parseDynamicItem(typed)
        ?: throw IllegalStateException("动态详情无法解析（type=${item.type}）")
    if (seed == null) return parsed
    return parsed.copy(
        descText = if (parsed.body.isNotEmpty()) {
            parsed.descText
        } else {
            listOf(parsed.descText, seed.descText).maxBy { it.length }
        },
        pics = (parsed.pics + seed.pics).distinct(),
        coverUrl = parsed.coverUrl.ifBlank { seed.coverUrl },
        opusTitle = parsed.opusTitle.ifBlank { seed.opusTitle },
        stat = seed.stat ?: parsed.stat,
        video = parsed.video ?: seed.video,
        authorName = parsed.authorName.ifBlank { seed.authorName },
        authorFace = parsed.authorFace.ifBlank { seed.authorFace },
        emotes = seed.emotes + parsed.emotes,
    )
}
