package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.chart.ChartState
import com.biliseek.app.chart.chartRowSubtitle
import com.biliseek.app.ui.components.miniBarContentPadding
import com.biliseek.core.network.ChartItem
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.designsystem.component.BsEmptyState
import com.biliseek.designsystem.component.BsErrorState
import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.component.BsTopBar

/**
 * 榜单页（每周必看 / 入站必刷 / 热歌榜 / 热门播客）。公开接口，不需要登录。
 *
 * 形态照「稍后再看」页：顶栏（副标题放页头说明）+ 下拉刷新 + 加载中 / 失败可重试 / 空列表 + 列表。
 * 有期数的榜单（热门播客、每周必看、热歌榜）在顶栏下多一排期数选择条（[ChartState.issues] 非空时显示）。
 * 列表不给 key：热歌榜里不同的歌可能指向同一个视频，用 bvid 当 key 会因重复而崩溃。
 */
@Composable
fun ChartScreen(
    state: ChartState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onItemClick: (ChartItem) -> Unit,
    onIssueSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 视频行右侧三点菜单的动作，与搜索结果一致（热歌榜的歌没有 UP 主，菜单里少「查看UP主」）。 */
    videoActions: VideoMenuActions = VideoMenuActions(),
) {
    // 三点菜单：当前打开菜单的那一条
    var menuFor by remember { mutableStateOf<SearchVideo?>(null) }
    menuFor?.let { VideoMenuSheet(it, videoActions) { menuFor = null } }

    Column(modifier.fillMaxSize()) {
        BsTopBar(title = state.title, subtitle = state.subtitle, onNavigate = onClose)

        if (state.issues.isNotEmpty()) {
            IssueChips(state.issues, state.selectedIssue, onIssueSelect)
        }

        PullRefreshContainer(
            isRefreshing = state.isLoading && state.items.isNotEmpty(),
            errorMessage = state.errorMessage,
            successText = "${state.title}已刷新",
            onRefresh = onRetry,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.isLoading && state.items.isEmpty() -> BsLoadingState()

                state.errorMessage != null && state.items.isEmpty() ->
                    BsErrorState(state.errorMessage, onRetry = onRetry)

                state.items.isEmpty() -> BsEmptyState("榜单暂时是空的", icon = Icons.Outlined.Leaderboard)

                else -> LazyColumn(Modifier.fillMaxWidth(), contentPadding = miniBarContentPadding()) {
                    items(state.items) { item ->
                        ChartRow(
                            item,
                            onClick = { onItemClick(item) },
                            onOpenMenu = { menuFor = item.toSearchVideo() },
                        )
                    }
                }
            }
        }
    }
}

/** 期数选择条：横向滚动的 FilterChip，最新一期在最左。 */
@Composable
private fun IssueChips(issues: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(issues) { index, label ->
            FilterChip(
                selected = index == selected,
                onClick = { onSelect(index) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun ChartRow(item: ChartItem, onClick: () -> Unit, onOpenMenu: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.rank?.let { rank ->
            Text(
                rank.toString(),
                modifier = Modifier.widthIn(min = 28.dp),
                style = MaterialTheme.typography.titleMedium,
                color = if (rank <= 3) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        CoverThumbnail(item.coverUrl, width = 96.dp)
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                chartRowSubtitle(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        VideoMenuButton(onOpenMenu)
    }
}
