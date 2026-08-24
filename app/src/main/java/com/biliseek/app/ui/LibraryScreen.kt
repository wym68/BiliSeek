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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.biliseek.app.data.PlaylistEntity
import com.biliseek.app.data.PlaylistTrack

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
    modifier: Modifier = Modifier,
) {
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistEntity?>(null) }

    if (openedPlaylist != null) {
        PlaylistDetail(openedPlaylist, tracks, onClose, onTrackClick, onTrackRemove, modifier)
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
    modifier: Modifier = Modifier,
) {
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
            LazyColumn(Modifier.fillMaxSize()) {
                items(tracks, key = { it.track.id }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onTrackClick(item) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.track.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(item.track.author, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { onTrackRemove(item.track.id) }) { Text("移除") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

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
