package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.biliseek.app.data.PlaylistEntity

/**
 * 选择加入哪个歌单。
 *
 * 取代早期「硬选第一个歌单」的行为——用户建了多个歌单，就是因为想分开放。
 * 「新建歌单」是列表里的一项而不是另一个入口：想把歌收进一个还不存在的歌单，
 * 是加歌时最常见的念头。
 */
@Composable
fun PlaylistPickerDialog(
    playlists: List<PlaylistEntity>,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(playlists.isEmpty()) }
    var name by remember { mutableStateOf("") }

    if (creating) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("新建歌单并加入") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("歌单名") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onCreate(name) },
                    enabled = name.isNotBlank(),
                ) { Text("创建并加入") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入歌单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        Text(
                            playlist.name,
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(playlist.id) }
                                .padding(vertical = 14.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        HorizontalDivider()
                    }
                }
                TextButton(onClick = { creating = true }) { Text("＋ 新建歌单") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
