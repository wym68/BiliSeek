package com.biliseek.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.chart.ChartKind
import com.biliseek.app.data.SearchHistoryEntity
import com.biliseek.app.ui.components.HomeSlogan
import com.biliseek.app.ui.components.LocalMiniBarInset
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 首页空状态。
 *
 * 「没有推荐」不等于「空屏」——展示的以**用户自己的行为数据**为主：搜索历史、播放热力图；
 * 快捷入口是官方榜单（每周必看 / 入站必刷 / 热歌榜 / 热门播客），全站同一份，不需要登录。
 * 无算法、无个性化推荐，不破坏产品身份。
 *
 * [showSearchHistory]：搜索历史改为「聚焦搜索框时才弹出」（M11 真机反馈），
 * 未聚焦时首页不直接显示历史，避免占版面。
 *
 * M12：不再展示「最近播放」竖排列表。
 */
@Composable
fun HomeEmptyState(
    searchHistory: List<SearchHistoryEntity>,
    /** 本地日期 → 播放次数（AppViewModel 已按设备时区聚合）。 */
    heatmap: Map<LocalDate, Int>,
    onKeywordClick: (String) -> Unit,
    onDeleteKeyword: (String) -> Unit,
    onClearHistory: () -> Unit,
    /** 点热力图某天 → 打开该日的播放记录页。 */
    onDayClick: (LocalDate) -> Unit = {},
    /** 点快捷入口里的榜单卡片 → 打开对应的榜单页。 */
    onOpenChart: (ChartKind) -> Unit = {},
    modifier: Modifier = Modifier,
    showSearchHistory: Boolean = true,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // 底部再让出迷你条（它盖在页面最下面）
            .padding(top = 8.dp, bottom = 8.dp + LocalMiniBarInset.current),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 全新用户引导：无搜索历史且无播放数据时顶部显示 slogan。
        // 不再 return 跳过后续内容——快捷入口始终可用（M12）。
        if (searchHistory.isEmpty() && heatmap.isEmpty()) {
            HomeSlogan()
        }

        if (showSearchHistory && searchHistory.isNotEmpty()) {
            SearchHistorySection(
                searchHistory = searchHistory,
                onKeywordClick = onKeywordClick,
                onDeleteKeyword = onDeleteKeyword,
                onClearHistory = onClearHistory,
            )
        }

        if (heatmap.isNotEmpty()) {
            Section(title = "播放热力") { WeeklyHeatGrid(heatmap, onDayClick) }
        }

        // 快捷入口：官方榜单（每周必看 / 入站必刷 / 热歌榜 / 热门播客），不需要登录
        Section(title = "快捷入口") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickEntryCard(
                    icon = Icons.Outlined.CalendarMonth,
                    label = ChartKind.WEEKLY.title,
                    onClick = { onOpenChart(ChartKind.WEEKLY) },
                    modifier = Modifier.weight(1f),
                )
                QuickEntryCard(
                    icon = Icons.Outlined.Whatshot,
                    label = ChartKind.PRECIOUS.title,
                    onClick = { onOpenChart(ChartKind.PRECIOUS) },
                    modifier = Modifier.weight(1f),
                )
                QuickEntryCard(
                    icon = Icons.Outlined.Leaderboard,
                    label = ChartKind.HOT_SONGS.title,
                    onClick = { onOpenChart(ChartKind.HOT_SONGS) },
                    modifier = Modifier.weight(1f),
                )
                QuickEntryCard(
                    icon = Icons.Outlined.Podcasts,
                    label = ChartKind.PODCAST.title,
                    onClick = { onOpenChart(ChartKind.PODCAST) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 快捷入口卡片：symbol + 标签，仿 BBPlayer quickAccessCard。 */
@Composable
private fun QuickEntryCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 搜索历史参考列表。
 *
 * 首页空状态与「聚焦搜索框时弹出」两处共用（M11 真机反馈：历史不直接占版面，
 * 聚焦时才弹出用以参考）。
 */
@Composable
fun SearchHistorySection(
    searchHistory: List<SearchHistoryEntity>,
    onKeywordClick: (String) -> Unit,
    onDeleteKeyword: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    Section(
        title = "搜索历史",
        action = { TextButton(onClick = onClearHistory) { Text("清空") } },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            for (entry in searchHistory) {
                AssistChip(
                    onClick = { onKeywordClick(entry.keyword) },
                    label = { Text(entry.keyword) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "删除",
                            modifier = Modifier.size(16.dp).clickable { onDeleteKeyword(entry.keyword) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    action: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            action?.invoke()
        }
        content()
    }
}

/**
 * 近三个月播放热力图（柱状格，仿 BBPlayer WeeklyHeatMap）。
 *
 * 布局与 BBPlayer 主页对齐：
 * - **月份表头**：每列跨入新月份时在该列上方标「Aug/Sep/...」英文 MMM 简写
 * - **星期侧边栏**：左侧固定「Sun/Mon/.../Sat」英文 ddd 简写，不随列滚动
 * - **周日开头**（BBPlayer `weekStartsOn=0`）：列内 7 行从周日排到周六
 * - **格子 18dp / 间距 4dp / 圆角 4dp**（BBPlayer 主页参数 `cellSize=18, cellGap=4, cellRadius=4`）
 * - 只显示到今天：今天之后的格子不画（留同样大小的空位，列高不变）；日期排法见 HeatmapPolicy
 * - 今天：主色描边；有记录：4 级色阶透明度
 *
 * 横向滚动到今天（BBPlayer `initialScrollEnd`，同步无动画版本 `scrollToItem`）。
 */
@Composable
private fun WeeklyHeatGrid(
    counts: Map<LocalDate, Int>,
    onDayClick: (LocalDate) -> Unit,
    weeks: Int = 53,
) {
    val primary = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val today = LocalDate.now()
    // 星期侧边栏标签：英文 ddd 简写（对齐 BBPlayer `sidebarTextFormat='ddd'`）
    val weekdayLabels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    // 月份表头格式：英文 MMM 简写（对齐 BBPlayer `headerTextFormat='MMM'`）。Locale.ENGLISH
    // 是关键：dayjs/zh-cn 的 MMM 是「1月」中文，这里要英文「Jan」。
    val monthFormatter = remember { DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH) }

    Row(Modifier.padding(horizontal = 16.dp)) {
        // 固定星期侧边栏（不随列滚动）。不设宽度约束——让文字自然宽，
        // 避免「Sun」被裁成「S」（M13 真机反馈：Column.width 会把英文文字裁剪）
        Column(
            Modifier.padding(end = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 与格子列的表头行对齐
            Spacer(Modifier.height(HEADER_ROW_DP.dp))
            weekdayLabels.forEach { label ->
                Box(
                    Modifier.height(18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                    )
                }
            }
        }

        // 横向滚动状态：初始滚到右端（今天列在右）。BBPlayer `initialScrollEnd={true}`
        // 对应 ScrollView.scrollToEnd({animated: false})——同步无动画
        val listState = rememberLazyListState()
        LaunchedEffect(Unit) {
            listState.scrollToItem(weeks - 1)
        }

        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(weeks) { weekOffset ->
                // 这一列的第一天（周日）；行序周日 → 周六（BBPlayer weekStartsOn=0）
                val weekStartDate = heatmapCellDate(today, weeks, weekOffset, row = 0)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 月份表头：跨入新月份才显示（BBPlayer renderHeader 同逻辑）。
                    // 不设宽度约束让文字自然宽（"Aug" 窄于格子 18dp，不会掉宽列），
                    // 避免「Aug」被裁成「A」。文字向左对齐后向右溢出到后续空表头列——
                    // 与 BBPlayer 的 SVG 跨列表头效果一致（相邻两周不可能都跨月，无重叠冲突）
                    val month = weekStartDate.monthValue
                    val prevMonth = weekStartDate.minusWeeks(1).monthValue
                    val showMonthLabel = weekOffset == 0 || month != prevMonth
                    Box(
                        Modifier.height(HEADER_ROW_DP.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (showMonthLabel) {
                            // 月份 3 字母简写（与 BBPlayer dayjs `MMM` 一致，无句点）：
                            // Jan / Feb / ... / Aug / Sep / Oct...
                            Text(
                                weekStartDate.format(monthFormatter),
                                style = MaterialTheme.typography.labelSmall,
                                color = labelColor,
                            )
                        }
                    }

                    for (row in 0..6) {
                        val date = heatmapCellDate(today, weeks, weekOffset, row)
                        if (!heatmapCellShown(date, today)) {
                            // 今天之后的格子不画，留出同样大小的空位
                            Spacer(Modifier.size(18.dp))
                        } else {
                            val count = counts[date] ?: 0
                            val alpha = heatmapLevelAlpha(count)
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (alpha > 0f) primary.copy(alpha = alpha) else base)
                                    .then(
                                        if (date == today) {
                                            Modifier.border(1.dp, primary, RoundedCornerShape(4.dp))
                                        } else Modifier,
                                    )
                                    .clickable { onDayClick(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 热力图表头行高（与格子列顶部对齐）。 */
private val HEADER_ROW_DP = 18

/**
 * 热力图色阶：播放次数 → 主题色透明度。0 = 底（不涂色）。
 *
 * 与 BBPlayer `cellColor` 4 级色阶同构，抽出为纯函数便于单测。
 */
internal fun heatmapLevelAlpha(count: Int): Float = when {
    count <= 0 -> 0f
    count == 1 -> 0.2f
    count <= 3 -> 0.4f
    count <= 6 -> 0.6f
    else -> 1.0f
}
