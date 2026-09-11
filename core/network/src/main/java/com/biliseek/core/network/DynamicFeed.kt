package com.biliseek.core.network

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
    /**
     * 多图：DRAW.items / OPUS.pics / ARTICLE.covers 全集（normalize 后）。
     * 视频投稿与单图文为空列表；转发里以原动态的多图为准（与封面同源）。
     */
    val pics: List<String> = emptyList(),
    /** DRAW 服务端 id（用于评论 oid/type=11）。非 DRAW 始终 0。 */
    val drawId: Long = 0,
    /**
     * 转发 / 评论 / 点赞。转发动态里是**转发者自己的**互动数，与 orig 不同——
     * 与 BiliPai 语义一致："这条转发有多少人转了"是用户的视角，不是原作者的。
     * 接口漏 module_stat 时为 null，UI 据此判断是否渲染底部互动栏。
     */
    val stat: DynamicStatModule? = null,
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
)

/** 动态类型常量（接口返回的 type 枚举值）。 */
object DynamicItemType {
    const val AV = "DYNAMIC_TYPE_AV"
    const val FORWARD = "DYNAMIC_TYPE_FORWARD"
    const val DRAW = "DYNAMIC_TYPE_DRAW"
    const val ARTICLE = "DYNAMIC_TYPE_ARTICLE"
    const val OPUS = "DYNAMIC_TYPE_OPUS"
}

/**
 * 把接口返回的 items 解析为展示条目（M11-C）。
 *
 * 规则：
 * - AV：直接取 major.archive 作视频
 * - FORWARD：取 orig 内嵌的 AV（转发者文案 + 原视频）；orig 是图文则仅展示
 * - DRAW / ARTICLE / OPUS：图文，仅展示首图，video = null
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
    // desc 来源优先级：dynamic.desc.text > opus.summary > article.title
    // ——OPUS 经常正文空、把全文写在 summary；ARTICLE 没有 summary，回退到 title。
    val descText = listOf(
        item.modules?.dynamic?.desc?.text.orEmpty(),
        major?.opus?.summary.orEmpty(),
        major?.article?.title.orEmpty(),
    ).firstOrNull { it.isNotBlank() } ?: ""
    /** 转发者自己的互动数据。转发动态里不要混 orig 的数据（M11-C 决策）。 */
    val stat = item.modules?.stat

    val base = DynamicEntry(
        idStr = item.idStr,
        authorName = author.name,
        authorFace = normalizeCoverUrl(author.face),
        pubTs = author.pubTs,
        descText = descText,
        rawType = item.type,
        video = null,
        coverUrl = "",
        stat = stat,
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
                    )
                } else null,
                coverUrl = cover,
            )
        }

        DynamicItemType.FORWARD -> {
            val orig = item.orig
            val origMajor = orig?.modules?.dynamic?.major
            val origArchive = origMajor?.archive
            // 转发里多图取自 orig：DRAW / OPUS / ARTICLE 同上规则
            val origPics = when (orig?.type) {
                DynamicItemType.DRAW -> normalizeUrls(origMajor?.draw?.items?.map { it.src }.orEmpty())
                DynamicItemType.OPUS -> normalizeUrls(origMajor?.opus?.pics.orEmpty())
                DynamicItemType.ARTICLE -> normalizeUrls(origMajor?.article?.covers.orEmpty())
                else -> emptyList()
            }
            base.copy(
                video = if (origArchive != null && origArchive.bvid.isNotBlank()) {
                    DynamicVideo(
                        bvid = origArchive.bvid,
                        title = stripHighlight(origArchive.title),
                        coverUrl = normalizeCoverUrl(origArchive.cover),
                        durationText = origArchive.durationText,
                        // 转发内嵌视频的统计仍取原视频的（B 站埋点）
                        playText = origArchive.stat?.play.orEmpty(),
                        danmakuText = origArchive.stat?.danmaku.orEmpty(),
                    )
                } else null,
                coverUrl = when {
                    origArchive != null -> normalizeCoverUrl(origArchive.cover)
                    else -> origPics.firstOrNull().orEmpty()
                },
                pics = origPics,
            )
        }

        DynamicItemType.DRAW -> {
            val pics = normalizeUrls(major?.draw?.items?.map { it.src }.orEmpty())
            base.copy(
                coverUrl = pics.firstOrNull().orEmpty(),
                pics = pics,
                drawId = major?.draw?.id ?: 0L,
            )
        }

        DynamicItemType.ARTICLE -> {
            val pics = normalizeUrls(major?.article?.covers.orEmpty())
            base.copy(coverUrl = pics.firstOrNull().orEmpty(), pics = pics)
        }

        DynamicItemType.OPUS -> {
            val pics = normalizeUrls(major?.opus?.pics.orEmpty())
            base.copy(coverUrl = pics.firstOrNull().orEmpty(), pics = pics)
        }

        else -> null
    }
}
