package com.biliseek.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.biliseek.app.region.CustomSpanError
import com.biliseek.app.region.DateSpan
import com.biliseek.app.region.REGION_EARLIEST
import com.biliseek.app.region.RegionOrder
import com.biliseek.app.region.RegionSettings
import com.biliseek.app.region.RegionTimeMode
import com.biliseek.app.region.formatSpanLabel
import com.biliseek.app.region.span
import com.biliseek.app.region.lastSevenDays
import com.biliseek.app.region.monthSpan
import com.biliseek.app.region.validateCustomSpan
import com.biliseek.app.ui.components.CompactDragHandle
import java.time.LocalDate

/**
 * 分区的时间范围与排序（设计稿 2026-10-21-region-entries）。全部分区共用。
 * 三种时间：最近 7 天（每次按当天重算）/ 按月份（年 + 月）/ 自定义（日期范围选择器，最长 30 天，2009-01-01 起，最晚今天）。
 * 选好点「完成」才生效；关掉弹层不改。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RegionTimeSheet(
    settings: RegionSettings,
    today: LocalDate,
    onApply: (RegionSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(settings.timeMode) }
    var order by remember { mutableStateOf(settings.order) }
    val initialSpan = settings.span(today)
    var year by remember { mutableIntStateOf(initialSpan.from.year) }
    var month by remember { mutableIntStateOf(initialSpan.from.monthValue) }
    val zone = java.time.ZoneOffset.UTC
    fun LocalDate.millis() = atStartOfDay(zone).toInstant().toEpochMilli()
    fun Long.date() = java.time.Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
    val rangeState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = (settings.savedSpan?.from ?: initialSpan.from).millis(),
        initialSelectedEndDateMillis = (settings.savedSpan?.to ?: initialSpan.to).millis(),
        yearRange = REGION_EARLIEST.year..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val d = utcTimeMillis.date()
                return !d.isBefore(REGION_EARLIEST) && !d.isAfter(today)
            }

            override fun isSelectableYear(year: Int) = year in REGION_EARLIEST.year..today.year
        },
    )
    val customSpan = rangeState.selectedStartDateMillis?.let { s ->
        rangeState.selectedEndDateMillis?.let { e -> DateSpan(s.date(), e.date()) }
    }
    val customError = customSpan?.let { validateCustomSpan(it, today) }
    val canApply = mode != RegionTimeMode.CUSTOM || (customSpan != null && customError == null)

    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { CompactDragHandle() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("时间范围", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(RegionTimeMode.LAST_7_DAYS to "最近 7 天", RegionTimeMode.MONTH to "按月份", RegionTimeMode.CUSTOM to "自定义")
                    .forEachIndexed { i, (m, label) ->
                        SegmentedButton(selected = mode == m, onClick = { mode = m }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                    }
            }
            when (mode) {
                RegionTimeMode.LAST_7_DAYS -> Text(formatSpanLabel(lastSevenDays(today), today), color = MaterialTheme.colorScheme.onSurfaceVariant)
                RegionTimeMode.MONTH -> MonthPicker(
                    year, month, today,
                    onYear = { y ->
                        year = y
                        // 切到今年时，已过了的未来月份退回当月
                        if (y == today.year && month > today.monthValue) month = today.monthValue
                    },
                    onMonth = { month = it },
                )
                RegionTimeMode.CUSTOM -> {
                    DateRangePicker(state = rangeState, modifier = Modifier.heightIn(max = 420.dp), title = null, headline = null, showModeToggle = false)
                    customError?.let { Text(customSpanErrorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Text("排序", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RegionOrder.entries.forEach { o -> FilterChip(selected = order == o, onClick = { order = o }, label = { Text(o.label) }) }
            }
            Button(
                onClick = {
                    val span = when (mode) {
                        RegionTimeMode.LAST_7_DAYS -> null
                        RegionTimeMode.MONTH -> monthSpan(year, month, today)
                        RegionTimeMode.CUSTOM -> customSpan
                    }
                    onApply(settings.copy(timeMode = mode, savedSpan = span, order = order))
                    onDismiss()
                },
                enabled = canApply,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("完成") }
        }
    }
}

/** 按月份：年份左右箭头（2009–今年）+ 12 个月份按钮（今年未来的月份不可选）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthPicker(year: Int, month: Int, today: LocalDate, onYear: (Int) -> Unit, onMonth: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onYear(year - 1) }, enabled = year > REGION_EARLIEST.year) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一年")
            }
            Text("$year 年", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onYear(year + 1) }, enabled = year < today.year) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一年")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..12).forEach { m ->
                val future = year == today.year && m > today.monthValue
                FilterChip(selected = m == month, onClick = { onMonth(m) }, enabled = !future, label = { Text("$m 月") })
            }
        }
    }
}

private fun customSpanErrorText(error: CustomSpanError): String = when (error) {
    CustomSpanError.TOO_LONG -> "最长 30 天"
    CustomSpanError.REVERSED -> "结束日期早于开始日期"
    CustomSpanError.TOO_EARLY -> "最早只能选到 2009-01-01"
    CustomSpanError.AFTER_TODAY -> "不能选今天以后的日子"
}
