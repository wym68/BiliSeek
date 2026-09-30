package com.biliseek.app.ui

import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.component.BsErrorState
import com.biliseek.designsystem.component.BsEmptyState
import androidx.compose.material.icons.outlined.StarBorder
import com.biliseek.designsystem.component.BsTopBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.width
import com.biliseek.designsystem.component.BsListRow
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.biliseek.app.data.CollectedListLoadState
import com.biliseek.app.SyncState
import com.biliseek.app.FavoriteTrack
import com.biliseek.app.LoginPanel
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.LoginState
import com.biliseek.core.network.dto.SeriesArchive
import com.biliseek.core.network.dto.CollectedItem
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.player.formatDuration
import com.biliseek.app.download.DownloadTask
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
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
    downloads: List<DownloadTask> = emptyList(),
    onOpenDownloads: () -> Unit = {},
    /** 本机 AI 总结：视频数与占用字节。视频数大于 0 时显示入口。 */
    summaryCount: Int = 0,
    summaryBytes: Long = 0,
    onOpenSummaries: () -> Unit = {},
    // ── 收藏夹（M11-B）──────────────────────────────
    favoriteFolders: List<FavoriteFolderEntity> = emptyList(),
    favoriteSync: SyncState = SyncState.Idle,
    favoriteContent: RemoteListState<FavoriteTrack>? = null,
    loginState: LoginState = LoginState.Idle,
    session: BiliSession = BiliSession.EMPTY,
    onSyncFavorites: () -> Unit = {},
    onOpenFavorite: (Long, String) -> Unit = { _, _ -> },
    onCloseFavorite: () -> Unit = {},
    /** 新建 B 站收藏夹（标题, 简介）。 */
    onCreateFavoriteFolder: (String, String) -> Unit = { _, _ -> },
    onEditFavoriteFolder: (Long, String) -> Unit = { _, _ -> },
    onDeleteFavoriteFolder: (Long) -> Unit = {},
    onFavoriteTrackClick: (FavoriteTrack) -> Unit = {},
    onFavoriteLoadMore: (Int) -> Unit = {},
    onStartLogin: () -> Unit = {},
    onCancelLogin: () -> Unit = {},
    // ── 稍后再看 ─────────────────────────────────
    watchLaterContent: RemoteListState<com.biliseek.core.network.dto.WatchLaterItem>? = null,
    onOpenWatchLater: () -> Unit = {},
    onCloseWatchLater: () -> Unit = {},
    onWatchLaterRetry: () -> Unit = {},
    onWatchLaterItemClick: (com.biliseek.core.network.dto.WatchLaterItem) -> Unit = {},
    // ── 合集（M13-N）──────────────────────────────
    selectedTab: LibraryTab = LibraryTab.PLAYLISTS,
    onTabSelected: (LibraryTab) -> Unit = {},
    openedSeries: RemoteListState<SeriesArchive>? = null,
    onCloseSeries: () -> Unit = {},
    collectedList: List<CollectedItem> = emptyList(),
    collectedListState: CollectedListLoadState = CollectedListLoadState(),
    onOpenSeries: (Long, String) -> Unit = { _, _ -> },
    onCollectedLoadMore: (Int) -> Unit = {},
    onSeriesLoadMore: (Int) -> Unit = {},
    onPlaySeriesArchive: (SeriesArchive) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistEntity?>(null) }
    var playlistsExpanded by rememberSaveable { mutableStateOf(true) }
    if (favoriteContent != null) {
        BackHandler(onBack = onCloseFavorite)
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
        BackHandler(onBack = onClose)
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

    if (openedSeries != null) {
        BackHandler(onBack = onCloseSeries)
        SeriesScreen(
            state = openedSeries,
            onClose = onCloseSeries,
            onItemClick = onPlaySeriesArchive,
            onLoadMore = onSeriesLoadMore,
            modifier = modifier,
        )
        return
    }

    if (watchLaterContent != null) {
        BackHandler(onBack = onCloseWatchLater)
        WatchLaterScreen(
            state = watchLaterContent,
            session = session,
            loginState = loginState,
            onClose = onCloseWatchLater,
            onRetry = onWatchLaterRetry,
            onItemClick = onWatchLaterItemClick,
            onStartLogin = onStartLogin,
            onCancelLogin = onCancelLogin,
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        // 顶部栏：标题 + 下载入口
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "播放列表",
                style = MaterialTheme.typography.titleLarge,
            )
            if (downloads.any { it.isActive }) {
                Row(
                    Modifier.clickable(onClick = onOpenDownloads),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "下载中 ${downloads.count { it.isActive }} 个",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(BsSize.iconSm),
                    )
                }
            }
        }

        // Tab 行
        PrimaryScrollableTabRow(
            selectedTabIndex = selectedTab.ordinal,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            LibraryTab.entries.forEach { tab ->
                // 图标在文字上方（带 icon + text 的 Tab 自动用 72dp 行高）
                Tab(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    text = { Text(tab.label) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                )
            }
        }

        // Tab 内容
        when (selectedTab) {
            LibraryTab.PLAYLISTS -> {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    // 稍后再看入口（仅登录时显示）
                    if (session.isLoggedIn) {
                        item(key = "watch-later") {
                            BsListRow(
                                title = "稍后再看",
                                subtitle = "与 B 站「稍后再看」同步",
                                leadingIcon = Icons.Outlined.Schedule,
                                onClick = onOpenWatchLater,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }

                    item(key = "playlist-header") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier.weight(1f).clickable { playlistsExpanded = !playlistsExpanded },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("本地列表", style = MaterialTheme.typography.titleSmall)
                                Icon(
                                    if (playlistsExpanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = if (playlistsExpanded) "收起" else "展开",
                                    modifier = Modifier.size(BsSize.iconSm),
                                )
                            }
                            FilledTonalButton(onClick = { showCreate = true }) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(BsSize.iconSm))
                                Spacer(Modifier.width(BsSpacing.xs))
                                Text("新建")
                            }
                        }
                    }

                    if (downloads.isNotEmpty()) {
                        item(key = "downloads-entry") {
                            BsListRow(
                                title = "已下载",
                                leadingIcon = Icons.Outlined.DownloadDone,
                                onClick = onOpenDownloads,
                                trailing = {
                                    Text(
                                        "${downloads.count { it.isDone }} 个",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }

                    if (summaryCount > 0) {
                        item(key = "summaries-entry") {
                            BsListRow(
                                title = "AI 总结",
                                subtitle = "存在本机，可查看与清理",
                                leadingIcon = Icons.Outlined.AutoAwesome,
                                onClick = onOpenSummaries,
                                trailing = {
                                    Text(
                                        "$summaryCount 个 · ${com.biliseek.app.summary.SummaryLibraryPolicy.formatSize(summaryBytes)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }

                    if (playlistsExpanded) {
                        if (playlists.isEmpty()) {
                            item(key = "empty-playlist") {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                    Alignment.Center,
                                ) {
                                    Text("还没有列表，新建一个开始收藏", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else {
                            items(playlists, key = { it.id }) { playlist ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpen(playlist) }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
                                    Row {
                                        TextButton(onClick = { renaming = playlist }) { Text("改名") }
                                        TextButton(onClick = { onDelete(playlist.id) }) { Text("删除") }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }

            LibraryTab.FAVORITES -> {
                // 收藏夹管理：新建 / 长按菜单 / 编辑 / 删除确认
                var creatingFavorite by remember { mutableStateOf(false) }
                var favoriteTitle by remember { mutableStateOf("") }
                var favoriteIntro by remember { mutableStateOf("") }
                var folderActionsFor by remember { mutableStateOf<FavoriteFolderEntity?>(null) }
                var editingFavorite by remember { mutableStateOf<Pair<Long, String>?>(null) }
                var deletingFavorite by remember { mutableStateOf<Pair<Long, String>?>(null) }
                // 收藏夹目录没有单独的加载占位（空列表同步中什么都不画），转圈要一直跟着同步走
                PullRefreshContainer(
                    isRefreshing = favoriteSync is SyncState.Syncing,
                    errorMessage = (favoriteSync as? SyncState.Error)?.message,
                    successText = "收藏夹已刷新",
                    onRefresh = onSyncFavorites,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        if (!session.isLoggedIn) {
                            item(key = "favorite-login") {
                                LoginPanel(
                                    loginState = loginState,
                                    session = session,
                                    onStart = onStartLogin,
                                    onCancel = onCancelLogin,
                                    onLogout = {},
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        } else {
                            item(key = "favorite-create") {
                                TextButton(
                                    onClick = { creatingFavorite = true },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(BsSize.iconSm))
                                    Spacer(Modifier.width(BsSpacing.xs))
                                    Text("新建收藏夹")
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            if (favoriteFolders.isEmpty()) {
                                item(key = "favorite-empty") {
                                    when (favoriteSync) {
                                        is SyncState.Syncing -> Unit
                                        is SyncState.Error -> {
                                            Text(
                                                "同步失败：${favoriteSync.message}",
                                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        else -> {
                                            Text(
                                                "还没有收藏夹，点上方「新建收藏夹」或右上角「同步」",
                                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            } else {
                                items(favoriteFolders, key = { "favorite-${it.mediaId}" }) { folder ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { onOpenFavorite(folder.mediaId, folder.title) },
                                                onLongClick = { folderActionsFor = folder },
                                            )
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }

                // 新建收藏夹
                if (creatingFavorite) {
                    AlertDialog(
                        onDismissRequest = { creatingFavorite = false },
                        title = { Text("新建收藏夹") },
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
                                onClick = {
                                    creatingFavorite = false
                                    onCreateFavoriteFolder(favoriteTitle, favoriteIntro)
                                    favoriteTitle = ""
                                    favoriteIntro = ""
                                },
                                enabled = favoriteTitle.isNotBlank(),
                            ) { Text("创建") }
                        },
                        dismissButton = {
                            TextButton(onClick = { creatingFavorite = false }) { Text("取消") }
                        },
                    )
                }

                // 长按菜单：编辑 / 删除
                folderActionsFor?.let { folder ->
                    ActionListSheet(
                        title = folder.title,
                        items = listOf(
                            "编辑" to {
                                folderActionsFor = null
                                editingFavorite = folder.mediaId to folder.title
                            },
                            "删除" to {
                                folderActionsFor = null
                                deletingFavorite = folder.mediaId to folder.title
                            },
                        ),
                        onDismiss = { folderActionsFor = null },
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
                                onClick = {
                                    editingFavorite = null
                                    onEditFavoriteFolder(mediaId, editTitle)
                                },
                                enabled = editTitle.isNotBlank(),
                            ) { Text("保存") }
                        },
                        dismissButton = {
                            TextButton(onClick = { editingFavorite = null }) { Text("取消") }
                        },
                    )
                }

                // 删除收藏夹确认
                deletingFavorite?.let { (mediaId, title) ->
                    AlertDialog(
                        onDismissRequest = { deletingFavorite = null },
                        title = { Text("删除收藏夹") },
                        text = { Text("确定删除「$title」？收藏夹内的视频不会被删除。") },
                        confirmButton = {
                            TextButton(onClick = {
                                deletingFavorite = null
                                onDeleteFavoriteFolder(mediaId)
                            }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { deletingFavorite = null }) { Text("取消") }
                        },
                    )
                }
            }

            LibraryTab.SERIES -> {
                PullRefreshContainer(
                    isRefreshing = collectedListState.isLoading && collectedList.isNotEmpty(),
                    errorMessage = collectedListState.errorMessage,
                    successText = "合集已刷新",
                    onRefresh = { onCollectedLoadMore(1) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        if (!session.isLoggedIn) {
                            item(key = "series-login") {
                                LoginPanel(
                                    loginState = loginState,
                                    session = session,
                                    onStart = onStartLogin,
                                    onCancel = onCancelLogin,
                                    onLogout = {},
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        } else if (collectedListState.isLoading && collectedList.isEmpty()) {
                            item(key = "series-loading") {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                    Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else if (collectedListState.errorMessage != null && collectedList.isEmpty()) {
                            item(key = "series-error") {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                    Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            collectedListState.errorMessage!!,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                        Button(
                                            onClick = { onCollectedLoadMore(1) },
                                            modifier = Modifier.padding(top = 8.dp),
                                        ) {
                                            Text("重试")
                                        }
                                    }
                                }
                            }
                        } else if (collectedList.isEmpty()) {
                            item(key = "series-empty") {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                    Alignment.Center,
                                ) {
                                    Text("还没有订阅合集", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else {
                            items(collectedList, key = { it.id }) { item ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenSeries(item.id, item.title) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CoverThumbnail(normalizeCoverUrl(item.cover), width = 72.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            item.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Text(
                                                item.upper.name,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                "·",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                "${item.media_count} 首",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            if (collectedListState.hasMore) {
                                item(key = "series-load-more") {
                                    Box(
                                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        Alignment.Center,
                                    ) {
                                        if (collectedListState.isLoadingMore) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        } else {
                                            LaunchedEffect(Unit) {
                                                onCollectedLoadMore(collectedListState.page + 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
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
        BsTopBar(title = playlist.name, subtitle = "${tracks.size} 首", onNavigate = onClose)
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("空列表。在搜索结果里长按可加入", style = MaterialTheme.typography.bodySmall)
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
        BsTopBar(title = state.title, subtitle = "${state.items.size} 首", onNavigate = onClose)

        when {
            state.isLoading && state.items.isEmpty() -> BsLoadingState()

            state.errorMessage != null && state.items.isEmpty() ->
                BsErrorState(state.errorMessage, onRetry = { onLoadMore(1) })

            state.items.isEmpty() -> BsEmptyState("这个收藏夹是空的", icon = Icons.Outlined.StarBorder)

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