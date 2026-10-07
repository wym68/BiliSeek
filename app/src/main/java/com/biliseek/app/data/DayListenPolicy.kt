package com.biliseek.app.data

import androidx.room.Embedded

// 日播放记录页与热力图的口径（设计稿 2026-10-07-listen-entry）：有按视频的收听明细（listen_entry）就用明细，
// 列表、当日时长、热力图深浅出自同一份数据；10-07 以前的日子没有明细，回退到 listen_day / play_history。

/** listen_entry JOIN play_history 的一行：这个视频这天听了 [listenedMs]，最后一次在 [lastListenedAt]。 */
data class DayListenRow(
    @Embedded val history: PlayHistoryEntity,
    val listenedMs: Long,
    val lastListenedAt: Long,
)

/** 某天（`LocalDate.toEpochDay()`）的毫秒合计。 */
data class DayMs(val epochDay: Long, val ms: Long)

/** 日页的一行；[listenedMs] 为 null 表示回退口径，显示视频完整时长。 */
data class DayRow(val history: PlayHistoryEntity, val listenedMs: Long?)

/** 日页内容；[totalMs] 为 null 显示「未统计」。 */
data class DayView(val rows: List<DayRow>, val totalMs: Long?)

/** [listened] 已按最后收听时间倒序。有明细用明细；否则列表用 [legacyHistory]、时长用 [legacyMs]。 */
fun resolveDayView(
    listened: List<DayListenRow>,
    legacyMs: Long?,
    legacyHistory: List<PlayHistoryEntity>,
): DayView =
    if (listened.isNotEmpty()) {
        DayView(listened.map { DayRow(it.history, it.listenedMs) }, listened.sumOf { it.listenedMs })
    } else {
        DayView(legacyHistory.map { DayRow(it, null) }, legacyMs)
    }

/** 每天的收听合计：有明细的日子用明细之和，其余用 listen_day 的旧合计。 */
fun mergeDayTotals(entries: List<DayMs>, legacy: List<DayMs>): Map<Long, Long> =
    legacy.associate { it.epochDay to it.ms } + entries.associate { it.epochDay to it.ms }
