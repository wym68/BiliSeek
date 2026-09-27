package com.biliseek.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.biliseek.app.data.FavoriteFolderEntity
import com.biliseek.app.data.PlaylistEntity

/**
 * 列表行右侧的「更多」触发器。40dp 居中 Box 保证 tap target + 图标垂直居中。
 * 调用方需要在右侧再放一个 12dp [Spacer]，让图标与 header 行的「新建」按钮中线对齐。
 */
@Composable
private fun RowMoreIcon(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作", modifier = Modifier.size(20.dp))
    }
}

/**
 * 简易竖直滚动条。
 *
 * Compose 1.12 移除了 `androidx.compose.foundation.VerticalScrollbar`，
 * material3 1.4.0 也没有对应 API，自己根据 `LazyListState` 画一个最简版：
 * 只画 thumb，不画 track，无交互（无拖拽）。
 */
@Composable
private fun SimpleVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    if (total <= 0) return
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return
    val viewportStart = info.viewportStartOffset
    val viewportEnd = info.viewportEndOffset
    val viewportSize = viewportEnd - viewportStart
    if (viewportSize <= 0) return
    val firstOffset = visible.first().offset
    val lastEnd = visible.last().offset + visible.last().size
    val firstIndex = visible.first().index
    val lastIndex = visible.last().index
    val visibleCount = (lastIndex - firstIndex + 1).coerceAtLeast(1)
    val avgItemSize = (lastEnd - firstOffset).coerceAtLeast(1) / visibleCount
    val totalContent = (avgItemSize * total).coerceAtLeast(viewportSize + 1)
    val thumbHeight = (viewportSize.toFloat() * viewportSize / totalContent)
        .coerceAtMost(viewportSize.toFloat())
        .coerceAtLeast(24f)
    val maxOffset = viewportSize - thumbHeight
    val firstItemOffset = state.firstVisibleItemIndex * avgItemSize + state.firstVisibleItemScrollOffset
    val scrollableRange = (totalContent - viewportSize).coerceAtLeast(1)
    val thumbY = (firstItemOffset.toFloat() / scrollableRange * maxOffset)
        .coerceIn(0f, maxOffset)
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Canvas(modifier = modifier) {
        drawRect(
            color = Color.Transparent,
            size = Size(size.width, size.height),
        )
        drawRect(
            color = thumbColor,
            topLeft = Offset(0f, thumbY),
            size = Size(size.width, thumbHeight),
        )
    }
}

/**
 * 「加入列表」选择器：本地歌单 + B 站收藏夹合并展示。
 *
 * 取代早期「硬选第一个歌单」的行为——用户建了多个歌单，就是因为想分开放。
 * 「新建」是列表里的一项而不是另一个入口：想把歌收进一个还不存在的列表，
 * 是加歌时最常见的念头。
 *
 * 未登录时仅展示本地歌单区域；已登录时展示两个区域，各有独立的新建按钮。
 * 两类列表都支持 ⋮ 菜单改名/删除：本地歌单直接走 Room 级联，
 * 收藏夹先 dealFavorite 把视频从该收藏夹移除、再 deleteFavoriteFolder。
 */
@Composable
fun AddToListPickerDialog(
    playlists: List<PlaylistEntity>,
    /** playlistId → 条目数。歌单行「名称 (N)」显示用。 */
    playlistTrackCounts: Map<Long, Int> = emptyMap(),
    favoriteFolders: List<FavoriteFolderEntity>,
    isLoggedIn: Boolean,
    onPickPlaylist: (Long) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (id: Long, name: String) -> Unit,
    onDeletePlaylist: (id: Long) -> Unit,
    onPickFavorite: (Long) -> Unit,
    onCreateFavorite: (title: String, intro: String) -> Unit,
    onEditFavorite: (mediaId: Long, newTitle: String) -> Unit,
    onDeleteFavorite: (mediaId: Long) -> Unit,
    /** 当前正在删除的收藏夹 mediaId（清空视频 + 删文件夹的整段过程）。非空 = 忙碌。 */
    deletingFavoriteMediaId: Long? = null,
    onDismiss: () -> Unit,
) {
    var creatingPlaylist by remember { mutableStateOf(playlists.isEmpty() && !isLoggedIn) }
    var creatingFavorite by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<FavoriteFolderEntity?>(null) }
    var editingFavorite by remember { mutableStateOf<FavoriteFolderEntity?>(null) }
    var deletingFavorite by remember { mutableStateOf<FavoriteFolderEntity?>(null) }
    var menuForPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var editingPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deletingPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var playlistName by remember { mutableStateOf("") }
    var favoriteTitle by remember { mutableStateOf("") }
    var favoriteIntro by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入列表") },
        text = {
            Column(Modifier.heightIn(max = 400.dp)) {
                // ── 本地歌单区域 ──
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "本地歌单",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f).padding(bottom = 4.dp),
                    )
                    TextButton(
                        onClick = { creatingPlaylist = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("新建")
                    }
                }
                val playlistItemDp = 40.dp
                val playlistVisible = minOf(playlists.size, 3)
                val playlistHeight = (playlistVisible * playlistItemDp).coerceAtLeast(0.dp)
                Box(
                    Modifier
                        .height(playlistHeight)
                        .fillMaxWidth(),
                ) {
                    val playlistState = rememberLazyListState()
                    LazyColumn(
                        state = playlistState,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    Modifier
                                        .weight(1f)
                                        .clickable { onPickPlaylist(playlist.id) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        playlist.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        " (${playlistTrackCounts[playlist.id] ?: 0}个内容)",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                RowMoreIcon(onClick = { menuForPlaylist = playlist })
                                Spacer(Modifier.width(12.dp))
                            }
                            HorizontalDivider()
                        }
                    }
                    if (playlists.size > 3) {
                        SimpleVerticalScrollbar(
                            state = playlistState,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(4.dp),
                        )
                    }
                }

                // ── B站收藏夹区域 ──
                if (isLoggedIn) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "B站收藏夹",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f).padding(bottom = 4.dp),
                        )
                        TextButton(
                            onClick = { creatingFavorite = true },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("新建")
                        }
                    }
                    val favoriteItemDp = 40.dp
                    val favoriteVisible = minOf(favoriteFolders.size, 3)
                    val favoriteHeight = (favoriteVisible * favoriteItemDp).coerceAtLeast(0.dp)
                    Box(
                        Modifier
                            .height(favoriteHeight)
                            .fillMaxWidth(),
                    ) {
                        val favoriteState = rememberLazyListState()
                        LazyColumn(
                            state = favoriteState,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(favoriteFolders, key = { it.mediaId }) { folder ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clickable { onPickFavorite(folder.mediaId) }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            folder.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            " (${folder.mediaCount}个内容)",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    RowMoreIcon(onClick = { menuFor = folder })
                                    Spacer(Modifier.width(12.dp))
                                }
                                HorizontalDivider()
                            }
                        }
                        if (favoriteFolders.size > 3) {
                            SimpleVerticalScrollbar(
                                state = favoriteState,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .width(4.dp),
                            )
                        }
                    }
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
                "编辑" to { menuFor = null; editingFavorite = folder },
                "删除" to { menuFor = null; deletingFavorite = folder },
            ),
            onDismiss = { menuFor = null },
        )
    }

    // 本地歌单 ⋮ 菜单：重命名 / 删除
    menuForPlaylist?.let { playlist ->
        ActionListSheet(
            title = playlist.name,
            items = listOf(
                "重命名" to { menuForPlaylist = null; editingPlaylist = playlist },
                "删除" to { menuForPlaylist = null; deletingPlaylist = playlist },
            ),
            onDismiss = { menuForPlaylist = null },
        )
    }

    // 编辑收藏夹
    editingFavorite?.let { folder ->
        var editTitle by remember(folder.mediaId) { mutableStateOf(folder.title) }
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
                    onClick = {
                        onEditFavorite(folder.mediaId, editTitle)
                        editingFavorite = null
                    },
                    enabled = editTitle.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingFavorite = null }) { Text("取消") } },
        )
    }

    // 编辑本地歌单
    editingPlaylist?.let { playlist ->
        var editName by remember(playlist.id) { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { editingPlaylist = null },
            title = { Text("重命名歌单") },
            text = {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    singleLine = true,
                    label = { Text("歌单名") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenamePlaylist(playlist.id, editName)
                        editingPlaylist = null
                    },
                    enabled = editName.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingPlaylist = null }) { Text("取消") } },
        )
    }

    // 删除收藏夹确认
    deletingFavorite?.let { folder ->
        val busy = deletingFavoriteMediaId == folder.mediaId
        AlertDialog(
            onDismissRequest = { if (!busy) deletingFavorite = null },
            title = { Text("删除收藏夹") },
            text = {
                Text(
                    "确定删除「${folder.title}」？这会同时将该收藏夹内的 ${folder.mediaCount} 个视频从该收藏夹移除。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onDeleteFavorite(folder.mediaId) },
                    enabled = !busy,
                ) {
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("处理中…")
                        }
                    } else {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deletingFavorite = null },
                    enabled = !busy,
                ) { Text("取消") }
            },
        )
    }

    // 收藏夹删除完成后（folder 离开列表 且 不在忙碌）自动关闭弹窗
    LaunchedEffect(deletingFavorite, favoriteFolders, deletingFavoriteMediaId) {
        val f = deletingFavorite ?: return@LaunchedEffect
        if (deletingFavoriteMediaId == null && favoriteFolders.none { it.mediaId == f.mediaId }) {
            deletingFavorite = null
        }
    }

    // 删除本地歌单确认
    deletingPlaylist?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deletingPlaylist = null },
            title = { Text("删除歌单") },
            text = { Text("确定删除「${playlist.name}」？歌单将被清空。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePlaylist(playlist.id)
                    deletingPlaylist = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deletingPlaylist = null }) { Text("取消") } },
        )
    }
}
