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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.biliseek.app.ui.AppShell
import com.biliseek.app.ui.DownloadDialog
import com.biliseek.app.ui.DownloadsScreen
import com.biliseek.app.ui.DynamicScreen
import com.biliseek.app.ui.HomeEmptyState
import com.biliseek.app.ui.LibraryScreen
import com.biliseek.app.ui.MeteredVideoDialog
import com.biliseek.app.ui.PlaylistPickerDialog
import com.biliseek.app.ui.PlayerExpansion
import com.biliseek.app.ui.SettingsScreen
import com.biliseek.app.ui.UpSpaceScreen
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
                val recentPlays by viewModel.recentPlays.collectAsState()
                val heatmap by viewModel.heatmap.collectAsState()
                val favoriteFolders by viewModel.favoriteFolders.collectAsState()
                val favoriteSync by viewModel.favoriteSync.collectAsState()
                val openedFavorite by viewModel.openedFavorite.collectAsState()
                val dynamic by viewModel.dynamic.collectAsState()
                val upSpace by viewModel.upSpace.collectAsState()
                val pendingAdd by viewModel.pendingAdd.collectAsState()
                val settings by viewModel.settings.collectAsState()
                val videoBlocked by viewModel.videoBlocked.collectAsState()
                val downloadTasks by viewModel.downloadTasks.collectAsState()

                var expansion by remember { mutableStateOf(PlayerExpansion.COLLAPSED) }
                // 长按 → 下载：先问内容与保存位置
                var downloadFor by remember { mutableStateOf<SearchVideo?>(null) }
                // 「已下载」分区展开态
                var downloadsOpen by remember { mutableStateOf(false) }

                val context = LocalContext.current
                val surfaceView = remember {
                    SurfaceView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                }

                // 起播即展开播放器，让用户看到当前在放什么
                LaunchedEffect(Unit) {
                    viewModel.playbackStarted.collect {
                        expansion = PlayerExpansion.EXPANDED
                        // 「记住上次」：自动进看模式也要过流量策略这一关，
                        // 否则设置里关掉的移动网络看视频会从这条路溜进去
                        if (viewModel.shouldAutoEnterVideo()) {
                            viewModel.requestPresentation(PresentationMode.INLINE, surfaceView)
                        }
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

                AppShell(
                    onEnterPip = { enterPip() },
                    playback = playback,
                    expansion = expansion,
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
                    dynamicContent = {
                        DynamicScreen(
                            state = dynamic,
                            session = session,
                            loginState = loginState,
                            onInit = viewModel::initDynamic,
                            onTypeFilterChange = viewModel::setDynamicTypeFilter,
                            onSelectUp = viewModel::selectDynamicUp,
                            onLoadMore = viewModel::loadMoreDynamic,
                            onRetryInit = viewModel::initDynamic,
                            onEntryClick = viewModel::playDynamic,
                            onStartLogin = viewModel::startLogin,
                            onCancelLogin = viewModel::cancelLogin,
                        )
                    },
                    // UP 空间页（M11-D）：从搜索页点 UP 结果进入
                    searchContent = {
                        if (upSpace.up != null) {
                            UpSpaceScreen(
                                state = upSpace,
                                onClose = viewModel::closeUpSpace,
                                onViewChange = viewModel::setUpSpaceView,
                                onPostClick = viewModel::playUpPost,
                                onLoadMorePosts = viewModel::loadMoreUpPosts,
                                onRetryPosts = { viewModel.loadUpPosts(1) },
                                onDynamicClick = viewModel::playDynamic,
                                onLoadMoreDynamic = viewModel::loadMoreUpDynamic,
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
                            onAddToPlaylist = viewModel::requestAddToPlaylist,
                            onPlayNext = viewModel::enqueueNext,
                            onCopyLink = { video -> copyToClipboard(viewModel.videoUrl(video)) },
                            onShowPages = viewModel::showPages,
                            onDownload = { downloadFor = it },
                            onLoadMore = viewModel::loadMore,
                            onPageClick = { viewModel.playPage(search.pagesOfBvid, it) },
                            onDismissPages = viewModel::dismissPages,
                            onRetry = viewModel::retrySearch,
                            onOrderChange = { viewModel.setSearchFilters(it, search.duration) },
                            onDurationChange = { viewModel.setSearchFilters(search.order, it) },
                            onModeChange = viewModel::setSearchMode,
                            onUserClick = viewModel::openUpSpace,
                            onRetryUser = viewModel::retryUserSearch,
                            onLoadMoreUsers = viewModel::loadMoreUsers,
                            emptyContent = {
                                if (showEmptyState) {
                                    HomeEmptyState(
                                        searchHistory = searchHistory,
                                        recentPlays = recentPlays,
                                        heatmap = heatmap,
                                        onKeywordClick = viewModel::searchKeyword,
                                        onDeleteKeyword = viewModel::deleteSearchKeyword,
                                        onClearHistory = viewModel::clearSearchHistory,
                                        onRecentClick = viewModel::playHistoryEntry,
                                    )
                                }
                            },
                        )
                        }
                    },
                    libraryContent = {
                        if (downloadsOpen) {
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
                            )
                        }
                    },
                    settingsContent = {
                        SettingsScreen(
                            settings = settings,
                            onDefaultPresentationChange = viewModel::setDefaultPresentation,
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
                pendingAdd?.let {
                    PlaylistPickerDialog(
                        playlists = playlists,
                        onPick = viewModel::confirmAddToPlaylist,
                        onCreate = viewModel::createPlaylistAndAdd,
                        onDismiss = viewModel::dismissAddToPlaylist,
                    )
                }

                // 下载前先确认内容与保存位置
                downloadFor?.let { video ->
                    DownloadDialog(
                        video = video,
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
