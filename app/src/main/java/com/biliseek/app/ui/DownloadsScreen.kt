package com.biliseek.app.ui

import com.biliseek.designsystem.component.BsTopBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import com.biliseek.designsystem.component.BsConfirmDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.data.DownloadContent
import com.biliseek.app.data.DownloadLocation
import com.biliseek.app.data.DownloadStatus
import com.biliseek.app.download.DownloadListPolicy
import com.biliseek.app.download.DownloadTask
import com.biliseek.core.player.formatDuration

/**
 * 「已下载」列表：下载中 / 失败状态可见可重试（M9 §9.5）。
 *
 * 静默失败是本项目反复强调要避免的那类问题——失败在这里有明确文案、
 * 有重试按钮，不是下了没反应。
 *
 * 行样式与 AI 总结列表一致：已完成的条目整行点击即播放；操作只放右侧图标
 * （已完成：删除；失败：重试 + 删除；排队中 / 下载中：取消）。
 *
 * 分三段（[DownloadListPolicy]）：下载中（含排队）、失败（段头「全部重试 / 清除」）、已完成。
 */
@Composable
fun DownloadsScreen(
    tasks: List<DownloadTask>,
    onClose: () -> Unit,
    onPlay: (DownloadTask) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onRetryAllFailed: () -> Unit = {},
    onClearFailed: () -> Unit = {},
) {
    // 公共 / SAF 的删除要先问用户：仅清记录，还是源文件一起删
    var pendingDelete by remember { mutableStateOf<DownloadTask?>(null) }
    // 应用内文件的删除：图标容易误触，先确认一次
    var pendingDeletePrivate by remember { mutableStateOf<DownloadTask?>(null) }

    Column(modifier.fillMaxSize()) {
        BsTopBar(title = "已下载", subtitle = "${tasks.count { it.isDone }} 个", onNavigate = onClose)
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "还没有下载。在搜索结果条目的「⋮」菜单里选「下载」",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }
        val sections = remember(tasks) { DownloadListPolicy.sections(tasks) }
        LazyColumn(Modifier.fillMaxSize()) {
            fun section(
                key: String,
                title: String,
                list: List<DownloadTask>,
                actions: (@Composable () -> Unit)? = null,
            ) {
                if (list.isEmpty()) return
                item(key = "header_$key") { SectionHeader("$title ${list.size}", actions) }
                items(list, key = { it.id }) { task ->
                    DownloadRow(
                        task = task,
                        onPlay = onPlay,
                        onRetry = onRetry,
                        onDelete = onDelete,
                        onAskDelete = { pendingDelete = it },
                        onAskDeletePrivate = { pendingDeletePrivate = it },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            section("active", "下载中", sections.active)
            section("failed", "失败", sections.failed) {
                TextButton(onClick = onRetryAllFailed) { Text("全部重试") }
                TextButton(onClick = onClearFailed) { Text("清除") }
            }
            section("done", "已完成", sections.done)
        }
    }

    pendingDeletePrivate?.let { task ->
        BsConfirmDialog(
            title = "删除这个下载？",
            text = buildString {
                append("将删除应用内保存的文件")
                if (task.fileSize > 0) append("，释放约 ${formatBytes(task.fileSize)}")
                append("。")
            },
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                onDelete(task.id, true)
                pendingDeletePrivate = null
            },
            onDismiss = { pendingDeletePrivate = null },
        )
    }

    pendingDelete?.let { task ->
        DeleteSourceDialog(
            task = task,
            onDismiss = { pendingDelete = null },
            onDeleteRecord = {
                onDelete(task.id, false)
                pendingDelete = null
            },
            onDeleteRecordAndFile = {
                onDelete(task.id, true)
                pendingDelete = null
            },
        )
    }
}

/** 分段标题：「下载中 2」+ 可选的段内操作。 */
@Composable
private fun SectionHeader(text: String, actions: (@Composable () -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        actions?.invoke()
    }
}

@Composable
private fun DownloadRow(
    task: DownloadTask,
    onPlay: (DownloadTask) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long, Boolean) -> Unit,
    onAskDelete: (DownloadTask) -> Unit,
    onAskDeletePrivate: (DownloadTask) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // 只有下载完成的条目能播：整行点击即播放
            .then(if (task.isDone) Modifier.clickable { onPlay(task) } else Modifier)
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverThumbnail(task.coverUrl, width = 72.dp)
        Column(Modifier.weight(1f)) {
            Text(
                task.title.ifBlank { "未命名" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(if (task.content == DownloadContent.VIDEO) "视频" else "音频")
                    append(" · ")
                    append(locationLabel(task.location))
                    if (task.fileSize > 0) append(" · ${formatBytes(task.fileSize)}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusLine(task)
        }
        when {
            task.isDone -> IconButton(onClick = {
                if (task.location == DownloadLocation.PRIVATE) onAskDeletePrivate(task) else onAskDelete(task)
            }) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除")
            }
            task.isFailed -> Row {
                IconButton(onClick = { onRetry(task.id) }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "重试")
                }
                IconButton(onClick = { onDelete(task.id, false) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除")
                }
            }
            // 排队中 / 下载中都可取消；下载中的会立刻中断传输并清掉半成品
            task.isActive -> IconButton(onClick = { onDelete(task.id, false) }) {
                Icon(Icons.Outlined.Close, contentDescription = "取消下载")
            }
        }
    }
}

/**
 * 公共媒体库 / SAF 的删除确认：源文件在系统里，删之前问一句。
 *
 * 两个选项直接可点（同歌单选择框的交互）；「仅清记录」不碰用户文件，
 * 「记录和源文件一起删」会把 MediaStore 条目 / SAF 文档一并移除。
 */
@Composable
private fun DeleteSourceDialog(
    task: DownloadTask,
    onDismiss: () -> Unit,
    onDeleteRecord: () -> Unit,
    onDeleteRecordAndFile: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除下载") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    task.title.ifBlank { "未命名" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "文件在${locationLabel(task.location)}，删除时怎么处理源文件？",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "仅清记录",
                    Modifier.fillMaxWidth().clickable { onDeleteRecord() }.padding(vertical = 14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "记录和源文件一起删",
                    Modifier.fillMaxWidth().clickable { onDeleteRecordAndFile() }.padding(vertical = 14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun StatusLine(task: DownloadTask) {
    when {
        task.status == DownloadStatus.DOWNLOADING -> {
            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Text(
                if (task.progress > 0) "下载中 ${(task.progress * 100).toInt()}%" else "下载中…",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        task.isFailed -> Text(
            task.error.ifBlank { "下载失败" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        task.status == DownloadStatus.QUEUED -> Text(
            "排队中",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun locationLabel(location: String): String = when (location) {
    DownloadLocation.PRIVATE -> "应用内"
    DownloadLocation.PUBLIC -> "公共媒体库"
    DownloadLocation.SAF -> "自定义目录"
    else -> ""
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** 下载条目里的时长提示，保留给后续扩展。 */
@Suppress("unused")
private fun durationHint(durationSec: Long): String =
    if (durationSec > 0) formatDuration(durationSec * 1000) else ""
