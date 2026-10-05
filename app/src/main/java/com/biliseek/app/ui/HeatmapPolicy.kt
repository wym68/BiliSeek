package com.biliseek.app.ui

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

// 首页播放热力图的格子日期（色阶见 HomeScreen 的 heatmapLevelAlpha）。
//
// 每列是「周日 → 周六」，与左侧星期标签一致；最后一列是今天所在的周。
// 早先用 `today.with(DayOfWeek.SUNDAY)` 定最后一列：java.time 的周是周一到周日，
// 拿到的是本周**结束**的周日，于是每列其实是「周一 → 周日」却从周日画起——
// 顶上那格周日比底下的周六还晚一天，而且今天之后的几天也画了出来。

/** 最后一列（今天所在周）的第一天：今天或之前最近的周日。 */
internal fun heatmapLastWeekStart(today: LocalDate): LocalDate =
    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

/** 第 [column] 列（0 起，共 [weeks] 列，最后一列是今天所在周）第 [row] 行（0 = 周日 … 6 = 周六）的日期。 */
internal fun heatmapCellDate(today: LocalDate, weeks: Int, column: Int, row: Int): LocalDate =
    heatmapLastWeekStart(today).minusWeeks((weeks - 1 - column).toLong()).plusDays(row.toLong())

/** 今天之后的格子不画：只显示到今天。 */
internal fun heatmapCellShown(date: LocalDate, today: LocalDate): Boolean = !date.isAfter(today)
