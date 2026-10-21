package com.biliseek.app.data

import com.biliseek.app.region.RegionCatalog
import com.biliseek.app.region.SubRegion

// 首页快捷入口的清单与「显示哪些、什么顺序」（设计稿：docs/superpowers/specs/2026-10-21-home-quick-entries-design.md；第二期分区入口：2026-10-21-region-entries）。
// 纯函数；SettingsStore 只负责落盘，入口 → 榜单 / 收藏夹的映射在 AppViewModel.openQuickEntry。

/** 首页快捷入口。[id] 是存进设置里的稳定 id（不用枚举名，改名不影响已存的设置）；[defaultEnabled] 是出厂开关（也用于以后新增、存的里没有的入口）。 */
sealed interface QuickEntry {
    val id: String
    val title: String
    val defaultEnabled: Boolean
}

/** 固定入口：榜单、播单、分区总入口。声明顺序 = 出厂顺序。 */
enum class FixedQuickEntry(
    override val id: String,
    override val title: String,
    override val defaultEnabled: Boolean = true,
) : QuickEntry {
    WEEKLY("weekly", "每周必看"),
    PRECIOUS("precious", "入站必刷"),
    HOT_SONGS("hot_songs", "热歌榜"),
    ORIGINAL_SONGS("original_songs", "原创音乐榜"),
    PODCAST("podcast", "热门播客"),
    VIDEO_PODCAST("video_podcast", "视频播客"),
    RANKING("ranking", "全站排行榜"),
    REGIONS("regions", "分区"),
}

/** 单个子分区作为快捷入口（设计稿 2026-10-21-region-entries）：出厂关，点了直接进分区详情并停在它上面。 */
data class RegionQuickEntry(val region: SubRegion) : QuickEntry {
    override val id: String get() = "$REGION_ID_PREFIX${region.tid}"
    override val title: String get() = region.name
    override val defaultEnabled: Boolean get() = false
}

private const val REGION_ID_PREFIX = "region:"

data class QuickEntrySetting(val entry: QuickEntry, val enabled: Boolean)

/** 全部入口，出厂顺序：固定入口 → 分区清单里的子分区。 */
val ALL_QUICK_ENTRIES: List<QuickEntry> =
    FixedQuickEntry.entries + RegionCatalog.all.map(::RegionQuickEntry)

/** 出厂：按 [ALL_QUICK_ENTRIES] 顺序，开关取各自的 [QuickEntry.defaultEnabled]。 */
val DEFAULT_QUICK_ENTRIES: List<QuickEntrySetting> = ALL_QUICK_ENTRIES.map { QuickEntrySetting(it, it.defaultEnabled) }

/** 「视频播客」：官方账号的公开收藏夹（网页首页导航「视频播客」页里唯一的播单）。运营换了收藏夹就改这里。 */
const val VIDEO_PODCAST_MEDIA_ID: Long = 3628308062L

private const val OFF_PREFIX = "-"

/**
 * 读设置：[saved] 形如 `weekly,-precious,ranking`（`-` = 关）。
 * - null（从没设置过）→ 出厂；
 * - 不认识的 id 丢掉，重复的只留第一个；
 * - 代码里有、存的里没有的入口（以后版本新加的）追加到末尾，开关按该入口的出厂默认值。
 */
fun resolveQuickEntries(saved: String?): List<QuickEntrySetting> {
    if (saved == null) return DEFAULT_QUICK_ENTRIES
    val byId = ALL_QUICK_ENTRIES.associateBy { it.id }
    val result = mutableListOf<QuickEntrySetting>()
    for (token in saved.split(',')) {
        val text = token.trim()
        val entry = byId[text.removePrefix(OFF_PREFIX)] ?: continue
        if (result.none { it.entry == entry }) result += QuickEntrySetting(entry, enabled = !text.startsWith(OFF_PREFIX))
    }
    ALL_QUICK_ENTRIES
        .filter { entry -> result.none { it.entry == entry } }
        .forEach { result += QuickEntrySetting(it, enabled = it.defaultEnabled) }
    return result
}

/** 写设置，与 [resolveQuickEntries] 互逆。 */
fun encodeQuickEntries(list: List<QuickEntrySetting>): String =
    list.joinToString(",") { if (it.enabled) it.entry.id else OFF_PREFIX + it.entry.id }

/** 首页要显示的：只留打开的，顺序不变。 */
fun visibleQuickEntries(list: List<QuickEntrySetting>): List<QuickEntry> =
    list.filter { it.enabled }.map { it.entry }

/** 开 / 关某一个，位置不变。 */
fun List<QuickEntrySetting>.withEnabled(entry: QuickEntry, enabled: Boolean): List<QuickEntrySetting> =
    map { if (it.entry == entry) it.copy(enabled = enabled) else it }
