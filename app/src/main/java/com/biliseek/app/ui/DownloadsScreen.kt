package com.biliseek.app.ui

import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.saveable.rememberSaveable
import com.biliseek.app.download.ActiveBulkAction
import com.biliseek.app.ui.components.miniBarContentPadding
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
import com.biliseek.designsystem.theme.BsSpacing
import com.biliseek.app.ui.components.SettingsSheet
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import com.biliseek.app.download.DoneEntry
import com.biliseek.app.hint.HintKey
import com.biliseek.app.download.splitPartTitle
import com.biliseek.app.ui.components.SwipeToRemoveRow

/**
 * 「已下载」列表：下载中 / 失败状态可见可重试（M9 §9.5）。
 *
 * 静默失败是本项目反复强调要避免的那类问题——失败在这里有明确文案、
 * 有重试按钮，不是下了没反应。
 *
 * 行样式与 AI 总结列表一致：已完成的条目整行点击即播放；操作只放右侧图标
 * （已完成：删除；失败：重试 + 删除；排队中 / 下载中：取消）。
 *
 * 分三段（[DownloadListPolicy]）：下载中（含排队、暂停；段头「全部暂停 / 全部继续」「全部取消」和折叠）、
 * 失败（段头「全部重试 / 清除」）、已完成。
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
    onPause: (Long) -> Unit = {},
    onResume: (Long) -> Unit = {},
    onPauseAll: () -> Unit = {},
    onResumeAll: () -> Unit = {},
    onCancelAll: () -> Unit = {},
    /** 已完成条目右滑 / 三点「删除」（一条，或多 P 组的全部分 P）：宿主先藏起来、给撤销，过几秒才真删。 */
    onRemoveDone: (List<DownloadTask>, Boolean) -> Unit = { _, _ -> },
    /** 分 P 页点一条：从这一 P 开始播，其余已下载的分 P 一起进队列。 */
    onPlayGroup: (List<DownloadTask>, Int) -> Unit = { _, _ -> },
    /** 已完成条目三点菜单的动作，与搜索结果一致。 */
    videoActions: VideoMenuActions = VideoMenuActions(),
) {
    // 公共 / SAF 的删除要先问用户：仅清记录，还是源文件一起删
    var pendingRemoval by remember { mutableStateOf<List<DownloadTask>?>(null) }
    // 「下载中」段折叠；「全部取消」先确认（会删掉已下的部分）
    var activeCollapsed by rememberSaveable { mutableStateOf(false) }
    var confirmCancelAll by remember { mutableStateOf(false) }
    // 多 P 组的分 P 页（页内状态，返回键回列表）
    var openedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    // 三点菜单：一条已完成的下载，或一个多 P 组
    var menuFor by remember { mutableStateOf<DownloadTask?>(null) }
    var menuForGroup by remember { mutableStateOf<DoneEntry.Group?>(null) }

    val sections = remember(tasks) { DownloadListPolicy.sections(tasks) }
    val doneEntries = remember(sections.done) { DownloadListPolicy.doneEntries(sections.done) }
    val group = openedGroup?.let { bvid -> doneEntries.filterIsInstance<DoneEntry.Group>().firstOrNull { it.bvid == bvid } }
    // 组里删得只剩一条（或全删了）：分 P 页没东西可看，回列表
    if (openedGroup != null && group == null) {
        LaunchedEffect(openedGroup) { openedGroup = null }
    }

    /** 应用内的直接进入可撤销的删除；公共 / SAF 先问要不要连源文件一起删。 */
    fun requestRemove(list: List<DownloadTask>) {
        if (list.isEmpty()) return
        if (list.all { it.location == DownloadLocation.PRIVATE }) onRemoveDone(list, true) else pendingRemoval = list
    }

    if (group != null) {
        BackHandler { openedGroup = null }
        DownloadGroupDetail(
            group = group,
            onClose = { openedGroup = null },
            onPlay = { index -> onPlayGroup(group.parts, index) },
            onMore = { menuFor = it },
            onRemove = { requestRemove(listOf(it)) },
            modifier = modifier,
        )
    } else {
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
            LazyColumn(Modifier.fillMaxSize(), contentPadding = miniBarContentPadding()) {
                fun section(
                    key: String,
                    title: String,
                    list: List<DownloadTask>,
                    collapsed: Boolean = false,
                    actions: (@Composable () -> Unit)? = null,
                ) {
                    if (list.isEmpty()) return
                    item(key = "header_$key") { SectionHeader("$title ${list.size}", actions) }
                    if (collapsed) return
                    items(list, key = { it.id }) { task ->
                        DownloadRow(
                            task = task,
                            onRetry = onRetry,
                            onDelete = onDelete,
                            onPause = onPause,
                            onResume = onResume,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                section("active", "下载中", sections.active, collapsed = activeCollapsed) {
                    when (DownloadListPolicy.bulkAction(sections.active)) {
                        ActiveBulkAction.PAUSE_ALL -> TextButton(onClick = onPauseAll) { Text("全部暂停") }
                        ActiveBulkAction.RESUME_ALL -> TextButton(onClick = onResumeAll) { Text("全部继续") }
                        null -> Unit
                    }
                    TextButton(onClick = { confirmCancelAll = true }) { Text("全部取消") }
                    IconButton(onClick = { activeCollapsed = !activeCollapsed }) {
                        Icon(
                            if (activeCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                            contentDescription = if (activeCollapsed) "展开" else "折叠",
                        )
                    }
                }
                section("failed", "失败", sections.failed) {
                    TextButton(onClick = onRetryAllFailed) { Text("全部重试") }
                    TextButton(onClick = onClearFailed) { Text("清除") }
                }
                // 已完成：同一个视频的多个分 P 合成一组；每条右滑或三点「删除」可撤销
                if (doneEntries.isNotEmpty()) {
                    item(key = "header_done") { SectionHeader("已完成 ${doneEntries.size}", null) }
                    items(
                        doneEntries,
                        key = { entry ->
                            when (entry) {
                                is DoneEntry.Single -> "done_${entry.task.id}"
                                is DoneEntry.Group -> "group_${entry.bvid}"
                            }
                        },
                    ) { entry ->
                        when (entry) {
                            is DoneEntry.Single -> SwipeToRemoveRow(
                                background = MaterialTheme.colorScheme.background,
                                onRemove = { requestRemove(listOf(entry.task)) },
                                hintKey = HintKey.SWIPE_DOWNLOADS,
                                showHint = entry === doneEntries.firstOrNull(),
                            ) {
                                DoneRow(entry.task, onClick = { onPlay(entry.task) }, onMore = { menuFor = entry.task })
                            }
                            is DoneEntry.Group -> SwipeToRemoveRow(
                                background = MaterialTheme.colorScheme.background,
                                onRemove = { requestRemove(entry.parts) },
                                hintKey = HintKey.SWIPE_DOWNLOADS,
                                showHint = entry === doneEntries.firstOrNull(),
                            ) {
                                GroupRow(entry, onClick = { openedGroup = entry.bvid }, onMore = { menuForGroup = entry })
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    menuFor?.let { task ->
        VideoMenuSheet(
            task.toSearchVideo(),
            videoActions,
            onRemove = { requestRemove(listOf(task)) },
            downloadable = false,
        ) { menuFor = null }
    }
    menuForGroup?.let { g ->
        val first = g.parts.first()
        VideoMenuSheet(
            first.toSearchVideo().copy(title = g.title, duration = ""),
            videoActions,
            onRemove = { requestRemove(g.parts) },
            downloadable = false,
        ) { menuForGroup = null }
    }

    if (confirmCancelAll) {
        val count = tasks.count { it.isActive || it.isPaused }
        BsConfirmDialog(
            title = "全部取消？",
            text = "取消全部 $count 个下载，已下的部分会一起删除。",
            confirmLabel = "全部取消",
            destructive = true,
            onConfirm = {
                onCancelAll()
                confirmCancelAll = false
            },
            onDismiss = { confirmCancelAll = false },
        )
    }

    pendingRemoval?.let { list ->
        DeleteSourceDialog(
            tasks = list,
            onDismiss = { pendingRemoval = null },
            onDeleteRecord = {
                onRemoveDone(list, false)
                pendingRemoval = null
            },
            onDeleteRecordAndFile = {
                onRemoveDone(list, true)
                pendingRemoval = null
            },
        )
    }
}

/** 已完成的一条：整行点即播放，右侧三点菜单。 */
@Composable
private fun DoneRow(task: DownloadTask, onClick: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                metaLine(task),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VideoMenuButton(onMore)
    }
}

/** 多 P 组：视频标题 +「N 个分 P · 总大小」，点开进分 P 页；三点菜单里「删除」删全部分 P。 */
@Composable
private fun GroupRow(group: DoneEntry.Group, onClick: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
            Text(
                buildString {
                    append("${group.parts.size} 个分 P")
                    append(" · ")
                    append(locationLabel(group.parts.first().location))
                    if (group.totalSize > 0) append(" · ${formatBytes(group.totalSize)}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VideoMenuButton(onMore)
    }
}

/** 多 P 组的分 P 页：每行「P几 分P名」+ 大小；点一行从这一 P 开始播；三点菜单与右滑删除同列表页。 */
@Composable
private fun DownloadGroupDetail(
    group: DoneEntry.Group,
    onClose: () -> Unit,
    onPlay: (Int) -> Unit,
    onMore: (DownloadTask) -> Unit,
    onRemove: (DownloadTask) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        BsTopBar(
            title = group.title.ifBlank { "未命名" },
            subtitle = buildString {
                append("${group.parts.size} 个分 P")
                if (group.totalSize > 0) append(" · ${formatBytes(group.totalSize)}")
            },
            onNavigate = onClose,
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = miniBarContentPadding()) {
            itemsIndexed(group.parts, key = { _, part -> part.id }) { index, part ->
                SwipeToRemoveRow(
                    background = MaterialTheme.colorScheme.background,
                    onRemove = { onRemove(part) },
                    hintKey = HintKey.SWIPE_DOWNLOADS,
                    showHint = index == 0,
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPlay(index) }
                            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                splitPartTitle(part.title).second ?: part.title.ifBlank { "未命名" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                metaLine(part),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        VideoMenuButton { onMore(part) }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** 「音频 · 应用内 · 5.2 MB」。 */
private fun metaLine(task: DownloadTask): String = buildString {
    append(if (task.content == DownloadContent.VIDEO) "视频" else "音频")
    append(" · ")
    append(locationLabel(task.location))
    if (task.fileSize > 0) append(" · ${formatBytes(task.fileSize)}")
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

/** 下载中 / 失败的一条（已完成的由 [DoneRow] 画）。 */
@Composable
private fun DownloadRow(
    task: DownloadTask,
    onRetry: (Long) -> Unit,
    onDelete: (Long, Boolean) -> Unit,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
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
                metaLine(task),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusLine(task)
        }
        when {
            task.isFailed -> Row {
                IconButton(onClick = { onRetry(task.id) }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "重试")
                }
                IconButton(onClick = { onDelete(task.id, false) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除")
                }
            }
            // 排队中 / 下载中：暂停（留着已下的部分）或取消（中断传输并清掉半成品）
            task.isActive -> Row {
                IconButton(onClick = { onPause(task.id) }) {
                    Icon(Icons.Outlined.Pause, contentDescription = "暂停下载")
                }
                IconButton(onClick = { onDelete(task.id, false) }) {
                    Icon(Icons.Outlined.Close, contentDescription = "取消下载")
                }
            }
            // 暂停的：继续（从断点接着下）或取消
            task.isPaused -> Row {
                IconButton(onClick = { onResume(task.id) }) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "继续下载")
                }
                IconButton(onClick = { onDelete(task.id, false) }) {
                    Icon(Icons.Outlined.Close, contentDescription = "取消下载")
                }
            }
        }
    }
}

/**
 * 公共媒体库 / SAF 的删除确认：源文件在系统里，删之前问一句。
 *
 * 底部弹层（与下载弹层同一个 [SettingsSheet] 外壳）：两个选项整行可点，各带一句说明，没有「取消」键，
 * 下滑 / 点空白关闭。「仅清记录」不碰用户文件，「记录和源文件一起删」会把 MediaStore 条目 / SAF 文档一并移除。
 */
@Composable
private fun DeleteSourceDialog(
    tasks: List<DownloadTask>,
    onDismiss: () -> Unit,
    onDeleteRecord: () -> Unit,
    onDeleteRecordAndFile: () -> Unit,
) {
    val first = tasks.first()
    val where = locationLabel(tasks.firstOrNull { it.location != DownloadLocation.PRIVATE }?.location ?: first.location)
    val note = if (tasks.size > 1) {
        "${splitPartTitle(first.title).first}（${tasks.size} 个分 P）"
    } else {
        first.title.ifBlank { "未命名" }
    }
    SettingsSheet(title = "删除下载", note = note, onDismiss = onDismiss) {
        Text(
            "文件在${where}，删除时怎么处理源文件？",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = BsSpacing.xl, vertical = BsSpacing.xs),
        )
        DeleteOptionRow("仅清记录", "只删下载记录，文件留在${where}原处", onDeleteRecord)
        DeleteOptionRow("记录和源文件一起删", "同时删除${where}里的文件", onDeleteRecordAndFile, destructive = true)
    }
}

/** 删除下载弹层里的一个选项：标题 + 一句说明，整行可点；有破坏性的选项标题用 error 色。 */
@Composable
private fun DeleteOptionRow(title: String, description: String, onClick: () -> Unit, destructive: Boolean = false) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = BsSpacing.xl, vertical = BsSpacing.md),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
        task.isPaused -> Text(
            if (task.progress > 0) "已暂停 · ${(task.progress * 100).toInt()}%" else "已暂停",
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
