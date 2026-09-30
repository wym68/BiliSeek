package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.summary.SummaryGroup
import com.biliseek.app.summary.SummaryGroupState
import com.biliseek.app.summary.SummaryLibraryPolicy
import com.biliseek.app.summary.SummaryLibraryState
import com.biliseek.designsystem.component.BsConfirmDialog
import com.biliseek.designsystem.component.BsTopBar

/**
 * 「AI 总结」本地列表：所有存在本机的总结，一条视频一行（多个版本合并显示），
 * 点进去看、右侧垃圾桶删除，顶部显示总占用并可一键清空——总结文件会一直累积，得让人看得见、删得掉。
 */
@Composable
fun SummaryListScreen(
    state: SummaryLibraryState,
    onClose: () -> Unit,
    onOpen: (SummaryGroup) -> Unit,
    onDelete: (SummaryGroup) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<SummaryGroup?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        BsTopBar(
            title = "AI 总结",
            subtitle = if (state.isEmpty) null else "${state.groups.size} 个视频 · 占用 ${SummaryLibraryPolicy.formatSize(state.totalBytes)}",
            onNavigate = onClose,
            actions = {
                if (!state.isEmpty) {
                    TextButton(onClick = { confirmClear = true }) { Text("清空") }
                }
            },
        )
        if (state.isEmpty) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "还没有 AI 总结。在播放页点「总结」，或在搜索结果里长按条目 → AI 总结",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().testTag("summary_list")) {
            items(state.groups, key = { "${it.bvid}_${it.cid}" }) { group ->
                SummaryRow(group, onOpen = { onOpen(group) }, onAskDelete = { pendingDelete = group })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    pendingDelete?.let { group ->
        BsConfirmDialog(
            title = "删除这个视频的总结？",
            text = if (group.versionCount > 1) {
                "将删除全部 ${group.versionCount} 个版本及保存的原文，释放约 ${SummaryLibraryPolicy.formatSize(group.sizeBytes)}。"
            } else {
                "将删除这份总结及保存的原文，释放约 ${SummaryLibraryPolicy.formatSize(group.sizeBytes)}。"
            },
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                onDelete(group)
            },
            onDismiss = { pendingDelete = null },
        )
    }
    if (confirmClear) {
        BsConfirmDialog(
            title = "清空全部 AI 总结？",
            text = SummaryLibraryPolicy.clearAllMessage(state.groups),
            confirmLabel = "全部清空",
            destructive = true,
            onConfirm = {
                confirmClear = false
                onClearAll()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun SummaryRow(group: SummaryGroup, onOpen: () -> Unit, onAskDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverThumbnail(group.coverUrl, width = 72.dp)
        Column(Modifier.weight(1f)) {
            Text(
                group.title.ifBlank { "未命名" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (group.pageTitle.isNotBlank() && group.pageTitle != group.title) {
                Text(
                    group.pageTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                SummaryLibraryPolicy.subtitle(group),
                style = MaterialTheme.typography.labelSmall,
                color = if (group.state == SummaryGroupState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(onClick = onAskDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "删除")
        }
    }
}
