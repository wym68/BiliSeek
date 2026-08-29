package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.biliseek.app.data.PlaylistEntity
import com.biliseek.app.data.PlaylistTrack
import com.biliseek.app.data.reorderNeighbors
import com.biliseek.app.data.reorderTargetIndex
import com.biliseek.core.player.formatDuration
import kotlin.math.roundToInt

@Composable
fun LibraryScreen(
    playlists: List<PlaylistEntity>,
    openedPlaylist: PlaylistEntity?,
    tracks: List<PlaylistTrack>,
    onCreate: (String) -> Unit,
    onOpen: (PlaylistEntity) -> Unit,
    onClose: () -> Unit,
    onDelete: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onTrackClick: (PlaylistTrack) -> Unit,
    onTrackRemove: (Long) -> Unit,
    onTrackReorder: (Long, Double?, Double?) -> Unit,
    /** 已下载任务列表。非空时显示入口——下载中/失败也要能进得去（可见可重试）。 */
    downloads: List<com.biliseek.app.download.DownloadTask> = emptyList(),
    onOpenDownloads: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistEntity?>(null) }

    if (openedPlaylist != null) {
        PlaylistDetail(
            openedPlaylist,
            tracks,
            onClose,
            onTrackClick,
            onTrackRemove,
            onTrackReorder,
            modifier,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("本地歌单", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { showCreate = true }) { Text("新建") }
        }

        // 「已下载」分区：音乐库页现在只有本地歌单，这是离线内容的归处。
        // 只要有过下载记录就显示入口——失败的任务也得进得去重试，不能藏起来
        if (downloads.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDownloads)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已下载", style = MaterialTheme.typography.bodyLarge)
                Text("${downloads.count { it.isDone }} 个 ›", style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider()
        }

        if (playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("还没有歌单，新建一个开始收藏", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(playlist) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
                        Row {
                            TextButton(onClick = { renaming = playlist }) { Text("改名") }
                            TextButton(onClick = { onDelete(playlist.id) }) { Text("删除") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showCreate) {
        NameDialog("新建歌单", "") { name ->
            showCreate = false
            if (name.isNotBlank()) onCreate(name)
        }
    }
    renaming?.let { target ->
        NameDialog("重命名", target.name) { name ->
            renaming = null
            if (name.isNotBlank()) onRename(target.id, name)
        }
    }
}

@Composable
private fun PlaylistDetail(
    playlist: PlaylistEntity,
    tracks: List<PlaylistTrack>,
    onClose: () -> Unit,
    onTrackClick: (PlaylistTrack) -> Unit,
    onTrackRemove: (Long) -> Unit,
    onTrackReorder: (Long, Double?, Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 拖拽态。行高固定，位移换算成「挪了几格」即可，不必测量每一行
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${playlist.name} · ${tracks.size} 首", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("返回") }
        }
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("空歌单。在搜索结果里长按可加入", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(
                "长按条目可拖动排序",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall,
            )
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(tracks, key = { _, it -> it.track.id }) { index, item ->
                    val dragging = draggingId == item.track.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(ROW_HEIGHT)
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                            .background(
                                if (dragging) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    Color.Transparent
                                },
                            )
                            .pointerInput(item.track.id, tracks.size) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingId = item.track.id
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                    },
                                    onDragEnd = {
                                        val slots = (dragOffset / rowHeightPx).roundToInt()
                                        val to = reorderTargetIndex(index, slots, tracks.size)
                                        if (to != index) {
                                            val (before, after) = reorderNeighbors(
                                                tracks.map { it.sortKey },
                                                index,
                                                to,
                                            )
                                            onTrackReorder(item.track.id, before, after)
                                        }
                                        draggingId = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        dragOffset = 0f
                                    },
                                )
                            }
                            .clickable { onTrackClick(item) }
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverThumbnail(item.track.coverUrl, width = 72.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.track.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                item.track.author + "  " +
                                    formatDuration(item.track.durationSec * 1000),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        TextButton(onClick = { onTrackRemove(item.track.id) }) { Text("移除") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** 固定行高，拖拽位移据此换算成挪动了几格。 */
private val ROW_HEIGHT = 72.dp

@Composable
private fun NameDialog(title: String, initial: String, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone("") },
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { onDone("") }) { Text("取消") } },
    )
}
