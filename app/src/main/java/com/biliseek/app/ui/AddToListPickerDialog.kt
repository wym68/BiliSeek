package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.biliseek.app.data.FavoriteFolderEntity
import com.biliseek.app.data.PlaylistEntity
import com.biliseek.app.ui.components.CenterDialog
import com.biliseek.app.ui.components.InputDialog
import com.biliseek.app.ui.components.SettingsSheetSection
import com.biliseek.designsystem.component.BsConfirmDialog
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing

/**
 * 「加入列表」选择器：稍后再看 + 本地歌单 + B 站收藏夹合并展示。从屏幕中间弹出（[CenterDialog]，与确认框同一套外观），
 * 里面的分区与行沿用底部弹层的排法（设计稿：docs/superpowers/specs/2026-10-04-add-to-list-centered-design.md）。
 *
 * 取代早期「硬选第一个歌单」的行为——用户建了多个歌单，就是因为想分开放。
 * 「新建」是列表里的一项而不是另一个入口：想把歌收进一个还不存在的列表，
 * 是加歌时最常见的念头。
 *
 * 每行只显示名字，不显示条目数。未登录时仅展示本地歌单区域；已登录时最上面多一行「稍后再看」
 * （同「列表」页的顺序；docs/superpowers/specs/2026-10-04-add-to-watch-later-design.md），下面两个区域各有独立的新建按钮。
 * 两类列表都支持 ⋮ 菜单改名/删除：本地歌单直接走 Room 级联，
 * 收藏夹先 dealFavorite 把视频从该收藏夹移除、再 deleteFavoriteFolder。
 */
@Composable
fun AddToListPickerDialog(
    playlists: List<PlaylistEntity>,
    favoriteFolders: List<FavoriteFolderEntity>,
    isLoggedIn: Boolean,
    /** 加入 B 站稍后再看（登录后在最上面一行）。 */
    onPickWatchLater: () -> Unit,
    onPickPlaylist: (Long) -> Unit,
    /** 新建本地歌单并加入（名字, 图标 key）。 */
    onCreatePlaylist: (name: String, icon: String) -> Unit,
    /** 编辑本地歌单的名字和图标。 */
    onRenamePlaylist: (id: Long, name: String, icon: String) -> Unit,
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
    var favoriteTitle by remember { mutableStateOf("") }
    var favoriteIntro by remember { mutableStateOf("") }

    CenterDialog(
        title = "加入列表",
        onDismiss = onDismiss,
        buttons = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        Spacer(Modifier.size(BsSpacing.sm))
        // ── 稍后再看（登录后）：不属于任何分区，不能改名、不能删，所以没有三点 ──
        if (isLoggedIn) {
            PickerRow(name = "稍后再看", onClick = onPickWatchLater, onMore = null, icon = Icons.Outlined.Schedule)
        }
        // ── 本地歌单区域 ──
        SettingsSheetSection("本地歌单", action = { NewButton { creatingPlaylist = true } })
        playlists.forEach { playlist ->
            PickerRow(
                name = playlist.name,
                onClick = { onPickPlaylist(playlist.id) },
                onMore = { menuForPlaylist = playlist },
                icon = playlistIcon(playlist.icon),
            )
        }

        // ── B站收藏夹区域 ──
        if (isLoggedIn) {
            SettingsSheetSection("B站收藏夹", action = { NewButton { creatingFavorite = true } })
            favoriteFolders.forEach { folder ->
                PickerRow(
                    name = folder.title,
                    onClick = { onPickFavorite(folder.mediaId) },
                    onMore = { menuFor = folder },
                )
            }
        }
        Spacer(Modifier.size(BsSpacing.sm))
    }

    // ── 子弹层 / 子对话框 ──

    // 新建歌单（名字 + 图标）
    if (creatingPlaylist) {
        PlaylistEditDialog(
            title = "新建歌单并加入",
            confirmLabel = "创建并加入",
            onConfirm = { name, icon -> onCreatePlaylist(name, icon) },
            onDismiss = { creatingPlaylist = false },
        )
    }

    // 新建收藏夹
    if (creatingFavorite) {
        InputDialog(
            title = "新建收藏夹并加入",
            confirmLabel = "创建并加入",
            confirmEnabled = favoriteTitle.isNotBlank(),
            onConfirm = { onCreateFavorite(favoriteTitle, favoriteIntro) },
            onDismiss = { creatingFavorite = false },
        ) { focus ->
            OutlinedTextField(
                value = favoriteTitle,
                onValueChange = { favoriteTitle = it },
                singleLine = true,
                label = { Text("标题") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            OutlinedTextField(
                value = favoriteIntro,
                onValueChange = { favoriteIntro = it },
                label = { Text("简介（可选）") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
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

    // 本地歌单 ⋮ 菜单：编辑（名字和图标）/ 删除
    menuForPlaylist?.let { playlist ->
        ActionListSheet(
            title = playlist.name,
            items = listOf(
                "编辑" to { menuForPlaylist = null; editingPlaylist = playlist },
                "删除" to { menuForPlaylist = null; deletingPlaylist = playlist },
            ),
            onDismiss = { menuForPlaylist = null },
        )
    }

    // 编辑收藏夹
    editingFavorite?.let { folder ->
        var editTitle by remember(folder.mediaId) { mutableStateOf(folder.title) }
        InputDialog(
            title = "编辑收藏夹",
            confirmLabel = "保存",
            confirmEnabled = editTitle.isNotBlank(),
            onConfirm = {
                onEditFavorite(folder.mediaId, editTitle)
                editingFavorite = null
            },
            onDismiss = { editingFavorite = null },
        ) { focus ->
            OutlinedTextField(
                value = editTitle,
                onValueChange = { editTitle = it },
                singleLine = true,
                label = { Text("标题") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }

    // 编辑本地歌单（名字 + 图标）
    editingPlaylist?.let { playlist ->
        PlaylistEditDialog(
            title = "编辑歌单",
            confirmLabel = "保存",
            initialName = playlist.name,
            initialIcon = playlist.icon,
            onConfirm = { name, icon ->
                onRenamePlaylist(playlist.id, name, icon)
                editingPlaylist = null
            },
            onDismiss = { editingPlaylist = null },
        )
    }

    // 删除收藏夹确认（清空视频 + 删文件夹的过程里是忙碌态）
    deletingFavorite?.let { folder ->
        BsConfirmDialog(
            title = "删除收藏夹",
            text = "确定删除「${folder.title}」？这会同时将该收藏夹内的 ${folder.mediaCount} 个视频从该收藏夹移除。",
            confirmLabel = "删除",
            destructive = true,
            busy = deletingFavoriteMediaId == folder.mediaId,
            onConfirm = { onDeleteFavorite(folder.mediaId) },
            onDismiss = { deletingFavorite = null },
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
        BsConfirmDialog(
            title = "删除歌单",
            text = "确定删除「${playlist.name}」？歌单将被清空。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                onDeletePlaylist(playlist.id)
                deletingPlaylist = null
            },
            onDismiss = { deletingPlaylist = null },
        )
    }
}

/**
 * 一行列表：[icon]（本地歌单的自定义图标、稍后再看的时钟，收藏夹没有）+ 名字（单行省略）+ 右侧三点
 * （[onMore] 为空就没有，右边留白同左边）。整行点 = 加入这个列表。
 */
@Composable
private fun PickerRow(name: String, onClick: () -> Unit, onMore: (() -> Unit)?, icon: ImageVector? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = BsSpacing.xl, end = if (onMore != null) BsSpacing.sm else BsSpacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BsSpacing.md),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = BsSpacing.md),
        )
        if (onMore != null) {
            IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, contentDescription = "更多操作") }
        }
    }
}

/** 分区标题右端的「新建」按钮。 */
@Composable
private fun NewButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = BsSpacing.sm)) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(BsSize.iconSm))
        Text("新建")
    }
}
