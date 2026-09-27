package com.biliseek.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import com.biliseek.designsystem.theme.BsSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.core.player.PlaybackRequest
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.formatDuration

/**
 * 播放列表 sheet（Ch5 §5.1.3）。
 *
 * - 标题：「播放队列 · N 首」+ 关闭
 * - 每行：序号（01/02…）、标题（单行省略）、时长
 * - 当前播放高亮（主色 + 「✓ 当前播放」前缀）
 * - 点击条目：onSkipTo(index)；点当前条目仅关闭 sheet
 * - 「清空队列」按钮
 * - 队列为空时显示「队列为空」+「从搜索结果加入」提示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerQueueSheet(
    state: PlaybackState,
    onSkipTo: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val queue = state.queue
    val currentIndex = state.queueIndex
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "播放队列 · ${queue.size} 首",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("关闭", Modifier.clickable(onClick = onDismiss).padding(8.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider()
            if (queue.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("队列为空", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "从搜索结果加入（长按结果 / 复选工具栏「加入队列」）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().fillMaxSize()) {
                    itemsIndexed(queue) { idx, item ->
                        QueueRow(
                            index = idx,
                            request = item,
                            isCurrent = idx == currentIndex,
                            onClick = {
                                if (idx != currentIndex) onSkipTo(idx)
                                else onDismiss()
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onClearQueue,
                    enabled = queue.isNotEmpty(),
                ) { Text("清空队列") }
            }
        }
    }
}

@Composable
private fun QueueRow(
    index: Int,
    request: PlaybackRequest,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val title = when (request) {
        is PlaybackRequest.Bili -> request.title.ifBlank { request.bvid }
        is PlaybackRequest.Direct -> request.uri
    }
    val durationSec = (request as? PlaybackRequest.Bili)?.durationSec ?: 0L
    val tint = if (isCurrent) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "%02d".format(index + 1),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
        if (isCurrent) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = "当前播放",
                tint = tint,
                modifier = Modifier.size(BsSize.iconSm),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (durationSec > 0) formatDuration(durationSec * 1000) else "",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}