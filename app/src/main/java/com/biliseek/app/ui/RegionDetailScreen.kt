package com.biliseek.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.biliseek.app.region.RegionCatalog
import com.biliseek.app.region.RegionDetailState
import com.biliseek.app.region.RegionListState
import com.biliseek.app.region.RegionOrder
import com.biliseek.app.region.RegionSettings
import com.biliseek.app.region.formatSpanLabel
import com.biliseek.app.region.span
import com.biliseek.app.region.visibleRegionItems
import com.biliseek.app.ui.components.miniBarContentPadding
import com.biliseek.core.network.ChartItem
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.designsystem.component.BsEmptyState
import com.biliseek.designsystem.component.BsErrorState
import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.component.BsTopBar
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 分区详情（设计稿 2026-10-21-region-entries）：顶栏「大类名」+ 排序菜单；子分区标签 + 左右滑；
 * 列表上方一行「时间范围（点开设置）· 隐藏 1 分钟以下」；滚到底自动加载下一页，失败给「重试」不自动再来。
 * 短视频只在显示时过滤；一页全被滤掉时照常续页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionDetailScreen(
    detail: RegionDetailState,
    lists: Map<Int, RegionListState>,
    settings: RegionSettings,
    onSettingsChange: (RegionSettings) -> Unit,
    onSelectTab: (Int) -> Unit,
    onLoadMore: (Int) -> Unit,
    onRetry: (Int) -> Unit,
    onItemClick: (Int, ChartItem) -> Unit,
    onClose: () -> Unit,
    videoActions: VideoMenuActions = VideoMenuActions(),
) {
    val today = remember { LocalDate.now() }
    val subs = remember(detail.group) { RegionCatalog.inGroup(detail.group) }
    val selectedIndex = subs.indexOfFirst { it.tid == detail.selectedTid }.coerceAtLeast(0)
    // 按大类分键：换了大类绝不复用上一个的翻页状态
    key(detail.group) {
        val pager = rememberPagerState(initialPage = selectedIndex) { subs.size }
        val scope = rememberCoroutineScope()
        var showTime by remember { mutableStateOf(false) }
        var showOrder by remember { mutableStateOf(false) }
        var menuFor by remember { mutableStateOf<SearchVideo?>(null) }
        menuFor?.let { VideoMenuSheet(it, videoActions) { menuFor = null } }
        if (showTime) RegionTimeSheet(settings, today, onApply = onSettingsChange, onDismiss = { showTime = false })

        // 左右滑到别的页 → 告诉控制器（取这一页的列表）
        LaunchedEffect(pager, subs) {
            snapshotFlow { pager.settledPage }.collect { page -> subs.getOrNull(page)?.let { onSelectTab(it.tid) } }
        }
        // 从外面改了选中（快捷入口进来、点标签）→ 翻到那一页
        // 比较已落定的页，且手指还在滑时不动，免得连续快滑被拉回
        LaunchedEffect(selectedIndex) {
            if (!pager.isScrollInProgress && pager.settledPage != selectedIndex) pager.scrollToPage(selectedIndex)
        }

        Column(Modifier.fillMaxSize()) {
            BsTopBar(
                title = detail.group.title,
                onNavigate = onClose,
                actions = {
                    Box {
                        TextButton(onClick = { showOrder = true }) { Text("按${settings.order.label}") }
                        DropdownMenu(expanded = showOrder, onDismissRequest = { showOrder = false }) {
                            RegionOrder.entries.forEach { o ->
                                DropdownMenuItem(
                                    text = { Text(o.label) },
                                    onClick = { showOrder = false; onSettingsChange(settings.copy(order = o)) },
                                    trailingIcon = if (o == settings.order) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                                )
                            }
                        }
                    }
                },
            )
            ScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 16.dp) {
                subs.forEachIndexed { i, sub ->
                    Tab(selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(sub.name) })
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showTime = true }) {
                    Icon(Icons.Outlined.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(formatSpanLabel(settings.span(today), today))
                }
                Spacer(Modifier.weight(1f))
                Text("隐藏 1 分钟以下", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Switch(checked = settings.hideShort, onCheckedChange = { onSettingsChange(settings.copy(hideShort = it)) })
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { subs[it].tid }) { page ->
                val tid = subs[page].tid
                val state = lists[tid] ?: RegionListState(isLoading = true)
                val visible = visibleRegionItems(state.items, settings.hideShort)
                val error = state.errorMessage
                when {
                    state.isLoading && state.items.isEmpty() -> BsLoadingState()
                    error != null && state.items.isEmpty() -> BsErrorState(error, onRetry = { onRetry(tid) })
                    state.items.isEmpty() -> BsEmptyState("这段时间这个分区没有投稿", icon = regionGroupIcon(detail.group))
                    // 取到的全被「隐藏 1 分钟以下」滤掉且没有后续：给提示，免得空白
                    visible.isEmpty() && !state.hasMore && !state.isLoadingMore && error == null ->
                        BsEmptyState("没有 1 分钟以上的视频，可以关掉「隐藏 1 分钟以下」看看", icon = regionGroupIcon(detail.group))
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = miniBarContentPadding()) {
                        items(visible, key = { it.bvid }) { item ->
                            ChartRow(item, onClick = { onItemClick(tid, item) }, onOpenMenu = { menuFor = item.toSearchVideo() })
                        }
                        if (state.hasMore || state.isLoadingMore || error != null) {
                            item(key = "footer") {
                                // 跟原始条数走：一页全被短视频过滤掉时也会接着取。失败后不自动再来（否则会一直重打 B 站）
                                if (shouldAutoLoadMore(state.hasMore, state.isLoadingMore, error)) {
                                    LaunchedEffect(state.items.size) { onLoadMore(tid) }
                                }
                                Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                                    when {
                                        state.isLoadingMore -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(error, style = MaterialTheme.typography.labelSmall)
                                            TextButton(onClick = { onRetry(tid) }) { Text("重试") }
                                        }
                                        else -> Text("加载中…", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
