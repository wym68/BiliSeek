package com.biliseek.app.region

// 分区列表的纯函数（设计稿：docs/superpowers/specs/2026-10-21-region-entries-design.md）：
// 时间范围、设置解析、短视频过滤、翻页。RegionController 只负责状态与请求。

import com.biliseek.core.network.ChartItem
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** 排序：[param] 是 newlist_rank 的 `order`。声明顺序 = 菜单顺序。 */
enum class RegionOrder(val param: String, val label: String) {
    CLICK("click", "播放"),
    STOW("stow", "收藏"),
    COIN("coin", "硬币"),
    DM("dm", "弹幕"),
    SCORES("scores", "评论"),
}

enum class RegionTimeMode { LAST_7_DAYS, MONTH, CUSTOM }

/** 闭区间 [from]–[to]。 */
data class DateSpan(val from: LocalDate, val to: LocalDate)

/**
 * 分区列表的设置，全部分区共用、存 DataStore。
 * [savedSpan] 只在 [RegionTimeMode.MONTH] / [RegionTimeMode.CUSTOM] 用；最近 7 天每次按当天重算。
 */
data class RegionSettings(
    val timeMode: RegionTimeMode = RegionTimeMode.LAST_7_DAYS,
    val savedSpan: DateSpan? = null,
    val order: RegionOrder = RegionOrder.CLICK,
    val hideShort: Boolean = true,
)

/** 短于这个秒数的算短视频（「隐藏 1 分钟以下」）。 */
const val SHORT_VIDEO_SECONDS = 60L

/** 自定义范围最长天数（含首尾）。 */
const val CUSTOM_SPAN_MAX_DAYS = 30L

/** 自定义范围最早可选的日子。 */
val REGION_EARLIEST: LocalDate = LocalDate.of(2009, 1, 1)

private val API_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
private val LABEL_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
private val LABEL_FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** 最近 7 天：今天往前 6 天到今天。 */
fun lastSevenDays(today: LocalDate): DateSpan = DateSpan(today.minusDays(6), today)

/** 某年某月：1 日到月末；本月只到今天。 */
fun monthSpan(year: Int, month: Int, today: LocalDate): DateSpan {
    val ym = YearMonth.of(year, month)
    val end = ym.atEndOfMonth()
    return DateSpan(ym.atDay(1), if (end.isAfter(today)) today else end)
}

enum class CustomSpanError { REVERSED, TOO_LONG, TOO_EARLY, AFTER_TODAY }

/** 自定义范围是否可用；null = 可用。 */
fun validateCustomSpan(span: DateSpan, today: LocalDate): CustomSpanError? = when {
    span.to.isBefore(span.from) -> CustomSpanError.REVERSED
    span.from.isBefore(REGION_EARLIEST) -> CustomSpanError.TOO_EARLY
    span.to.isAfter(today) -> CustomSpanError.AFTER_TODAY
    ChronoUnit.DAYS.between(span.from, span.to) + 1 > CUSTOM_SPAN_MAX_DAYS -> CustomSpanError.TOO_LONG
    else -> null
}

/** 当前要查的范围。月份 / 自定义缺了存的范围时退回最近 7 天。 */
fun RegionSettings.span(today: LocalDate): DateSpan = when (timeMode) {
    RegionTimeMode.LAST_7_DAYS -> lastSevenDays(today)
    RegionTimeMode.MONTH, RegionTimeMode.CUSTOM -> savedSpan ?: lastSevenDays(today)
}

fun formatApiDate(date: LocalDate): String = date.format(API_DATE)

fun parseApiDate(text: String?): LocalDate? =
    text?.let { runCatching { LocalDate.parse(it, API_DATE) }.getOrNull() }

/** 显示用：今年的写「MM-dd 至 MM-dd」，跨年或往年写完整日期。 */
fun formatSpanLabel(span: DateSpan, today: LocalDate): String {
    val sameYear = span.from.year == today.year && span.to.year == today.year
    val f = if (sameYear) LABEL_SHORT else LABEL_FULL
    return "${span.from.format(f)} 至 ${span.to.format(f)}"
}

/** 从 DataStore 读出的原始值还原设置；缺失或非法的项用默认，要范围的模式范围坏了退回最近 7 天。 */
fun resolveRegionSettings(mode: String?, from: String?, to: String?, order: String?, hideShort: Boolean?): RegionSettings {
    val span = parseApiDate(from)?.let { f -> parseApiDate(to)?.let { t -> DateSpan(f, t) } }
    val parsedMode = RegionTimeMode.entries.firstOrNull { it.name == mode } ?: RegionTimeMode.LAST_7_DAYS
    val timeMode = if (parsedMode != RegionTimeMode.LAST_7_DAYS && span == null) RegionTimeMode.LAST_7_DAYS else parsedMode
    return RegionSettings(
        timeMode = timeMode,
        savedSpan = if (timeMode == RegionTimeMode.LAST_7_DAYS) null else span,
        order = RegionOrder.entries.firstOrNull { it.param == order } ?: RegionOrder.CLICK,
        hideShort = hideShort ?: true,
    )
}

/** 要显示的条目：开关打开时去掉短于 [SHORT_VIDEO_SECONDS] 秒的（时长未知 = 0 也去掉）。 */
fun visibleRegionItems(items: List<ChartItem>, hideShort: Boolean): List<ChartItem> =
    if (hideShort) items.filter { it.durationSec >= SHORT_VIDEO_SECONDS } else items

/** 翻页追加：按 bvid 去重（排序在翻页之间变动会让同一条出现两次）。 */
fun appendUnseenRegionItems(existing: List<ChartItem>, incoming: List<ChartItem>): List<ChartItem> {
    val seen = existing.mapTo(HashSet()) { it.bvid }
    return existing + incoming.filter { seen.add(it.bvid) }
}

/** 还有没有下一页：这一页有东西、且没到最后一页。 */
fun regionHasMore(page: Int, totalPages: Int, received: Int): Boolean = received > 0 && page < totalPages
