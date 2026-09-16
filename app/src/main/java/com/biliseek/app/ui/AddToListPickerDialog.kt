package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.data.FavoriteFolderEntity
import com.biliseek.app.data.PlaylistEntity

/**
 * 「加入列表」选择器：本地歌单 + B 站收藏夹合并展示。
 *
 * 取代早期「硬选第一个歌单」的行为——用户建了多个歌单，就是因为想分开放。
 * 「新建」是列表里的一项而不是另一个入口：想把歌收进一个还不存在的列表，
 * 是加歌时最常见的念头。
 *
 * 未登录时仅展示本地歌单区域；已登录时展示两个区域，各有独立的新建按钮。
 * 收藏夹条目支持编辑/删除（⋮ 菜单），本地歌单的编辑管理在音乐库页。
 */
@Composable
fun AddToListPickerDialog(
    playlists: List<PlaylistEntity>,
    favoriteFolders: List<FavoriteFolderEntity>,
    isLoggedIn: Boolean,
    onPickPlaylist: (Long) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onPickFavorite: (Long) -> Unit,
    onCreateFavorite: (title: String, intro: String) -> Unit,
    onEditFavorite: (mediaId: Long, newTitle: String) -> Unit,
    onDeleteFavorite: (mediaId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var creatingPlaylist by remember { mutableStateOf(playlists.isEmpty() && !isLoggedIn) }
    var creatingFavorite by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<FavoriteFolderEntity?>(null) }
    var editingFavorite by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var deletingFavorite by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var playlistName by remember { mutableStateOf("") }
    var favoriteTitle by remember { mutableStateOf("") }
    var favoriteIntro by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入列表") },
        text = {
            Column(Modifier.heightIn(max = 400.dp)) {
                // ── 本地歌单区域 ──
                Text(
                    "本地歌单",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                LazyColumn(Modifier.heightIn(max = 160.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        Text(
                            playlist.name,
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPickPlaylist(playlist.id) }
                                .padding(vertical = 14.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        HorizontalDivider()
                    }
                }
                TextButton(onClick = { creatingPlaylist = true }) { Text("＋ 新建歌单") }

                // ── B站收藏夹区域 ──
                if (isLoggedIn) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        "B站收藏夹",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    LazyColumn(Modifier.heightIn(max = 160.dp)) {
                        items(favoriteFolders, key = { it.mediaId }) { folder ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickable { onPickFavorite(folder.mediaId) }
                                        .padding(vertical = 10.dp),
                                ) {
                                    Text(
                                        folder.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${folder.mediaCount} 个",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Text(
                                    "⋮",
                                    Modifier
                                        .clickable { menuFor = folder }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                    TextButton(onClick = { creatingFavorite = true }) { Text("＋ 新建收藏夹") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    // ── 子对话框 ──

    // 新建歌单
    if (creatingPlaylist) {
        AlertDialog(
            onDismissRequest = { creatingPlaylist = false },
            title = { Text("新建歌单并加入") },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    singleLine = true,
                    label = { Text("歌单名") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onCreatePlaylist(playlistName) },
                    enabled = playlistName.isNotBlank(),
                ) { Text("创建并加入") }
            },
            dismissButton = { TextButton(onClick = { creatingPlaylist = false }) { Text("取消") } },
        )
    }

    // 新建收藏夹
    if (creatingFavorite) {
        AlertDialog(
            onDismissRequest = { creatingFavorite = false },
            title = { Text("新建收藏夹并加入") },
            text = {
                Column {
                    OutlinedTextField(
                        value = favoriteTitle,
                        onValueChange = { favoriteTitle = it },
                        singleLine = true,
                        label = { Text("标题") },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = favoriteIntro,
                        onValueChange = { favoriteIntro = it },
                        label = { Text("简介（可选）") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onCreateFavorite(favoriteTitle, favoriteIntro) },
                    enabled = favoriteTitle.isNotBlank(),
                ) { Text("创建并加入") }
            },
            dismissButton = { TextButton(onClick = { creatingFavorite = false }) { Text("取消") } },
        )
    }

    // 收藏夹 ⋮ 菜单：编辑 / 删除
    menuFor?.let { folder ->
        ActionListSheet(
            title = folder.title,
            items = listOf(
                "编辑" to { menuFor = null; editingFavorite = folder.mediaId to folder.title },
                "删除" to { menuFor = null; deletingFavorite = folder.mediaId to folder.title },
            ),
            onDismiss = { menuFor = null },
        )
    }

    // 编辑收藏夹
    editingFavorite?.let { (mediaId, currentTitle) ->
        var editTitle by remember { mutableStateOf(currentTitle) }
        AlertDialog(
            onDismissRequest = { editingFavorite = null },
            title = { Text("编辑收藏夹") },
            text = {
                OutlinedTextField(
                    value = editTitle,
                    onValueChange = { editTitle = it },
                    singleLine = true,
                    label = { Text("标题") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onEditFavorite(mediaId, editTitle) },
                    enabled = editTitle.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingFavorite = null }) { Text("取消") } },
        )
    }

    // 删除收藏夹确认
    deletingFavorite?.let { (mediaId, title) ->
        AlertDialog(
            onDismissRequest = { deletingFavorite = null },
            title = { Text("删除收藏夹") },
            text = { Text("确定删除「$title」？收藏夹内的视频不会被删除。") },
            confirmButton = {
                TextButton(onClick = { onDeleteFavorite(mediaId) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deletingFavorite = null }) { Text("取消") } },
        )
    }
}
