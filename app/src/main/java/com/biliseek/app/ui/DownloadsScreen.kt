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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.data.DownloadContent
import com.biliseek.app.data.DownloadLocation
import com.biliseek.app.data.DownloadStatus
import com.biliseek.app.download.DownloadTask
import com.biliseek.core.player.formatDuration

/**
 * 「已下载」列表：下载中 / 失败状态可见可重试（M9 §9.5）。
 *
 * 静默失败是本项目反复强调要避免的那类问题——失败在这里有明确文案、
 * 有重试按钮，不是下了没反应。
 */
@Composable
fun DownloadsScreen(
    tasks: List<DownloadTask>,
    onClose: () -> Unit,
    onPlay: (DownloadTask) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 公共 / SAF 的删除要先问用户：仅清记录，还是源文件一起删
    var pendingDelete by remember { mutableStateOf<DownloadTask?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已下载 · ${tasks.count { it.isDone }} 个", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("返回") }
        }
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "还没有下载。在搜索结果里长按条目 → 下载",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(tasks, key = { it.id }) { task ->
                DownloadRow(
                    task = task,
                    onPlay = onPlay,
                    onRetry = onRetry,
                    onDelete = onDelete,
                    onAskDelete = { pendingDelete = it },
                )
                HorizontalDivider()
            }
        }
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

@Composable
private fun DownloadRow(
    task: DownloadTask,
    onPlay: (DownloadTask) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long, Boolean) -> Unit,
    onAskDelete: (DownloadTask) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
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
                )
                StatusLine(task)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            when {
                task.isDone -> {
                    TextButton(onClick = { onPlay(task) }) { Text("播放") }
                    TextButton(onClick = {
                        if (task.location == DownloadLocation.PRIVATE) {
                            // 应用内：删记录就是不要文件了，直接删，不弹窗
                            onDelete(task.id, true)
                        } else {
                            onAskDelete(task)
                        }
                    }) { Text("删除") }
                }
                task.isFailed -> {
                    TextButton(onClick = { onRetry(task.id) }) { Text("重试") }
                    TextButton(onClick = { onDelete(task.id, false) }) { Text("删除") }
                }
                task.status == DownloadStatus.QUEUED -> {
                    Text("排队中", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { onDelete(task.id, false) }) { Text("取消") }
                }
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
            color = Color.Red,
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
