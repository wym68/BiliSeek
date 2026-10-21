package com.biliseek.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.biliseek.app.ui.components.InputDialog
import com.biliseek.app.hint.HintKey
import com.biliseek.app.ui.components.SwipeToRemoveRow
import com.biliseek.app.ui.components.miniBarContentPadding
import com.biliseek.designsystem.component.BsConfirmDialog
import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.component.BsErrorState
import com.biliseek.designsystem.component.BsEmptyState
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import com.biliseek.designsystem.component.BsTopBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.width
import com.biliseek.designsystem.component.BsListRow
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.biliseek.app.data.PlaylistEntity
import com.biliseek.app.data.PlaylistTrack
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.data.reorderNeighbors
import com.biliseek.app.data.FavoriteFolderEntity
import com.biliseek.app.data.TrackListId
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

@Composable
fun LibraryScreen(
    playlists: List<PlaylistEntity>,
    openedPlaylist: PlaylistEntity?,
    tracks: List<PlaylistTrack>,
    /** 新建本地列表（名字, 图标 key）。 */
    onCreate: (String, String) -> Unit,
    onOpen: (PlaylistEntity) -> Unit,
    onClose: () -> Unit,
    onDelete: (Long) -> Unit,
    /** 改本地列表的名字和图标（id, 名字, 图标 key）。 */
    onEdit: (Long, String, String) -> Unit,
    onTrackClick: (PlaylistTrack) -> Unit,
    /** 从开着的本地列表移除一条（右滑 / 三点菜单「删除」，可撤销）。 */
    onTrackRemove: (PlaylistTrack) -> Unit,
    onTrackReorder: (Long, Double?, Double?) -> Unit,
    /** 已下载任务列表。非空时显示入口——下载中/失败也要能进得去（可见可重试）。 */
    downloads: List<DownloadTask> = emptyList(),
    onOpenDownloads: () -> Unit = {},
    /** 本机 AI 总结的视频数。大于 0 时显示入口。 */
    summaryCount: Int = 0,
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
    /** 从当前打开的收藏夹移除一条（右滑 / 三点菜单「删除」，可撤销）。 */
    onRemoveFavoriteTrack: (FavoriteTrack) -> Unit = {},
    onStartLogin: () -> Unit = {},
    onCancelLogin: () -> Unit = {},
    // ── 稍后再看 ─────────────────────────────────
    watchLaterContent: RemoteListState<com.biliseek.core.network.dto.WatchLaterItem>? = null,
    onOpenWatchLater: () -> Unit = {},
    onCloseWatchLater: () -> Unit = {},
    onWatchLaterRetry: () -> Unit = {},
    onWatchLaterItemClick: (com.biliseek.core.network.dto.WatchLaterItem) -> Unit = {},
    /** 从稍后再看移除一条（右滑 / 三点菜单「删除」，可撤销）。 */
    onRemoveWatchLaterItem: (com.biliseek.core.network.dto.WatchLaterItem) -> Unit = {},
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
    /** 取消订阅当前打开的合集。 */
    onUnsubscribeSeries: () -> Unit = {},
    /** 收藏夹 / 稍后再看 / 合集内容页里视频行三点菜单的动作，与搜索结果一致。 */
    videoActions: VideoMenuActions = VideoMenuActions(),
    modifier: Modifier = Modifier,
) {
    var showCreate by remember { mutableStateOf(false) }
    var playlistsExpanded by rememberSaveable { mutableStateOf(true) }
    if (favoriteContent != null) {
        BackHandler(onBack = onCloseFavorite)
        FavoriteDetail(
            state = favoriteContent,
            onClose = onCloseFavorite,
            onTrackClick = onFavoriteTrackClick,
            onLoadMore = onFavoriteLoadMore,
            onEditFolder = onEditFavoriteFolder,
            onDeleteFolder = { mediaId ->
                // 先回到列表：删除要逐个移出视频再删文件夹，可能要一会儿，做完会提示
                onCloseFavorite()
                onDeleteFavoriteFolder(mediaId)
            },
            onRemoveTrack = onRemoveFavoriteTrack,
            modifier = modifier,
            videoActions = videoActions,
        )
        return
    }

    if (openedPlaylist != null) {
        BackHandler(onBack = onClose)
        PlaylistDetail(
            playlist = openedPlaylist,
            tracks = tracks,
            onClose = onClose,
            onTrackClick = onTrackClick,
            onTrackRemove = onTrackRemove,
            onTrackReorder = onTrackReorder,
            onEdit = onEdit,
            onDelete = { id ->
                onClose()
                onDelete(id)
            },
            modifier = modifier,
            videoActions = videoActions,
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
            onUnsubscribe = onUnsubscribeSeries,
            modifier = modifier,
            videoActions = videoActions,
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
            onRemove = onRemoveWatchLaterItem,
            onStartLogin = onStartLogin,
            onCancelLogin = onCancelLogin,
            modifier = modifier,
            videoActions = videoActions,
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
                        "下载中 ${downloads.count { it.isActive }} 项",
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
                    contentPadding = miniBarContentPadding(top = 8.dp, bottom = 8.dp),
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
                                        "${downloads.count { it.isDone }} 项",
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
                                        // 只写条数；占用空间在 AI 总结页里看（2026-10-05 用户要求列表页不再标）
                                        "$summaryCount 项",
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
                            // 和收藏夹列表一样：整行点开内页，编辑 / 删除在内页标题右侧
                            items(playlists, key = { it.id }) { playlist ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpen(playlist) }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 与「稍后再看」行的图标同色（灰黑），不用主色，免得一排蓝图标太显眼
                                    Icon(
                                        playlistIcon(playlist.icon),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        playlist.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
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

            LibraryTab.FAVORITES -> {
                // 收藏夹管理：这里只有新建；编辑 / 删除在进入收藏夹后的标题右侧（FavoriteDetail）
                var creatingFavorite by remember { mutableStateOf(false) }
                var favoriteTitle by remember { mutableStateOf("") }
                var favoriteIntro by remember { mutableStateOf("") }
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
                        contentPadding = miniBarContentPadding(top = 8.dp, bottom = 8.dp),
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
                                                "还没有收藏夹，点上方「新建收藏夹」或下拉刷新",
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
                                            .clickable { onOpenFavorite(folder.mediaId, folder.title) }
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
                                                "${folder.mediaCount} 项 · ${formatRelativeTime(folder.syncedAt)}同步",
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
                    InputDialog(
                        title = "新建收藏夹",
                        confirmLabel = "创建",
                        confirmEnabled = favoriteTitle.isNotBlank(),
                        onConfirm = {
                            creatingFavorite = false
                            onCreateFavoriteFolder(favoriteTitle, favoriteIntro)
                            favoriteTitle = ""
                            favoriteIntro = ""
                        },
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
                        contentPadding = miniBarContentPadding(top = 8.dp, bottom = 8.dp),
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
                                        val loadMoreError = collectedListState.errorMessage
                                        when {
                                            collectedListState.isLoadingMore ->
                                                CircularProgressIndicator(modifier = Modifier.size(24.dp))

                                            shouldAutoLoadMore(
                                                collectedListState.hasMore,
                                                collectedListState.isLoadingMore,
                                                loadMoreError,
                                            ) -> LaunchedEffect(Unit) {
                                                onCollectedLoadMore(collectedListState.page + 1)
                                            }

                                            // 失败后不自动再来（否则会一直重打 B 站），等用户点「重试」
                                            loadMoreError != null ->
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(loadMoreError, style = MaterialTheme.typography.labelSmall)
                                                    TextButton(onClick = { onCollectedLoadMore(collectedListState.page + 1) }) {
                                                        Text("重试")
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
    }

    if (showCreate) {
        PlaylistEditDialog(
            title = "新建歌单",
            confirmLabel = "创建",
            onConfirm = { name, icon ->
                showCreate = false
                onCreate(name, icon)
            },
            onDismiss = { showCreate = false },
        )
    }
}

/**
 * 本地列表内页，和收藏夹内页同一套（设计稿：docs/superpowers/specs/2026-10-04-local-list-like-favorites-design.md）：
 * 标题前是列表图标，右侧「编辑」「删除」；条目右滑或三点菜单「删除」从列表移除（可撤销）；长按条目拖动排序。
 *
 * 长按拖动与右滑删除不冲突：直接横滑时长按还没触发就超出触摸阈值，交给右滑；
 * 按住不动等长按触发后再拖，拖动事件被排序消费，右滑看到已消费的事件不会启动。
 *
 * 拖动排序（设计稿：2026-10-04-playlist-reorder）：拿起的那一行变成浮起卡片、三点换成拖动手柄并震一下；
 * 拖动时别的行滑开让位，空出来的格子就是落点。状态与手势见 [DragReorderState]。
 */
@Composable
private fun PlaylistDetail(
    playlist: PlaylistEntity,
    tracks: List<PlaylistTrack>,
    onClose: () -> Unit,
    onTrackClick: (PlaylistTrack) -> Unit,
    onTrackRemove: (PlaylistTrack) -> Unit,
    onTrackReorder: (Long, Double?, Double?) -> Unit,
    onEdit: (Long, String, String) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** 条目右侧三点菜单的动作，与搜索结果一致。 */
    videoActions: VideoMenuActions = VideoMenuActions(),
) {
    val listState = rememberLazyListState()
    // 松手：按「最初位置 → 最终位置」算两侧 sortKey（取中值）写一次库
    val reorder = rememberDragReorderState(listState, tracks, keyOf = { it.track.id }) { key, from, to ->
        val (before, after) = reorderNeighbors(tracks.map { it.sortKey }, from, to)
        onTrackReorder(key as Long, before, after)
    }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    // 三点菜单：当前打开菜单的那一条
    var menuFor by remember { mutableStateOf<PlaylistTrack?>(null) }
    menuFor?.let { item ->
        VideoMenuSheet(item.toSearchVideo(), videoActions, onRemove = { onTrackRemove(item) }) { menuFor = null }
    }

    if (editing) {
        PlaylistEditDialog(
            title = "编辑歌单",
            confirmLabel = "保存",
            initialName = playlist.name,
            initialIcon = playlist.icon,
            onConfirm = { name, icon ->
                editing = false
                onEdit(playlist.id, name, icon)
            },
            onDismiss = { editing = false },
        )
    }

    if (deleting) {
        BsConfirmDialog(
            title = "删除歌单",
            text = "确定删除「${playlist.name}」？列表里的条目会一起移除，视频本身不受影响。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                deleting = false
                onDelete(playlist.id)
            },
            onDismiss = { deleting = false },
        )
    }

    Column(modifier.fillMaxSize()) {
        BsTopBar(
            title = playlist.name,
            subtitle = "${tracks.size} 首",
            onNavigate = onClose,
            titleIcon = playlistIcon(playlist.icon),
            actions = {
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑歌单")
                }
                IconButton(onClick = { deleting = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除歌单")
                }
            },
        )
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("空列表。在视频的 ⋮ 菜单里选「加入列表」", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(
                "长按拖动排序，右滑移除",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall,
            )
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = miniBarContentPadding()) {
                items(reorder.order, key = { it.track.id }) { item ->
                    val id = item.track.id
                    val lifted = reorder.draggingId == id
                    // 拖动中或刚松手正滑回空位：浮在别的行上面
                    val floating = lifted || reorder.settlingId == id
                    // 拿起 / 放下的过渡：0 = 普通行，1 = 浮起卡片
                    val lift by animateFloatAsState(if (lifted) 1f else 0f, tween(LIFT_ANIM_MS), label = "lift")
                    val scheme = MaterialTheme.colorScheme
                    // 浅色用最白的一档，深色用高一档（最低一档会和背景糊在一起），同 AppToast
                    val cardColor = if (scheme.surface.luminance() < 0.5f) {
                        scheme.surfaceContainerHigh
                    } else {
                        scheme.surfaceContainerLowest
                    }
                    Column(
                        if (floating) {
                            Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offsetOf(id) }
                        } else {
                            // 别的行在本地顺序变化时滑到新位置：这就是「让位」
                            Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                        },
                    ) {
                        // 浮起卡片：左右各缩进 8dp、圆角 12dp、阴影、放大 1.03 倍；行内边距同步减 8dp，封面位置不动
                        Box(
                            Modifier
                                .padding(horizontal = LIFT_INSET * lift)
                                .graphicsLayer {
                                    val scale = 1f + (LIFT_SCALE - 1f) * lift
                                    scaleX = scale
                                    scaleY = scale
                                    shadowElevation = LIFT_ELEVATION.toPx() * lift
                                    shape = RoundedCornerShape(LIFT_CORNER * lift)
                                    clip = lift > 0f
                                },
                        ) {
                            SwipeToRemoveRow(
                                background = lerp(scheme.background, cardColor, lift),
                                onRemove = { onTrackRemove(item) },
                                hintKey = HintKey.SWIPE_LOCAL_PLAYLIST,
                                showHint = id == reorder.order.firstOrNull()?.track?.id,
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(ROW_HEIGHT)
                                        .pointerInput(id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { reorder.start(id) },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    reorder.dragBy(amount.y)
                                                },
                                                onDragEnd = { reorder.end() },
                                                onDragCancel = { reorder.cancel() },
                                            )
                                        }
                                        .clickable { onTrackClick(item) }
                                        .padding(horizontal = 16.dp - LIFT_INSET * lift),
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
                                    if (lifted) {
                                        // 拖动中三点换成主色的拖动手柄，一眼看出在「移动」；占位与三点按钮同大
                                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Filled.DragIndicator,
                                                contentDescription = "拖动排序",
                                                tint = scheme.primary,
                                            )
                                        }
                                    } else {
                                        VideoMenuButton { menuFor = item }
                                    }
                                }
                            }
                        }
                        // 浮起时分割线留位置但不画，免得跟着卡片飘
                        HorizontalDivider(color = if (floating) Color.Transparent else DividerDefaults.color)
                    }
                }
            }
        }
    }
}



/**
 * 收藏夹内容页：分页拉取，滚动到底自动加载下一页。
 * 标题右侧「编辑」「删除」管这个收藏夹；条目右滑或三点菜单「删除」从这个收藏夹移除（可撤销）。
 * [readOnly] 时只能看、播。
 */
@Composable
internal fun FavoriteDetail(
    state: RemoteListState<FavoriteTrack>,
    onClose: () -> Unit,
    onTrackClick: (FavoriteTrack) -> Unit,
    onLoadMore: (Int) -> Unit,
    onEditFolder: (Long, String) -> Unit,
    onDeleteFolder: (Long) -> Unit,
    onRemoveTrack: (FavoriteTrack) -> Unit,
    modifier: Modifier = Modifier,
    /** 视频行右侧三点菜单的动作，与搜索结果一致。 */
    videoActions: VideoMenuActions = VideoMenuActions(),
    /** 别人的公开收藏夹（首页「视频播客」）：没有编辑 / 删除收藏夹、右滑移除、菜单里的「移除」。 */
    readOnly: Boolean = false,
) {
    val mediaId = (state.id as? TrackListId.Favorite)?.mediaId?.takeIf { !readOnly }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    // 三点菜单：当前打开菜单的那一条
    var menuFor by remember { mutableStateOf<FavoriteTrack?>(null) }
    menuFor?.let { track ->
        VideoMenuSheet(
            track.toSearchVideo(),
            videoActions,
            onRemove = if (readOnly) null else ({ onRemoveTrack(track) }),
        ) { menuFor = null }
    }

    if (editing && mediaId != null) {
        var editTitle by remember { mutableStateOf(state.title) }
        InputDialog(
            title = "编辑收藏夹",
            confirmLabel = "保存",
            confirmEnabled = editTitle.isNotBlank(),
            onConfirm = {
                editing = false
                onEditFolder(mediaId, editTitle)
            },
            onDismiss = { editing = false },
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

    if (deleting && mediaId != null) {
        BsConfirmDialog(
            title = "删除收藏夹",
            text = "确定删除「${state.title}」？收藏夹内的视频不会被删除。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                deleting = false
                onDeleteFolder(mediaId)
            },
            onDismiss = { deleting = false },
        )
    }

    Column(modifier.fillMaxSize()) {
        BsTopBar(
            title = state.title,
            subtitle = "${state.items.size} 首",
            onNavigate = onClose,
            actions = {
                if (mediaId != null) {
                    IconButton(onClick = { editing = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑收藏夹")
                    }
                    IconButton(onClick = { deleting = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除收藏夹")
                    }
                }
            },
        )

        when {
            state.isLoading && state.items.isEmpty() -> BsLoadingState()

            state.errorMessage != null && state.items.isEmpty() ->
                BsErrorState(state.errorMessage, onRetry = { onLoadMore(1) })

            state.items.isEmpty() -> BsEmptyState("这个收藏夹是空的", icon = Icons.Outlined.StarBorder)

            else -> {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = miniBarContentPadding()) {
                    items(state.items, key = { it.bvid }) { track ->
                        SwipeToRemoveRow(
                            background = MaterialTheme.colorScheme.background,
                            onRemove = { onRemoveTrack(track) },
                            enabled = !readOnly,
                            hintKey = HintKey.SWIPE_FAVORITE,
                            showHint = track.bvid == state.items.firstOrNull()?.bvid,
                        ) {
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
                                VideoMenuButton { menuFor = track }
                            }
                        }
                        HorizontalDivider()
                    }

                    // 滚动到底自动加载下一页；失败时给重试，不静默
                    if (state.hasMore || state.isLoadingMore) {
                        item(key = "footer") {
                            // 跟条数走：删过条目后会重取同一页（页码不变）。
                            // 失败后不自动再来（否则会一直重打 B 站），等用户点「重试」
                            if (shouldAutoLoadMore(state.hasMore, state.isLoadingMore, state.errorMessage)) {
                                LaunchedEffect(state.items.size) { onLoadMore(state.page + 1) }
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

// 拖动排序时拿起的那一行（设计稿：2026-10-04-playlist-reorder）
private val LIFT_INSET = 8.dp
private val LIFT_CORNER = 12.dp
private val LIFT_ELEVATION = 8.dp
private const val LIFT_SCALE = 1.03f
private const val LIFT_ANIM_MS = 150
