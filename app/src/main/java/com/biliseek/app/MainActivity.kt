package com.biliseek.app

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.biliseek.app.ui.AppShell
import com.biliseek.app.ui.DayHistoryScreen
import com.biliseek.app.ui.Destination
import com.biliseek.app.ui.DownloadDialog
import com.biliseek.app.ui.toDownloadTarget
import com.biliseek.app.toSearchVideo
import com.biliseek.app.ui.DownloadsScreen
import com.biliseek.app.ui.DynamicScreen
import com.biliseek.app.ui.HomeEmptyState
import com.biliseek.app.ui.LibraryScreen
import com.biliseek.app.ui.LibraryTab
import com.biliseek.app.ui.MeteredVideoDialog
import com.biliseek.app.ui.PlaylistPickerDialog
import com.biliseek.app.ui.PlayerExpansion
import com.biliseek.app.ui.SettingsScreen
import com.biliseek.app.ui.UpSpaceScreen
import com.biliseek.app.ui.WatchLaterScreen
import com.biliseek.core.network.sharedTextFrom
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.player.PresentationMode
import com.biliseek.designsystem.theme.BiliSeekTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    /** 记录进入 PiP 前的呈现态，退出 PiP 时恢复。 */
    private var wasInPip = false

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不阻断 */ }

    /** API 26–28 写公共媒体库需要运行时权限；29+ 走 MediaStore 不需要。 */
    private val requestStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingStorageDownload?.let { req ->
                if (granted) viewModel.enqueueDownload(req)
                // 拒绝时任务不入队——界面上的失败提示由用户自己决定重试
            }
            pendingStorageDownload = null
        }

    /** 等权限就绪后入队的下载（仅 API 26–28 + 公共媒体库路径）。 */
    private var pendingStorageDownload: com.biliseek.app.download.DownloadRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        handleSharedIntent(intent)

        setContent {
            BiliSeekTheme {
                val playback by viewModel.connection.state.collectAsState()
                val search by viewModel.search.collectAsState()
                val loginState by viewModel.loginState.collectAsState()
                val session by viewModel.session.collectAsState()
                val playlists by viewModel.playlists.collectAsState()
                val openedPlaylist by viewModel.openedPlaylist.collectAsState()
                val openedTracks by viewModel.openedTracks.collectAsState()
                val searchHistory by viewModel.searchHistory.collectAsState()
                val heatmap by viewModel.heatmap.collectAsState()
                val favoriteFolders by viewModel.favoriteFolders.collectAsState()
                val favoriteSync by viewModel.favoriteSync.collectAsState()
                val openedFavorite by viewModel.openedFavorite.collectAsState()
                val dynamic by viewModel.dynamic.collectAsState()
                val upSpace by viewModel.upSpace.collectAsState()
                val dayHistoryDate by viewModel.dayHistoryDate.collectAsState()
                val dayHistory by viewModel.dayHistory.collectAsState()
                val watchLater by viewModel.watchLater.collectAsState()
                val pendingBatchAdd by viewModel.pendingBatchAdd.collectAsState()
                val selectionMode by viewModel.selectionMode.collectAsState()
                val selectedBvids by viewModel.selectedBvids.collectAsState()
                val multiPagePicker by viewModel.multiPagePicker.collectAsState()
                // 多 P 面板 / UP 空间投稿的复选作用域（与搜索结果复选互斥）
                val pageSelectionActive by viewModel.pageSelection.active.collectAsState()
                val selectedPageKeys by viewModel.pageSelection.selected.collectAsState()
                val upSelectionActive by viewModel.upSelection.active.collectAsState()
                val selectedUpKeys by viewModel.upSelection.selected.collectAsState()
                val commentDialog by viewModel.commentDialog.collectAsState()
                val previewImage by viewModel.previewImage.collectAsState()
                val queueSheetOpen by viewModel.queueSheetOpen.collectAsState()
                val settings by viewModel.settings.collectAsState()
                val videoBlocked by viewModel.videoBlocked.collectAsState()
                val downloadTasks by viewModel.downloadTasks.collectAsState()
                val openedSeries by viewModel.openedSeries.collectAsState()
                val collectedList by viewModel.collectedList.collectAsState()
                val collectedListState by viewModel.collectedListState.collectAsState()

                var expansion by remember { mutableStateOf(PlayerExpansion.COLLAPSED) }
                // 长按 → 下载：先问内容与保存位置（搜索结果 / 多 P 分 P / UP 投稿共用）
                var downloadFor by remember { mutableStateOf<com.biliseek.app.ui.DownloadTarget?>(null) }
                // 程序化 tab 切换（首页快捷入口「近期歌单」→ 列表 tab）。
                val tabSwitchRequests = remember { MutableSharedFlow<Destination>(extraBufferCapacity = 1) }
                // 「已下载」分区展开态
                var downloadsOpen by remember { mutableStateOf(false) }
                // 列表 tab 选中的页
                var selectedTab by remember { mutableStateOf(LibraryTab.PLAYLISTS) }

                val context = LocalContext.current
                val surfaceView = remember {
                    SurfaceView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                }

                // 起播即展开播放器，让用户看到当前在放什么。
                // 跳转本身由 AppShell 负责（player 是 nav destination），
                // 这里只做看模式的「记住上次」默认开启。
                LaunchedEffect(Unit) {
                    viewModel.playbackStarted.collect {
                        if (viewModel.shouldAutoEnterVideo()) {
                            viewModel.requestPresentation(PresentationMode.INLINE, surfaceView)
                        }
                    }
                }

                // Ch3：批量操作成功的瞬时提示。Android Toast——简单可靠、与现有 UI 解耦。
                LaunchedEffect(Unit) {
                    viewModel.transientMessage.collect { msg ->
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }

                // 已登录且目录从未同步过 → 自动拉一次收藏夹目录（音乐库分区秒开）
                LaunchedEffect(session.isLoggedIn, favoriteFolders.isEmpty()) {
                    if (session.isLoggedIn && favoriteFolders.isEmpty() &&
                        favoriteSync !is com.biliseek.app.SyncState.Syncing &&
                        favoriteSync !is com.biliseek.app.SyncState.NotLoggedIn
                    ) {
                        viewModel.syncFavoriteFolders()
                    }
                }

                // 已登录且合集列表从未加载过 → 自动拉一次订阅合集列表
                LaunchedEffect(session.isLoggedIn) {
                    if (session.isLoggedIn && viewModel.collectedList.value.isEmpty() &&
                        viewModel.collectedListState.value.isLoading.not()
                    ) {
                        viewModel.loadCollectedList(1)
                    }
                }

                AppShell(
                    onEnterPip = { enterPip() },
                    playback = playback,
                    expansion = expansion,
                    playbackStarted = viewModel.playbackStarted,
                    tabSwitchRequests = tabSwitchRequests,
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    surfaceView = surfaceView,
                    onExpansionChange = { next ->
                        val leavingFullscreen = expansion == PlayerExpansion.FULLSCREEN
                        expansion = next
                        // 全屏与内联都属「可见」，切换本身不动播放器；但呈现态要如实同步，
                        // 否则进后台的回落判定会以为还停在内联
                        if (playback.presentation != PresentationMode.NONE) {
                            when {
                                next == PlayerExpansion.FULLSCREEN ->
                                    viewModel.applyPresentation(
                                        PresentationMode.FULLSCREEN,
                                        surfaceView,
                                    )
                                leavingFullscreen ->
                                    viewModel.applyPresentation(
                                        PresentationMode.INLINE,
                                        surfaceView,
                                    )
                            }
                        }
                    },
                    onTogglePlayPause = viewModel.connection::togglePlayPause,
                    onPresentationChange = viewModel::requestPresentation,
                    onSkipNext = viewModel::skipNext,
                    onSkipPrevious = viewModel::skipPrevious,
                    onCyclePlayMode = viewModel::cyclePlayMode,
                    onSeek = viewModel::seekTo,
                    onSetSpeed = viewModel::setSpeed,
                    onSetSleepTimer = viewModel::setSleepTimer,
                    onRetryPlayback = viewModel::retryPlayback,
                    onQueueSkipTo = viewModel::queueSkipTo,
                    onOpenQueue = viewModel::openQueueSheet,
                    miniBarStyle = settings.miniBarStyle,
                    dynamicContent = {
                        DynamicScreen(
                            state = dynamic,
                            session = session,
                            loginState = loginState,
                            onInit = viewModel::initDynamic,
                            onRefresh = viewModel::initDynamic,
                            onTypeFilterChange = viewModel::setDynamicTypeFilter,
                            onSelectUp = viewModel::selectDynamicUp,
                            onLoadMore = viewModel::loadMoreDynamic,
                            onRetryInit = viewModel::initDynamic,
                            onEntryClick = viewModel::playDynamic,
                            onStartLogin = viewModel::startLogin,
                            onCancelLogin = viewModel::cancelLogin,
                            // Ch4
                            onOpenImagePreview = viewModel::openImagePreview,
                            onOpenRepost = { idStr ->
                                copyToClipboard(viewModel.dynamicUrl(idStr))
                                viewModel.notifyLinkCopied()
                            },
                            onOpenComment = viewModel::openCommentDialog,
                            onToggleLike = viewModel::toggleDynamicLike,
                        )
                    },
                    // UP 空间页（M11-D）：从搜索页点 UP 结果进入
                    searchContent = {
                        val dayDate = dayHistoryDate
                        if (dayDate != null) {
                            // 日历史页（M12）：从首页热力图点入，返回手势收起到首页
                            BackHandler(onBack = viewModel::closeDayHistory)
                            DayHistoryScreen(
                                date = dayDate,
                                entries = dayHistory,
                                onClose = viewModel::closeDayHistory,
                                onEntryClick = viewModel::playDayHistoryEntry,
                            )
                        } else if (upSpace.up != null) {
                            // 系统返回手势应收起空间页，而不是弹走导航栈（tab 内状态）
                            BackHandler(onBack = viewModel::closeUpSpace)
                            UpSpaceScreen(
                                state = upSpace,
                                onClose = viewModel::closeUpSpace,
                                onViewChange = viewModel::setUpSpaceView,
                                onPostClick = viewModel::playUpPost,
                                onLoadMorePosts = viewModel::loadMoreUpPosts,
                                onRetryPosts = { viewModel.loadUpPosts(1) },
                                onDynamicClick = viewModel::playDynamic,
                                onLoadMoreDynamic = viewModel::loadMoreUpDynamic,
                                // Ch4
                                onToggleFollow = viewModel::toggleRelation,
                                onOpenImagePreview = viewModel::openImagePreview,
                                onOpenRepost = { idStr ->
                                    copyToClipboard(viewModel.dynamicUrl(idStr))
                                    viewModel.notifyLinkCopied()
                                },
                                onOpenComment = viewModel::openCommentDialog,
                                onToggleLike = viewModel::toggleDynamicLike,
                                // 投稿复选与动作
                                isSelection = upSelectionActive,
                                selectedBvids = selectedUpKeys,
                                isLoggedIn = session.isLoggedIn,
                                onEnterSelection = { viewModel.enterUpSelection(it.bvid) },
                                onToggleSelection = viewModel::toggleUpSelection,
                                onSelectAll = viewModel::selectAllUp,
                                onClearSelection = viewModel::clearUpSelection,
                                onExitSelection = viewModel::exitUpSelection,
                                onAddSelectionToPlaylist = { viewModel.runUpBatch(com.biliseek.app.PendingAction.AddToPlaylist) },
                                onAppendSelectionToQueue = { viewModel.runUpBatch(com.biliseek.app.PendingAction.AppendToQueue) },
                                onEnqueueSelectionNext = { viewModel.runUpBatch(com.biliseek.app.PendingAction.EnqueueNext) },
                                onDownloadSelection = { viewModel.runUpBatch(com.biliseek.app.PendingAction.Download) },
                                onPostAddToPlaylist = { viewModel.startUpSingleAction(it, com.biliseek.app.PendingAction.AddToPlaylist) },
                                onPostAppendToQueue = { viewModel.startUpSingleAction(it, com.biliseek.app.PendingAction.AppendToQueue) },
                                onPostPlayNext = { viewModel.startUpSingleAction(it, com.biliseek.app.PendingAction.EnqueueNext) },
                                onPostCopyLink = { copyToClipboard(viewModel.upVideoUrl(it)) },
                                onPostDownload = { downloadFor = it.toSearchVideo().toDownloadTarget() },
                            )
                        } else {
                        // 首页空状态：无搜索、无结果时展示用户自己的行为数据
                        val showEmptyState = search.items.isEmpty() &&
                            search.keyword.isBlank() &&
                            search.error == null &&
                            search.pages.isEmpty()
                        SearchScreen(
                            state = search,
                            onKeywordChange = viewModel::onKeywordChange,
                            onSubmit = viewModel::submitSearch,
                            onResultClick = viewModel::onResultClick,
                            onAddToPlaylist = { viewModel.startSingleAction(it, com.biliseek.app.PendingAction.AddToPlaylist) },
                            // 加入队列 = 追加到播放队列末尾（多 P 走挑 P 页，默认全选）
                            onAppendToQueue = { viewModel.startSingleAction(it, com.biliseek.app.PendingAction.AppendToQueue) },
                            onPlayNext = { viewModel.startSingleAction(it, com.biliseek.app.PendingAction.EnqueueNext) },
                            onCopyLink = { video -> copyToClipboard(viewModel.videoUrl(video)) },
                            onShowPages = viewModel::showPages,
                            onDownload = { downloadFor = it.toDownloadTarget() },
                            onLoadMore = viewModel::loadMore,
                            onPageClick = { viewModel.playPage(search.pagesOfBvid, it) },
                            onDismissPages = viewModel::dismissPages,
                            // 多 P 面板：复选与动作
                            isPageSelection = pageSelectionActive,
                            selectedPageCids = selectedPageKeys.mapNotNull { it.toLongOrNull() }.toSet(),
                            onEnterPageSelection = { viewModel.enterPageSelection(it.cid) },
                            onTogglePageSelection = viewModel::togglePageSelection,
                            onSelectAllPages = viewModel::selectAllPages,
                            onClearPageSelection = viewModel::clearPageSelection,
                            onExitPageSelection = viewModel::exitPageSelection,
                            onAddPagesToPlaylist = { viewModel.runPageBatch(com.biliseek.app.PendingAction.AddToPlaylist) },
                            onAppendPagesToQueue = { viewModel.runPageBatch(com.biliseek.app.PendingAction.AppendToQueue) },
                            onEnqueuePagesNext = { viewModel.runPageBatch(com.biliseek.app.PendingAction.EnqueueNext) },
                            onDownloadPages = { viewModel.runPageBatch(com.biliseek.app.PendingAction.Download) },
                            onPageAddToPlaylist = { viewModel.pageSingleAction(it, com.biliseek.app.PendingAction.AddToPlaylist) },
                            onPageAppendToQueue = { viewModel.pageSingleAction(it, com.biliseek.app.PendingAction.AppendToQueue) },
                            onPagePlayNext = { viewModel.pageSingleAction(it, com.biliseek.app.PendingAction.EnqueueNext) },
                            onPageCopyLink = { copyToClipboard(viewModel.pageVideoUrl(it)) },
                            onPageDownload = {
                                downloadFor = com.biliseek.app.ui.DownloadTarget(
                                    bvid = search.pagesOfBvid,
                                    cid = it.cid,
                                    title = "P${it.page} ${it.title}".trim(),
                                    coverUrl = search.pagesCover,
                                    author = search.pagesAuthor,
                                )
                            },
                            // Ch3 复选模式
                            isSelectionMode = selectionMode is com.biliseek.app.SelectionMode.On,
                            selectedBvids = selectedBvids,
                            onEnterSelection = { viewModel.enterSelectionMode(it.bvid) },
                            onToggleSelection = viewModel::toggleSelection,
                            onSelectAll = viewModel::selectAllVisible,
                            onClearSelection = viewModel::clearSelection,
                            onExitSelection = viewModel::exitSelectionMode,
                            onAddSelectionToPlaylist = viewModel::addSelectionToPlaylist,
                            onAppendSelectionToQueue = viewModel::appendSelectionToQueue,
                            onEnqueueSelectionNext = viewModel::enqueueSelectionNext,
                            onDownloadSelection = viewModel::downloadSelection,
                            isLoggedIn = session.isLoggedIn,
                            onRetry = viewModel::retrySearch,
                            onOrderChange = { viewModel.setSearchFilters(it, search.duration) },
                            onDurationChange = { viewModel.setSearchFilters(search.order, it) },
                            onModeChange = viewModel::setSearchMode,
                            onUserClick = viewModel::openUpSpace,
                            onRetryUser = viewModel::retryUserSearch,
                            onLoadMoreUsers = viewModel::loadMoreUsers,
                            searchHistory = searchHistory,
                            onHistoryClick = viewModel::searchKeyword,
                            onDeleteHistory = viewModel::deleteSearchKeyword,
                            onClearHistory = viewModel::clearSearchHistory,
                            session = session,
                            emptyContent = {
                                if (showEmptyState) {
                                    HomeEmptyState(
                                        searchHistory = searchHistory,
                                        heatmap = heatmap,
                                        onKeywordClick = viewModel::searchKeyword,
                                        onDeleteKeyword = viewModel::deleteSearchKeyword,
                                        onClearHistory = viewModel::clearSearchHistory,
                                        onDayClick = { date -> viewModel.openDayHistory(date) },
                                        onOpenWatchLater = viewModel::openWatchLater,
                                        onOpenRecentPlaylists = { tabSwitchRequests.tryEmit(Destination.LIBRARY) },
                                        isLoggedIn = session.isLoggedIn,
                                        // 搜索历史改为聚焦时才弹出，未聚焦首页不直接显示
                                        showSearchHistory = false,
                                    )
                                }
                            },
                        )
                        }
                    },
                    libraryContent = { tab, onTab ->
                        if (downloadsOpen) {
                            // 系统返回手势应收起下载页，而不是弹走导航栈（tab 内状态）
                            BackHandler(onBack = { downloadsOpen = false })
                            DownloadsScreen(
                                tasks = downloadTasks,
                                onClose = { downloadsOpen = false },
                                onPlay = viewModel::playDownload,
                                onRetry = viewModel::retryDownload,
                                onDelete = viewModel::deleteDownload,
                            )
                        } else {
                            LibraryScreen(
                                playlists = playlists,
                                openedPlaylist = openedPlaylist,
                                tracks = openedTracks,
                                onCreate = viewModel::createPlaylist,
                                onOpen = viewModel::openPlaylist,
                                onClose = viewModel::closePlaylist,
                                onDelete = viewModel::deletePlaylist,
                                onRename = viewModel::renamePlaylist,
                                onTrackClick = viewModel::playTrack,
                                onTrackRemove = viewModel::removeFromPlaylist,
                                onTrackReorder = viewModel::reorderTrack,
                                downloads = downloadTasks,
                                onOpenDownloads = { downloadsOpen = true },
                                favoriteFolders = favoriteFolders,
                                favoriteSync = favoriteSync,
                                favoriteContent = openedFavorite,
                                loginState = loginState,
                                session = session,
                                onSyncFavorites = viewModel::syncFavoriteFolders,
                                onOpenFavorite = viewModel::openFavorite,
                                onCloseFavorite = viewModel::closeFavorite,
                                onFavoriteTrackClick = viewModel::playFavoriteTrack,
                                onFavoriteLoadMore = viewModel::loadFavoritePage,
                                onStartLogin = viewModel::startLogin,
                                onCancelLogin = viewModel::cancelLogin,
                                watchLaterContent = watchLater,
                                onOpenWatchLater = viewModel::openWatchLater,
                                onCloseWatchLater = viewModel::closeWatchLater,
                                onWatchLaterRetry = viewModel::retryWatchLater,
                                onWatchLaterItemClick = viewModel::playWatchLaterItem,
                                selectedTab = tab,
                                onTabSelected = onTab,
                                openedSeries = openedSeries,
                                onCloseSeries = viewModel::closeSeries,
                                collectedList = collectedList,
                                collectedListState = collectedListState,
                                onOpenSeries = viewModel::openSeries,
                                onCollectedLoadMore = viewModel::loadCollectedList,
                                onSeriesLoadMore = viewModel::loadSeriesPage,
                                onPlaySeriesArchive = viewModel::playSeriesArchive,
                            )
                        }
                    },
                    settingsContent = {
                        SettingsScreen(
                            settings = settings,
                            onDefaultPresentationChange = viewModel::setDefaultPresentation,
                            onMiniBarStyleChange = viewModel::setMiniBarStyle,
                            onAllowVideoOnMeteredChange = viewModel::setAllowVideoOnMetered,
                            onAudioQualityChange = viewModel::setAudioQuality,
                            onLoudnessChange = viewModel::setLoudnessNormalization,
                            accountContent = {
                                LoginPanel(
                                    loginState = loginState,
                                    session = session,
                                    onStart = viewModel::startLogin,
                                    onCancel = viewModel::cancelLogin,
                                    onLogout = viewModel::logout,
                                )
                            },
                        )
                    },
                )

                // 流量策略拦下的「切看」。给一次性放行，而不是简单地不响应——
                // 点了没反应会被当成 bug
                videoBlocked?.let { target ->
                    MeteredVideoDialog(
                        onConfirmOnce = { viewModel.applyPresentation(target, surfaceView) },
                        onDismiss = viewModel::dismissVideoBlocked,
                    )
                }

                // 加入歌单不再替用户选第一个：问清楚加到哪儿
                pendingBatchAdd?.let {
                    PlaylistPickerDialog(
                        playlists = playlists,
                        onPick = viewModel::confirmAddToPlaylist,
                        onCreate = viewModel::createPlaylistAndAdd,
                        onDismiss = viewModel::dismissBatchAdd,
                    )
                }

                // 单条多 P 视频的分 P 选择页（Ch3）
                multiPagePicker?.let { picker ->
                    com.biliseek.app.ui.MultiPagePickerScreen(
                        state = picker,
                        onConfirm = viewModel::confirmMultiPagePicker,
                        onDismiss = viewModel::dismissMultiPagePicker,
                    )
                }

                // Ch4：动态评论 / 图片预览
                commentDialog?.let { state ->
                    com.biliseek.app.ui.components.DynamicCommentDialog(
                        comments = state.comments,
                        sort = state.sort,
                        replyTargetUname = state.replyTarget?.uname,
                        content = state.content,
                        isPosting = state.isPosting,
                        expandedSubReplies = state.expandedSubReplies,
                        onSortChange = viewModel::setCommentSort,
                        onLoadMore = viewModel::loadMoreComments,
                        onLike = { reply, root -> viewModel.toggleCommentLike(reply, root) },
                        onReply = { reply, root -> viewModel.startReplyTo(reply, root) },
                        onExpandSubReplies = viewModel::expandSubReplies,
                        onLoadMoreSubReplies = viewModel::loadMoreSubReplies,
                        onContentChange = viewModel::updateCommentContent,
                        onClearReplyTarget = viewModel::clearReplyTarget,
                        onSubmit = viewModel::submitComment,
                        onDismiss = viewModel::dismissCommentDialog,
                    )
                }
                previewImage?.let { state ->
                    com.biliseek.app.ui.components.ImagePreviewDialog(
                        pics = state.pics,
                        initialIndex = state.initialIndex,
                        onDismiss = viewModel::dismissImagePreview,
                    )
                }

                // Ch5：播放列表 sheet
                if (queueSheetOpen) {
                    com.biliseek.app.ui.components.PlayerQueueSheet(
                        state = playback,
                        onSkipTo = viewModel::queueSkipTo,
                        onClearQueue = viewModel::clearQueue,
                        onDismiss = viewModel::closeQueueSheet,
                    )
                }

                // 下载前先确认内容与保存位置
                downloadFor?.let { target ->
                    DownloadDialog(
                        target = target,
                        settings = settings,
                        onDismiss = { downloadFor = null },
                        onConfirm = { request ->
                            if (request.location == com.biliseek.app.data.DownloadLocation.PUBLIC &&
                                needsStoragePermission()
                            ) {
                                pendingStorageDownload = request
                                requestStoragePermission.launch(
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                )
                            } else {
                                viewModel.enqueueDownload(request)
                            }
                        },
                        onRememberLocation = viewModel::setDownloadLocation,
                        onRememberSafTree = viewModel::setDownloadSafTreeUri,
                    )
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BiliSeek", text))
    }

    /**
     * 分享 / 链接进来的第二处入口。
     *
     * **两处都要接。** App 已在后台时系统不会再走 `onCreate`，
     * 只漏这一处的表现是「第一次分享能用，第二次点了没反应」。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent?) {
        val text = sharedTextFrom(
            action = intent?.action,
            type = intent?.type,
            extraText = intent?.getStringExtra(Intent.EXTRA_TEXT),
            dataString = intent?.dataString,
        ) ?: return
        viewModel.handleSharedText(text)
    }

    /**
     * Android 13+ 通知需要运行时授权。
     *
     * 不授予不影响**锁屏**媒体控制（那由 MediaSession 驱动），
     * 但通知栏控制会因 `importance=NONE` 而不显示——M0 验收时实测确认过这个区别。
     */
    private fun ensureNotificationPermission() {        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** API 26–28 且未授予写外部存储时返回 true。 */
    private fun needsStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT < 26 || Build.VERSION.SDK_INT > 28) return false
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build(),
            )
        }
    }

    /**
     * 离开 App 时的处置。
     *
     * 看模式下若支持 PiP 则进 PiP（呈现态仍为可见，**不回落音频**）；
     * 否则回落纯音频，避免解码没人看的画面。
     */
    override fun onUserLeaveHint() {        super.onUserLeaveHint()
        val state = viewModel.connection.state.value
        if (state.presentation != com.biliseek.core.player.PresentationMode.NONE) {
            enterPip()
        } else {
            viewModel.onEnterBackground()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        wasInPip = isInPictureInPictureMode
        viewModel.onPipChanged(isInPictureInPictureMode)
    }

    override fun onStop() {
        super.onStop()
        // PiP 窗口仍可见，不回落；真正不可见了才降回纯音频
        if (!wasInPip && !isInPictureInPictureMode) viewModel.onEnterBackground()
    }
}
