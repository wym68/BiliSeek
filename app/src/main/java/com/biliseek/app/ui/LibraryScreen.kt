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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.data.FavoriteFolderEntity
import com.biliseek.app.SyncState
import com.biliseek.app.FavoriteTrack
import com.biliseek.app.LoginPanel
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.LoginState
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
    // ── 收藏夹（M11-B）──────────────────────────────
    favoriteFolders: List<FavoriteFolderEntity> = emptyList(),
    favoriteSync: SyncState = SyncState.Idle,
    favoriteContent: RemoteListState<FavoriteTrack>? = null,
    loginState: LoginState = LoginState.Idle,
    session: BiliSession = BiliSession.EMPTY,
    onSyncFavorites: () -> Unit = {},
    onOpenFavorite: (Long, String) -> Unit = { _, _ -> },
    onCloseFavorite: () -> Unit = {},
    onFavoriteTrackClick: (FavoriteTrack) -> Unit = {},
    onFavoriteLoadMore: (Int) -> Unit = {},
    onStartLogin: () -> Unit = {},
    onCancelLogin: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistEntity?>(null) }

    if (favoriteContent != null) {
        FavoriteDetail(
            state = favoriteContent,
            onClose = onCloseFavorite,
            onTrackClick = onFavoriteTrackClick,
            onLoadMore = onFavoriteLoadMore,
            modifier = modifier,
        )
        return
    }

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
        FavoriteSection(
            folders = favoriteFolders,
            sync = favoriteSync,
            loginState = loginState,
            session = session,
            onSync = onSyncFavorites,
            onOpen = onOpenFavorite,
            onStartLogin = onStartLogin,
            onCancelLogin = onCancelLogin,
        )

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

/**
 * 收藏夹分区（M11-B）。
 *
 * 三种状态，如实呈现，不静默：
 * - 未登录：内嵌 [LoginPanel] 引导扫码（复用设置页同一组件，流程一致）
 * - 已登录但目录空：同步按钮 / 同步失败可重试
 * - 已登录有目录：目录行（封面 + 名称 + 条目数），点入拉内容
 */
@Composable
private fun FavoriteSection(
    folders: List<FavoriteFolderEntity>,
    sync: SyncState,
    loginState: LoginState,
    session: BiliSession,
    onSync: () -> Unit,
    onOpen: (Long, String) -> Unit,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("收藏夹", style = MaterialTheme.typography.titleMedium)
            when (sync) {
                is SyncState.Syncing -> {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                is SyncState.Error -> {
                    TextButton(onClick = onSync) { Text("重试") }
                }
                is SyncState.NotLoggedIn -> Unit
                is SyncState.Idle -> {
                    if (session.isLoggedIn && folders.isNotEmpty()) {
                        TextButton(onClick = onSync) { Text("同步") }
                    }
                }
            }
        }

        if (!session.isLoggedIn) {
            // 登录引导：登录非强制，只有收藏夹/动态这类功能需要账号
            LoginPanel(
                loginState = loginState,
                session = session,
                onStart = onStartLogin,
                onCancel = onCancelLogin,
                onLogout = {},
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            return@Column
        }

        if (folders.isEmpty()) {
            when (sync) {
                is SyncState.Syncing -> Unit // 标题行已有转圈
                is SyncState.Error -> {
                    Text(
                        "同步失败：${sync.message}",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> {
                    Text(
                        "还没有收藏夹，点右上角「同步」从 B 站拉取",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider()
            return@Column
        }

        folders.forEach { folder ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(folder.mediaId, folder.title) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverThumbnail(folder.coverUrl, width = 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        folder.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${folder.mediaCount} 个 · ${formatRelativeTime(folder.syncedAt)}同步",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider()
        }
    }
}

/** 收藏夹内容页：分页拉取，滚动到底自动加载下一页。 */
@Composable
private fun FavoriteDetail(
    state: RemoteListState<FavoriteTrack>,
    onClose: () -> Unit,
    onTrackClick: (FavoriteTrack) -> Unit,
    onLoadMore: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${state.title} · ${state.items.size} 首",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onClose) { Text("返回") }
        }

        when {
            state.isLoading && state.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.errorMessage != null && state.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                        Button(onClick = { onLoadMore(1) }) { Text("重试") }
                    }
                }
            }

            state.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("这个收藏夹是空的", style = MaterialTheme.typography.bodySmall)
                }
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.items, key = { it.bvid }) { track ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onTrackClick(track) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverThumbnail(track.coverUrl, width = 96.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    track.author + "  " + formatDuration(track.durationSec * 1000),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        HorizontalDivider()
                    }

                    // 滚动到底自动加载下一页；失败时给重试，不静默
                    if (state.hasMore || state.isLoadingMore) {
                        item(key = "footer") {
                            if (state.hasMore && !state.isLoadingMore) {
                                LaunchedEffect(state.page) { onLoadMore(state.page + 1) }
                            }
                            Box(
                                Modifier.fillMaxWidth().padding(12.dp),
                                Alignment.Center,
                            ) {
                                if (state.isLoadingMore) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    state.errorMessage?.let { msg ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                msg,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                            TextButton(onClick = { onLoadMore(state.page + 1) }) {
                                                Text("重试")
                                            }
                                        }
                                    } ?: Text(
                                        "加载中…",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
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
