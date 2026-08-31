package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.data.SearchHistoryEntity
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 首页空状态。
 *
 * 「没有推荐」不等于「空屏」——展示的全是**用户自己的行为数据**：
 * 搜索历史、播放热力图、快捷入口。无算法、无个性化推荐，不破坏产品身份。
 *
 * [showSearchHistory]：搜索历史改为「聚焦搜索框时才弹出」（M11 真机反馈），
 * 未聚焦时首页不直接显示历史，避免占版面。
 *
 * M12：不再展示「最近播放」竖排列表——快捷入口的「近期歌单」承担了进列表页的职责。
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
    /** 快捷入口「稍后再看」。登录后才显示。 */
    onOpenWatchLater: () -> Unit = {},
    /** 快捷入口「近期歌单」→ 跳底部列表 tab。 */
    onOpenRecentPlaylists: () -> Unit = {},
    /** 是否已登录（决定「稍后再看」入口是否显示）。 */
    isLoggedIn: Boolean = false,
    modifier: Modifier = Modifier,
    showSearchHistory: Boolean = true,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 全新用户引导：无搜索历史且无播放数据时顶部显示 slogan。
        // 不再 return 跳过后续内容——快捷入口始终可用（M12）。
        if (searchHistory.isEmpty() && heatmap.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp)) {
                Text("Seek and you shall find.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "搜索全 B 站，默认只听。想看画面时一键切换。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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

        // 快捷入口：稍后再看（需登录）+ 近期歌单（跳列表 tab）
        Section(title = "快捷入口") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickEntryCard(
                    symbol = "⏱",
                    label = "稍后再看",
                    onClick = onOpenWatchLater,
                    visible = isLoggedIn,
                    modifier = Modifier.weight(1f),
                )
                QuickEntryCard(
                    symbol = "📁",
                    label = "近期歌单",
                    onClick = onOpenRecentPlaylists,
                    visible = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 快捷入口卡片：symbol + 标签，仿 BBPlayer quickAccessCard。 */
@Composable
private fun QuickEntryCard(
    symbol: String,
    label: String,
    onClick: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
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
            Text(symbol, style = MaterialTheme.typography.headlineSmall)
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
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            items(searchHistory, key = { it.id }) { entry ->
                AssistChip(
                    onClick = { onKeywordClick(entry.keyword) },
                    label = { Text(entry.keyword) },
                    trailingIcon = {
                        Text(
                            "✕",
                            modifier = Modifier.clickable { onDeleteKeyword(entry.keyword) },
                            style = MaterialTheme.typography.labelSmall,
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
 * 7 行（周一到周日）× N 周列，每格一个本地日期：
 * - 无记录：surfaceVariant 底色
 * - 有记录：主题色按次数分 4 级透明度（0.2 / 0.4 / 0.6 / 1.0）
 * - 今天：主色描边
 *
 * 横向 LazyRow 滚动到最右（今天），与 BBPlayer `initialScrollEnd` 一致。
 */
@Composable
private fun WeeklyHeatGrid(
    counts: Map<LocalDate, Int>,
    onDayClick: (LocalDate) -> Unit,
    weeks: Int = 13,
) {
    val primary = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.surfaceVariant
    val today = LocalDate.now()
    // 本周一作为右边界（今天必然在本周内）
    val weekEnd = today.with(DayOfWeek.MONDAY)
    val weekStart = weekEnd.minusWeeks((weeks - 1).toLong())

    // 周一是首列，列内 7 行按 周一..周日
    val rows = DayOfWeek.entries // MONDAY..SUNDAY

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        items(weeks) { weekOffset ->
            val weekStartDate = weekStart.plusWeeks(weekOffset.toLong())
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                rows.forEach { dow ->
                    val date = weekStartDate.with(dow)
                    // 未来日期（本周的周二..周日，若今天是周一）不渲染格子
                    if (date > today) return@forEach
                    val count = counts[date] ?: 0
                    val alpha = heatmapLevelAlpha(count)
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (alpha > 0f) primary.copy(alpha = alpha) else base)
                            .then(
                                if (date == today) {
                                    Modifier.border(1.dp, primary, RoundedCornerShape(3.dp))
                                } else Modifier,
                            )
                            .clickable { onDayClick(date) },
                    )
                }
            }
        }
    }
}

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
