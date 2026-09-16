package com.biliseek.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TextButton

/**
 * 复选模式工具栏（Ch3，提取为共用组件）。
 *
 * 上行：计数 + 全选/全不选 + 退出；下行：加入列表 / 加入队列 / 下一首 / 下载。
 * 搜索结果、多 P 面板、UP 空间投稿三处复用，回调由调用方接各自的数据源。
 */
@Composable
fun SelectionToolbar(
    selectedCount: Int,
    visibleCount: Int,
    allSelected: Boolean,
    canAddToPlaylist: Boolean,
    canDownload: Boolean,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAppendToQueue: () -> Unit,
    onEnqueueNext: () -> Unit,
    onDownload: () -> Unit,
    onExit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        // 上行：计数独占一行，右侧全选/全不选 + 退出
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已选 $selectedCount 项 · 共 $visibleCount 可见",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ToolbarButton(
                text = if (allSelected) "全不选" else "全选",
                enabled = !allSelected || selectedCount > 0,
                onClick = if (allSelected) onClearSelection else onSelectAll,
            )
            ToolbarButton("退出", onClick = onExit)
        }
        // 下行：四个动作均分一行
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        ) {
            ToolbarButton("加入列表", Modifier.weight(1f), enabled = selectedCount > 0 && canAddToPlaylist, onClick = onAddToPlaylist)
            ToolbarButton("加入队列", Modifier.weight(1f), enabled = selectedCount > 0, onClick = onAppendToQueue)
            ToolbarButton("下一首", Modifier.weight(1f), enabled = selectedCount > 0, onClick = onEnqueueNext)
            ToolbarButton("下载", Modifier.weight(1f), enabled = selectedCount > 0 && canDownload, onClick = onDownload)
        }
    }
}

@Composable
fun ToolbarButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
