package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.data.DayCount
import com.biliseek.app.data.PlayHistoryEntity
import com.biliseek.app.data.SearchHistoryEntity

/**
 * 首页空状态。
 *
 * 「没有推荐」不等于「空屏」——展示的全是**用户自己的行为数据**：
 * 搜索历史、最近播放、播放热力图。无算法、无个性化推荐，不破坏产品身份。
 */
@Composable
fun HomeEmptyState(
    searchHistory: List<SearchHistoryEntity>,
    recentPlays: List<PlayHistoryEntity>,
    heatmap: List<DayCount>,
    onKeywordClick: (String) -> Unit,
    onDeleteKeyword: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRecentClick: (PlayHistoryEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (searchHistory.isEmpty() && recentPlays.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp)) {
                Text("Seek and you shall hear.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "搜索全 B 站，默认只听。想看画面时一键切换。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            return@Column
        }

        if (searchHistory.isNotEmpty()) {
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

        if (recentPlays.isNotEmpty()) {
            Section(title = "最近播放") {
                Column {
                    recentPlays.take(8).forEach { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onRecentClick(entry) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${entry.positionMs / 1000}s / ${entry.durationSec}s",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (heatmap.isNotEmpty()) {
            Section(title = "播放热力") { Heatmap(heatmap) }
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

/** 近 N 天播放次数的方块图。数据来自用户自己的播放记录。 */
@Composable
private fun Heatmap(days: List<DayCount>) {
    val max = days.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        items(days, key = { it.day }) { d ->
            val ratio = d.count.toFloat() / max
            Column(
                Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = 0.15f + 0.85f * ratio,
                        ),
                    ),
            ) {}
        }
    }
}
