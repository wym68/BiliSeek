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
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.biliseek.app.data.QuickEntry
import com.biliseek.app.hint.HintKey
import com.biliseek.app.ui.components.HintBubble
import com.biliseek.app.ui.components.HintTiming
import com.biliseek.app.ui.components.rememberHintDemo
import kotlinx.coroutines.delay
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
 * 快捷入口是官方榜单与官方播单（设置 → 首页快捷入口 里选显示哪些、调顺序），全站同一份，不需要登录。
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
    /** 每天格子的深浅（0–1，见 [heatmapCellAlpha]）；没有的日子不涂色。 */
    heatmap: Map<LocalDate, Float>,
    onKeywordClick: (String) -> Unit,
    onDeleteKeyword: (String) -> Unit,
    onClearHistory: () -> Unit,
    /** 点热力图某天 → 打开该日的播放记录页。 */
    onDayClick: (LocalDate) -> Unit = {},
    /** 要显示的快捷入口（已按设置过滤、排好序）；空 = 整节不显示。 */
    quickEntries: List<QuickEntry> = emptyList(),
    /** 点快捷入口 → 打开对应的榜单页 / 播单。 */
    onOpenQuickEntry: (QuickEntry) -> Unit = {},
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

        if (quickEntries.isNotEmpty()) {
            Section(title = "快捷入口") { QuickEntryGrid(quickEntries, onOpenQuickEntry) }
        }
    }
}

/**
 * 快捷入口网格：每行 [QUICK_ENTRY_COLUMNS] 张，最后一行不足时靠左、空位等宽占住。
 * 第一次显示时在上方弹一次说明气泡（没有手势动画，碰一下或停满时长后记为看过）。
 */
@Composable
private fun QuickEntryGrid(entries: List<QuickEntry>, onOpen: (QuickEntry) -> Unit) {
    val demo = rememberHintDemo(HintKey.HOME_QUICK_ENTRIES, ready = entries.isNotEmpty())
    var showBubble by remember { mutableStateOf(false) }
    LaunchedEffect(demo.running) {
        showBubble = false
        if (!demo.running) return@LaunchedEffect
        delay(HintTiming.START_DELAY_MS)
        showBubble = true
        delay(HintTiming.BUBBLE_HOLD_MS)
        demo.finish()
    }
    Box {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            entries.chunked(QUICK_ENTRY_COLUMNS).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { entry ->
                        QuickEntryCard(
                            icon = quickEntryIcon(entry),
                            label = entry.title,
                            onClick = { onOpen(entry) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(QUICK_ENTRY_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (showBubble) HintBubble(text = QUICK_ENTRY_HINT_TEXT, preferAbove = true)
    }
}

/** 快捷入口的图标；首页与设置页共用。 */
internal fun quickEntryIcon(entry: QuickEntry): ImageVector = when (entry) {
    QuickEntry.WEEKLY -> Icons.Outlined.CalendarMonth
    QuickEntry.PRECIOUS -> Icons.Outlined.Whatshot
    QuickEntry.HOT_SONGS -> Icons.Outlined.Leaderboard
    QuickEntry.ORIGINAL_SONGS -> Icons.Outlined.MusicNote
    QuickEntry.PODCAST -> Icons.Outlined.Podcasts
    QuickEntry.VIDEO_PODCAST -> Icons.Outlined.Mic
    QuickEntry.RANKING -> Icons.Outlined.EmojiEvents
}

private const val QUICK_ENTRY_COLUMNS = 4

private const val QUICK_ENTRY_HINT_TEXT = "入口可以在 设置 → 首页快捷入口 里关闭和排序"

/** 快捷入口卡片：symbol + 标签，仿 BBPlayer quickAccessCard。 */
@Composable
private fun QuickEntryCard(
    icon: ImageVector,
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
                            contentDescription = "移除",
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
    levels: Map<LocalDate, Float>,
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
                            val alpha = levels[date] ?: 0f
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
 * 热力图色阶：播放次数 → 主题色透明度。0 = 底（不涂色）。10-04 以前没有收听时长的日子用（见 [heatmapCellAlpha]）。
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
