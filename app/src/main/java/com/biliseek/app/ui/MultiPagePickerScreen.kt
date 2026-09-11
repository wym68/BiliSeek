package com.biliseek.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.MultiPagePickerState
import com.biliseek.app.PendingAction
import com.biliseek.core.player.formatDuration

/**
 * 单条多 P 视频的分 P 选择页（Ch3）。**仅正常态三点按钮触发**——批量场景取全 P、不弹此页。
 *
 * 采用「预选动作」模型：动作已在三点按钮 sheet 里选定（[MultiPagePickerState.pendingAction]），
 * 本页只负责挑 P + 确认。选中项按 pages 自然顺序执行（由 ViewModel 保证）。
 */
@Composable
fun MultiPagePickerScreen(
    state: MultiPagePickerState,
    onConfirm: (selectedCids: List<Long>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    // 默认全选：进来通常就是想对整张多 P 执行
    var selected by remember(state.bvid) {
        mutableStateOf(state.pages.map { it.cid }.toSet())
    }
    Surface(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "选择分 P · 已选 ${selected.size}/${state.pages.size}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 全选 / 全不选切换（退出本页走系统返回手势，页内 BackHandler 兜底）
                    val allSelected = selected.size == state.pages.size && state.pages.isNotEmpty()
                    TextButton(
                        onClick = {
                            selected = if (allSelected) {
                                emptySet()
                            } else {
                                state.pages.map { it.cid }.toSet()
                            }
                        },
                    ) { Text(if (allSelected) "全不选" else "全选") }
                    TextButton(
                        onClick = { onConfirm(selected.toList()) },
                        enabled = selected.isNotEmpty(),
                    ) { Text(actionLabel(state.pendingAction)) }
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.pages, key = { it.cid }) { entry ->
                    val checked = entry.cid in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (checked) selected - entry.cid else selected + entry.cid
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(
                                "P${entry.page}  ${entry.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatDuration(entry.durationSec * 1000),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun actionLabel(action: PendingAction): String = when (action) {
    PendingAction.AddToPlaylist -> "加入歌单"
    PendingAction.AppendToQueue -> "加入播放列表"
    PendingAction.EnqueueNext -> "下一首播放"
    PendingAction.Download -> "下载"
}
