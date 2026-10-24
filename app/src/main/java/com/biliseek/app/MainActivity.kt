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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.biliseek.app.ui.AppShell
import com.biliseek.app.ui.ChartScreen
import com.biliseek.app.ui.DayHistoryScreen
import com.biliseek.app.ui.Destination
import com.biliseek.app.ui.DownloadDialog
import com.biliseek.app.ui.FollowerScreen
import com.biliseek.app.ui.LikeScreen
import com.biliseek.app.ui.MessageCenterScreen
import com.biliseek.app.ui.ReplyAtScreen
import com.biliseek.app.ui.PrivateChatScreen
import com.biliseek.app.toSearchVideo
import com.biliseek.app.ui.DownloadsScreen
import com.biliseek.app.ui.DynamicScreen
import com.biliseek.app.ui.DynamicVideoActions
import com.biliseek.app.ui.FollowingListScreen
import com.biliseek.app.ui.HomeEmptyState
import com.biliseek.app.ui.LibraryScreen
import com.biliseek.app.ui.LibraryTab
import com.biliseek.app.ui.MeteredVideoDialog
import com.biliseek.app.ui.AddToListPickerDialog
import com.biliseek.app.ui.PlayerExpansion
import com.biliseek.app.ui.SettingsScreen
import com.biliseek.app.ui.UpSpaceScreen
import com.biliseek.app.ui.VideoMenuActions
import com.biliseek.app.ui.WatchLaterScreen
import com.biliseek.app.ui.playModeLabel
import com.biliseek.core.network.sharedTextFrom
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.dto.SearchUser
import com.biliseek.core.player.PresentationMode
import com.biliseek.designsystem.theme.BiliSeekTheme

/** 队列「撤销」提示的停留时长（docs/10）：与列表移除的撤销提示同一时长，右滑演示气泡里写的秒数也从这里来。 */
private const val QUEUE_UNDO_SNACKBAR_MS = com.biliseek.app.remote.RemoteLibraryController.REMOVAL_UNDO_MS

/**
 * 弹一条带「撤销」的提示，返回用户有没有点「撤销」。
 *
 * 队列变动、远程列表移除、本地列表移除共用主界面的提示位：先收起别的提示，免得新提示排队、把撤销时间耗掉。
 * 停留 [timeoutMs] 后自动消失——不用 SnackbarDuration.Short：带按钮时它会被无障碍设置拉长；
 * 用 Indefinite + 超时取消，取消时提示随之消失。
 */
private suspend fun androidx.compose.material3.SnackbarHostState.showUndo(message: String, timeoutMs: Long): Boolean {
    currentSnackbarData?.dismiss()
    val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        showSnackbar(
            message = message,
            actionLabel = "撤销",
            duration = androidx.compose.material3.SnackbarDuration.Indefinite,
        )
    }
    return result == androidx.compose.material3.SnackbarResult.ActionPerformed
}

/**
 * 弹一条普通提示（App 内、不带按钮），停留 [TOAST_MS]（同系统 Toast 的短提示）。
 *
 * 新的普通提示替换正在显示的普通提示（连点只留最新一条）；带「撤销」的提示正在显示时先等它结束，
 * 免得把撤销的机会顶掉（如删除后正好有下载完成）。反过来，带「撤销」的提示会顶掉普通提示（见 [showUndo]）。
 */
private suspend fun androidx.compose.material3.SnackbarHostState.showToast(message: String) {
    currentSnackbarData?.takeIf { it.visuals.actionLabel == null }?.dismiss()
    androidx.compose.runtime.snapshotFlow { currentSnackbarData }.first { it == null }
    kotlinx.coroutines.withTimeoutOrNull(TOAST_MS) {
        showSnackbar(message = message, duration = androidx.compose.material3.SnackbarDuration.Indefinite)
    }
}

private const val TOAST_MS = 2_000L

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    /** 当前是否处于 PiP。onStop 判定回落要用，[inPipState] 是给 Compose 看的同一份。 */
    private var wasInPip = false

    /** PiP 状态的 Compose 版：PiP 窗口里只渲染画面，不渲染控件。 */
    private val inPipState = androidx.compose.runtime.mutableStateOf(false)

    /** 承载画面的 SurfaceView（Compose 里创建）。进 PiP 时用它的位置做 sourceRectHint。 */
    private var videoSurface: SurfaceView? = null

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不阻断 */ }

    /** API 26–28 写公共媒体库需要运行时权限；29+ 走 MediaStore 不需要。 */
    private val requestStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingStorageDownload?.let { requests ->
                if (granted) viewModel.enqueueDownloads(requests)
                // 拒绝时任务不入队——界面上的失败提示由用户自己决定重试
            }
            pendingStorageDownload = null
        }

    /** AI 总结「另存为…」：SAF 让用户选位置，写入待导出的 md（工单 AI-6）。 */
    private var pendingSummaryExport: String? = null
    private val exportSummary =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            val text = pendingSummaryExport
            pendingSummaryExport = null
            if (uri != null && text != null) {
                val ok = runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false)
                viewModel.notifyMessage(if (ok) "已保存" else "保存失败")
            }
        }

    /** 等权限就绪后入队的下载（仅 API 26–28 + 公共媒体库路径）。 */
    private var pendingStorageDownload: List<com.biliseek.app.download.DownloadRequest>? = null

    /**
     * 「先取消上一条再弹」的系统 Toast 的上一条：未登录点头像、动态页按 UP 筛选的提示共用。
     * 留着引用，弹新的之前先取消上一个，连点时不会排队弹多次。
     */
    private var replacingToast: android.widget.Toast? = null

    /** Activity 自己发的普通提示（筛选、播放模式、请先登录、登录面板），和 `viewModel.transientMessage` 合成一条管道。 */
    private val appMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 首次演示的「另一次启动」按冷启动计（设计稿 2026-10-07-first-use-hints）
        if (savedInstanceState == null) viewModel.hints.onColdStart()
        ensureNotificationPermission()
        handleSharedIntent(intent)
        handleSummaryIntent(intent)

        setContent {
            // 深色模式：设置里可选跟随系统 / 浅色 / 深色（见 ThemePolicy）；状态栏、导航条图标颜色跟着变
            val themeMode by remember { viewModel.settings.map { it.themeMode }.distinctUntilChanged() }
                .collectAsState(initial = viewModel.settings.value.themeMode)
            val darkTheme = com.biliseek.app.ui.resolveDarkTheme(themeMode, isSystemInDarkTheme())
            androidx.compose.runtime.DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = androidx.activity.SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { darkTheme },
                    // 遮罩色同 enableEdgeToEdge 的默认值（三键导航时才看得到），只是深浅改按设置判断
                    navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                        android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF),
                        android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B),
                    ) { darkTheme },
                )
                onDispose {}
            }
            BiliSeekTheme(darkTheme = darkTheme) {
                // 表情：进程级字典（富文本兜底）+ 表情面板宿主（评论输入栏用）
                val emoteDictionary by viewModel.emotes.dictionary.collectAsState()
                val emotePanel by viewModel.emotes.panel.collectAsState()
                val showMessage = remember { { msg: String -> showReplacingToast(msg) } }
                androidx.compose.runtime.CompositionLocalProvider(
                    com.biliseek.app.ui.components.LocalShowMessage provides showMessage,
                    com.biliseek.app.ui.components.LocalHints provides viewModel.hints,
                    com.biliseek.app.ui.richtext.LocalEmoteDictionary provides emoteDictionary,
                    com.biliseek.app.ui.richtext.LocalEmotePanelHost provides com.biliseek.app.ui.richtext.EmotePanelHost(
                        state = emotePanel,
                        ensureLoaded = viewModel.emotes::ensureLoaded,
                    ),
                ) {
                val playback by viewModel.connection.state.collectAsState()
                val videoInteraction by viewModel.videoInteraction.state.collectAsState()
                val search by viewModel.search.state.collectAsState()
                val pagePanel by viewModel.pagePanel.collectAsState()
                val loginState by viewModel.loginState.collectAsState()
                val session by viewModel.session.collectAsState()
                val playlists by viewModel.playlists.collectAsState()
                val openedPlaylist by viewModel.openedPlaylist.collectAsState()
                val openedTracks by viewModel.openedTracks.collectAsState()
                val searchHistory by viewModel.search.history.collectAsState()
                val heatmap by viewModel.heatmap.collectAsState()
                val favoriteFolders by viewModel.remoteLibrary.favoriteFolders.collectAsState()
                val favoriteSync by viewModel.remoteLibrary.favoriteSync.collectAsState()
                val openedFavorite by viewModel.remoteLibrary.openedFavorite.collectAsState()
                val dynamic by viewModel.dynamics.dynamic.collectAsState()
                val upSpace by viewModel.upSpace.state.collectAsState()
                val dayHistoryDate by viewModel.dayHistoryDate.collectAsState()
                val dayView by viewModel.dayView.collectAsState()
                val watchLater by viewModel.remoteLibrary.watchLater.collectAsState()
                val chart by viewModel.charts.opened.collectAsState()
                val publicFavorite by viewModel.remoteLibrary.openedPublicFavorite.collectAsState()
                val regionOverviewOpen by viewModel.regions.overviewOpen.collectAsState()
                val regionDetail by viewModel.regions.detail.collectAsState()
                val regionLists by viewModel.regions.lists.collectAsState()
                val regionSettings by viewModel.regions.settings.collectAsState()
                val pendingBatchAdd by viewModel.pendingBatchAdd.collectAsState()
                val deletingFavoriteMediaId by viewModel.deletingFavoriteMediaId.collectAsState()
                val selectionMode by viewModel.selectionMode.collectAsState()
                val selectedBvids by viewModel.selectedBvids.collectAsState()
                val multiPagePicker by viewModel.multiPagePicker.collectAsState()
                val currentHasSummary by viewModel.currentHasSummary.collectAsState()
                val summarySheet by viewModel.summary.sheet.collectAsState()
                val summaryReader by viewModel.summary.reader.collectAsState()
                val summaryReaderState by viewModel.summary.readerState.collectAsState(initial = null)
                // 多 P 面板 / UP 空间投稿的复选作用域（与搜索结果复选互斥）
                val pageSelectionActive by viewModel.pageSelection.active.collectAsState()
                val selectedPageKeys by viewModel.pageSelection.selected.collectAsState()
                val upSelectionActive by viewModel.upSelection.active.collectAsState()
                val selectedUpKeys by viewModel.upSelection.selected.collectAsState()
                val danmakuEnabled by viewModel.danmakuEnabled.collectAsState()
                val danmakuItems by viewModel.danmakuItems.collectAsState()
                val previewImage by viewModel.previewImage.collectAsState()
                val dynamicExpand by viewModel.dynamics.dynamicExpand.collectAsState()
                val queueSheetOpen by viewModel.queueSheetOpen.collectAsState()
                val settings by viewModel.settings.collectAsState()
                val videoBlocked by viewModel.videoBlocked.collectAsState()
                val downloadTasks by viewModel.downloadTasks.collectAsState()
                val openedSeries by viewModel.remoteLibrary.openedSeries.collectAsState()
                val collectedList by viewModel.remoteLibrary.collectedList.collectAsState()
                val collectedListState by viewModel.remoteLibrary.collectedListState.collectAsState()
                val unreadSummary by viewModel.messages.unreadSummary.collectAsState()

                val coroutineScope = rememberCoroutineScope()
                var expansion by remember { mutableStateOf(PlayerExpansion.COLLAPSED) }
                // 长按 → 下载：先问内容与保存位置（搜索结果 / 多 P 分 P / UP 投稿共用）
                // 播放页「下载当前」直接确认这一条；其余入口经 ViewModel（多 P 先挑分 P）落到 pendingDownload
                var downloadFor by remember { mutableStateOf<com.biliseek.app.ui.DownloadTarget?>(null) }
                val pendingDownload by viewModel.pendingDownload.collectAsState()
                // 视频动态长按菜单：动态 tab 与 UP 空间「动态」共用，四项全部复用搜索结果那套动作
                val dynamicVideoActions = DynamicVideoActions(
                    addToPlaylist = { viewModel.startDynamicSingleAction(it, com.biliseek.app.PendingAction.AddToPlaylist) },
                    appendToQueue = { viewModel.startDynamicSingleAction(it, com.biliseek.app.PendingAction.AppendToQueue) },
                    playNext = { viewModel.startDynamicSingleAction(it, com.biliseek.app.PendingAction.EnqueueNext) },
                    download = { viewModel.startDynamicSingleAction(it, com.biliseek.app.PendingAction.Download) },
                    summarize = { viewModel.startDynamicSingleAction(it, com.biliseek.app.PendingAction.Summarize) },
                )
                // 程序化 tab 切换（如「去设置」→ 设置 tab）。
                val tabSwitchRequests = remember { MutableSharedFlow<Destination>(extraBufferCapacity = 1) }
                // UP 空间页导航信号：AppShell 收到后 push UP_SPACE destination。
                val openUpSpaceRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
                // 栈顶页面（AppShell 回报）：评论弹层点人进 UP 空间时藏起，回到这个页面再显示
                var topEntryId by remember { mutableStateOf<String?>(null) }
                var topRoute by remember { mutableStateOf<String?>(null) }
                // 打开自己的 UP 空间：搜索页头像、设置页账号卡片共用
                val openOwnSpace: () -> Unit = {
                    viewModel.openUpSpace(
                        SearchUser(
                            mid = session.dedeUserId.toLongOrNull() ?: 0L,
                            uname = session.uname,
                            upic = session.face,
                        )
                    )
                    openUpSpaceRequests.tryEmit(Unit)
                }
                // 视频条目三点菜单（榜单 / 收藏夹 / 稍后再看 / 合集）：七项全部复用搜索结果那套动作
                val videoMenuActions = VideoMenuActions(
                    addToPlaylist = { viewModel.startSingleAction(it, com.biliseek.app.PendingAction.AddToPlaylist) },
                    appendToQueue = { viewModel.startSingleAction(it, com.biliseek.app.PendingAction.AppendToQueue) },
                    playNext = { viewModel.startSingleAction(it, com.biliseek.app.PendingAction.EnqueueNext) },
                    viewUp = { video ->
                        viewModel.openUpSpace(SearchUser(mid = video.mid, uname = video.author))
                        openUpSpaceRequests.tryEmit(Unit)
                    },
                    copyLink = { video -> copyToClipboard(viewModel.videoUrl(video)) },
                    download = { viewModel.startSingleAction(it, com.biliseek.app.PendingAction.Download) },
                    summarize = viewModel::summarizeVideo,
                )
                // 关注列表页导航信号：AppShell 收到后 push FOLLOWING_LIST destination。
                val openFollowingListRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
                // 启动 / 从后台返回时刷新未读小红点。
                // 使用 `androidx.compose.ui.platform.LocalLifecycleOwner`（compose-ui 自带，
                // 不引入新依赖）；返回 LifecycleOwner 接口，足够 `lifecycle.addObserver` 使用。
                val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            viewModel.messages.refreshUnreadCount()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                // 「已下载」分区展开态
                var downloadsOpen by remember { mutableStateOf(false) }
                var summariesOpen by remember { mutableStateOf(false) }
                val summaryLibraryState by viewModel.summaryLibrary.state.collectAsState()
                var aiSettingsOpen by remember { mutableStateOf(false) }
                var quickEntriesOpen by remember { mutableStateOf(false) }
                // 「去设置」：切到设置 tab 并打开 AI 总结设置页
                LaunchedEffect(Unit) {
                    viewModel.openAiSettingsRequests.collect {
                        aiSettingsOpen = true
                        tabSwitchRequests.tryEmit(Destination.SETTINGS)
                    }
                }
                val aiSettingsState by viewModel.aiSettings.state.collectAsState()
                val aiConnectionTests by viewModel.aiSettings.tests.collectAsState()
                val aiModelLists by viewModel.aiSettings.modelLists.collectAsState()
                // 列表 tab 选中的页
                var selectedTab by remember { mutableStateOf(LibraryTab.PLAYLISTS) }

                val context = LocalContext.current
                val surfaceView = remember {
                    SurfaceView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }.also { videoSurface = it }
                }
                val isInPip by inPipState

                // 看模式播放中保持常亮——内联与全屏一视同仁，不只全屏
                val keepScreenOn = com.biliseek.app.ui.shouldKeepScreenOn(
                    playback.presentation,
                    playback.isPlaying,
                )
                androidx.compose.runtime.DisposableEffect(keepScreenOn) {
                    if (keepScreenOn) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                // 全屏 / 画中画时不开始新的首次演示
                LaunchedEffect(expansion, isInPip) {
                    viewModel.hints.setEnvironmentBlocked(expansion == PlayerExpansion.FULLSCREEN || isInPip)
                }

                // 弹幕只在全屏（且不在 PiP）时拉取与显示
                LaunchedEffect(expansion, isInPip) {
                    viewModel.setDanmakuSurfaceVisible(
                        expansion == PlayerExpansion.FULLSCREEN && !isInPip,
                    )
                }

                // 画面被回落成纯听（息屏 / 进后台）后，全屏层没有存在意义：
                // 留着只剩一个有声音的黑框，退回展开态
                LaunchedEffect(expansion, playback.presentation) {
                    if (expansion == PlayerExpansion.FULLSCREEN &&
                        com.biliseek.app.ui.shouldLeaveFullscreen(playback.presentation)
                    ) {
                        expansion = PlayerExpansion.EXPANDED
                    }
                }

                // 「起播模式：记住上次」不在起播时进看（那时多半不在播放页，画面没有地方显示），
                // 改在打开播放页时生效：AppShell 进入播放页时调 onPlayerPageShown（见 PlayerPagePolicy）。

                // 可撤销的队列变动（docs/10）：清空、右滑删除、点击替换掉整张列表。
                // 队列面板开着时显示在面板里（面板是独立窗口，会挡住主界面的 Snackbar）。
                // collectLatest：新提示来了就取消旧的——撤销快照只对最近一次变动有效。
                // 停留 4 秒：不用 SnackbarDuration.Short，带按钮时它会被无障碍设置拉长；
                // 用 Indefinite + 超时取消，取消时 Snackbar 随之消失
                val queueSnackbar = remember { androidx.compose.material3.SnackbarHostState() }
                val queueSheetSnackbar = remember { androidx.compose.material3.SnackbarHostState() }
                LaunchedEffect(Unit) {
                    viewModel.queueUndo.collectLatest { msg ->
                        val host = if (viewModel.queueSheetOpen.value) queueSheetSnackbar else queueSnackbar
                        if (host.showUndo(msg, QUEUE_UNDO_SNACKBAR_MS)) viewModel.undoQueueChange()
                    }
                }
                // 稍后再看 / 收藏夹里移除条目（右滑、三点菜单「删除」）：与队列撤销同一个提示位。
                // 提示消失后控制器才真正提交，所以点「撤销」不用再发请求；只有最近一次能撤销
                LaunchedEffect(Unit) {
                    viewModel.remoteLibrary.removalNotice.collectLatest { msg ->
                        if (queueSnackbar.showUndo(msg, com.biliseek.app.remote.RemoteLibraryController.REMOVAL_UNDO_MS)) {
                            viewModel.remoteLibrary.undoRemoval()
                        }
                    }
                }
                // 本地列表里移除条目：本地数据已经移除，点「撤销」按原位置放回
                LaunchedEffect(Unit) {
                    viewModel.playlistRemovalNotice.collectLatest { msg ->
                        if (queueSnackbar.showUndo(msg, QUEUE_UNDO_SNACKBAR_MS)) viewModel.undoPlaylistRemoval()
                    }
                }
                // 「已下载」页删除：先藏起来，点「撤销」放回；过几秒才真删文件
                LaunchedEffect(Unit) {
                    viewModel.downloadRemovalNotice.collectLatest { msg ->
                        if (queueSnackbar.showUndo(msg, com.biliseek.app.remote.RemoteLibraryController.REMOVAL_UNDO_MS)) {
                            viewModel.undoDownloadRemoval()
                        }
                    }
                }
                // 「AI 总结」页右滑删除：同上，过几秒才真删总结文件
                LaunchedEffect(Unit) {
                    viewModel.summaryLibrary.removalNotice.collectLatest { msg ->
                        if (queueSnackbar.showUndo(msg, com.biliseek.app.remote.RemoteLibraryController.REMOVAL_UNDO_MS)) {
                            viewModel.summaryLibrary.undoDelete()
                        }
                    }
                }

                // 普通提示（不带按钮：批量操作结果、链接已复制、下载完成、筛选、播放模式……）：
                // 前台一律用 App 内的提示，放在迷你条上方——系统 Toast 的位置挪不动、会压在迷你条上（用户 2026-10-04 要求）。
                // 队列面板开着时显示在面板里；App 在后台 / 小窗里，或别的弹层 / 弹窗盖着主界面时，
                // App 内的提示看不到，照旧系统 Toast。
                // collectLatest：新的替换旧的（设计稿 docs/superpowers/specs/2026-10-04-toast-above-minibar-design.md）
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.flow.merge(viewModel.transientMessage, appMessages).collectLatest { msg ->
                        val target = com.biliseek.app.ui.components.resolveToastTarget(
                            foreground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED),
                            inPip = inPipState.value,
                            queueSheetOpen = viewModel.queueSheetOpen.value,
                            mainWindowFocused = hasWindowFocus(),
                        )
                        when (target) {
                            com.biliseek.app.ui.components.ToastTarget.MAIN -> queueSnackbar.showToast(msg)
                            com.biliseek.app.ui.components.ToastTarget.QUEUE_SHEET -> queueSheetSnackbar.showToast(msg)
                            com.biliseek.app.ui.components.ToastTarget.SYSTEM -> showSystemToast(msg)
                        }
                    }
                }

                // 动态页按 UP 筛选的提示：选中 / 取消各一条，连点时新的替换上一条（不排队）
                LaunchedEffect(Unit) {
                    viewModel.dynamics.filterNotice.collect { showReplacingToast(it) }
                }

                // 已登录且目录从未同步过 → 自动拉一次收藏夹目录（音乐库分区秒开）
                LaunchedEffect(session.isLoggedIn, favoriteFolders.isEmpty()) {
                    if (session.isLoggedIn && favoriteFolders.isEmpty() &&
                        favoriteSync !is com.biliseek.app.SyncState.Syncing &&
                        favoriteSync !is com.biliseek.app.SyncState.NotLoggedIn
                    ) {
                        viewModel.remoteLibrary.syncFavoriteFolders()
                    }
                }

                // 已登录且合集列表从未加载过 → 自动拉一次订阅合集列表
                LaunchedEffect(session.isLoggedIn) {
                    if (session.isLoggedIn && viewModel.remoteLibrary.collectedList.value.isEmpty() &&
                        viewModel.remoteLibrary.collectedListState.value.isLoading.not()
                    ) {
                        viewModel.remoteLibrary.loadCollectedList(1)
                    }
                }

                // 首次进入 / 登录 / 退出也刷新一次未读小红点（LifecycleObserver 只覆盖 ON_RESUME）
                LaunchedEffect(session.isLoggedIn) {
                    viewModel.messages.refreshUnreadCount()
                }

                AppShell(
                    onEnterPip = { enterPip() },
                    playback = playback,
                    expansion = expansion,
                    tabSwitchRequests = tabSwitchRequests,
                    openUpSpaceRequests = openUpSpaceRequests,
                    onBackStackEntryChanged = { id, route ->
                        topEntryId = id
                        topRoute = route
                        viewModel.dynamics.comments.resumeOn(id)
                        viewModel.playerComments.resumeOn(id)
                    },
                    openFollowingListRequests = openFollowingListRequests,
                    openMessagesRequests = viewModel.messages.openMessagesRequests,
                    openReplyFeedRequests = viewModel.messages.openReplyFeedRequests,
                    openLikeFeedRequests = viewModel.messages.openLikeFeedRequests,
                    openFollowerFeedRequests = viewModel.messages.openFollowerFeedRequests,
                    openPrivateChatRequests = viewModel.messages.openPrivateChatRequests,
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
                    onLeaveToListen = viewModel::leaveToListen,
                    onPlayerPageShown = { viewModel.onPlayerPageShown(surfaceView) },
                    onSkipNext = viewModel::skipNext,
                    onSkipPrevious = viewModel::skipPrevious,
                    onCyclePlayMode = { showReplacingToast(playModeLabel(viewModel.cyclePlayMode())) },
                    onSeek = viewModel::seekTo,
                    onSetSpeed = viewModel::setSpeed,
                    onSetSleepTimer = viewModel::setSleepTimer,
                    onRetryPlayback = viewModel::retryPlayback,
                    onOpenQueue = viewModel::openQueueSheet,
                    onQueueSkipTo = viewModel::queueSkipTo,
                    onOpenComments = viewModel::openPlayerComment,
                    videoInteraction = videoInteraction,
                    videoActionHandlers = remember {
                        com.biliseek.app.ui.VideoActionHandlers(
                            onBind = viewModel.videoInteraction::bind,
                            onToggleLike = viewModel.videoInteraction::toggleLike,
                            onTriple = viewModel.videoInteraction::triple,
                            onCoin = viewModel.videoInteraction::addCoin,
                        )
                    },
                    onSummary = viewModel::summarizeCurrent,
                    hasSummary = currentHasSummary,
                    onDescriptionTimestamp = viewModel::seekFromDescription,
                    onDescriptionLink = viewModel::openDescriptionLink,
                    onShare = {
                        viewModel.currentVideoUrl()?.let { url ->
                            copyToClipboard(url)
                            viewModel.notifyLinkCopied()
                        }
                    },
                    onOpenAddToList = viewModel::openPlayerAddToList,
                    onOpenUpSpace = {
                        coroutineScope.launch {
                            if (viewModel.resolvePlayerAuthor()) {
                                openUpSpaceRequests.tryEmit(Unit)
                            }
                        }
                    },
                    danmakuEnabled = danmakuEnabled,
                    onDanmakuToggle = viewModel::toggleDanmaku,
                    isInPip = isInPip,
                    onSetVideoQuality = viewModel::setVideoQuality,
                    defaultAudioQuality = settings.audioQuality,
                    onSetAudioQuality = viewModel::setAudioQualityForSession,
                    onSetDefaultAudioQuality = { viewModel.setAudioQuality(it) },
                    danmakuItems = danmakuItems,
                    danmakuSettings = settings.danmaku,
                    onDanmakuSettingsChange = viewModel::updateDanmakuSettings,
                    onDownloadCurrent = {
                        val current = playback.queue.getOrNull(playback.queueIndex)
                            as? com.biliseek.core.player.PlaybackRequest.Bili
                        if (playback.bvid.isNotBlank()) {
                            downloadFor = com.biliseek.app.ui.DownloadTarget(
                                bvid = playback.bvid,
                                cid = playback.cid,
                                title = playback.title,
                                coverUrl = playback.coverUrl,
                                author = current?.author.orEmpty(),
                            )
                        }
                    },
                    miniBarStyle = settings.miniBarStyle,
                    snackbarHostState = queueSnackbar,
                    dynamicContent = {
                        DynamicScreen(
                            state = dynamic,
                            session = session,
                            loginState = loginState,
                            onInit = viewModel.dynamics::initDynamic,
                            onRefresh = viewModel.dynamics::refreshDynamic,
                            onTypeFilterChange = viewModel.dynamics::setDynamicTypeFilter,
                            onSelectUp = viewModel.dynamics::selectDynamicUp,
                            onLoadMore = viewModel.dynamics::loadMoreDynamic,
                            onRetryInit = viewModel.dynamics::initDynamic,
                            onEntryClick = viewModel::playDynamic,
                            onStartLogin = viewModel::startLogin,
                            onCancelLogin = viewModel::cancelLogin,
                            // Ch4
                            onOpenImagePreview = viewModel::openImagePreview,
                            onOpenRepost = { idStr ->
                                copyToClipboard(viewModel.dynamicUrl(idStr))
                                viewModel.notifyLinkCopied()
                            },
                            onOpenComment = viewModel.dynamics::openCommentDialog,
                            onToggleLike = viewModel.dynamics::toggleDynamicLike,
                            onOpenFollowList = {
                                openFollowingListRequests.tryEmit(Unit)
                            },
                            unreadCount = unreadSummary.total,
                            onOpenMessages = viewModel.messages::openMessages,
                            onAuthorClick = { entry ->
                                viewModel.openUpSpace(
                                    SearchUser(mid = entry.authorMid, uname = entry.authorName, upic = entry.authorFace),
                                )
                                openUpSpaceRequests.tryEmit(Unit)
                            },
                            dynamicExpand = dynamicExpand,
                            onToggleExpand = viewModel.dynamics::toggleDynamicExpand,
                            videoActions = dynamicVideoActions,
                        )
                    },
                    searchContent = {
                        // 外部分享链接会触发搜索：榜单页 / 视频播客不能盖住结果页
                        LaunchedEffect(search.keyword) {
                            if (search.keyword.isNotBlank()) {
                                viewModel.charts.close()
                                viewModel.remoteLibrary.closePublicFavorite()
                                viewModel.regions.closeAll()
                            }
                        }
                        val dayDate = dayHistoryDate
                        val openedChart = chart
                        val openedPublic = publicFavorite
                        val openedRegionDetail = regionDetail
                        if (dayDate != null) {
                            // 日历史页（M12）：从首页热力图点入，返回手势收起到首页
                            BackHandler(onBack = viewModel::closeDayHistory)
                            DayHistoryScreen(
                                date = dayDate,
                                view = dayView,
                                onClose = viewModel::closeDayHistory,
                                onEntryClick = viewModel::playDayHistoryEntry,
                            )
                        } else if (openedChart != null) {
                            // 榜单页：从首页快捷入口点入，返回手势收起到首页
                            BackHandler(onBack = viewModel.charts::close)
                            ChartScreen(
                                state = openedChart,
                                onClose = viewModel.charts::close,
                                onRetry = viewModel.charts::retry,
                                onItemClick = viewModel.charts::play,
                                onIssueSelect = viewModel.charts::selectIssue,
                                videoActions = videoMenuActions,
                            )
                        } else if (openedPublic != null) {
                            // 视频播客：从首页快捷入口点入，只读的收藏夹内容页；返回手势收起到首页
                            BackHandler(onBack = viewModel.remoteLibrary::closePublicFavorite)
                            com.biliseek.app.ui.FavoriteDetail(
                                state = openedPublic,
                                onClose = viewModel.remoteLibrary::closePublicFavorite,
                                onTrackClick = viewModel.remoteLibrary::playFavoriteTrack,
                                onLoadMore = viewModel.remoteLibrary::loadPublicFavoritePage,
                                onEditFolder = { _, _ -> },
                                onDeleteFolder = {},
                                onRemoveTrack = {},
                                videoActions = videoMenuActions,
                                readOnly = true,
                            )
                        } else if (openedRegionDetail != null) {
                            // 分区详情：返回回到总览（从总览进来时）或首页（从快捷入口直接进来时）
                            BackHandler(onBack = viewModel.regions::closeDetail)
                            com.biliseek.app.ui.RegionDetailScreen(
                                detail = openedRegionDetail,
                                lists = regionLists,
                                settings = regionSettings,
                                onSettingsChange = viewModel.regions::updateSettings,
                                onSelectTab = viewModel.regions::selectTab,
                                onLoadMore = viewModel.regions::loadMore,
                                onRetry = viewModel.regions::retry,
                                onItemClick = viewModel.regions::play,
                                onClose = viewModel.regions::closeDetail,
                                videoActions = videoMenuActions,
                            )
                        } else if (regionOverviewOpen) {
                            BackHandler(onBack = viewModel.regions::closeOverview)
                            com.biliseek.app.ui.RegionOverviewScreen(
                                settings = regionSettings,
                                onSettingsChange = viewModel.regions::updateSettings,
                                onOpenSubRegion = viewModel.regions::openDetail,
                                onClose = viewModel.regions::closeOverview,
                            )
                        } else {
                        // 首页空状态：无搜索、无结果时展示用户自己的行为数据
                        val showEmptyState = search.items.isEmpty() &&
                            search.keyword.isBlank() &&
                            search.error == null &&
                            !pagePanel.isOpen
                        SearchScreen(
                            state = search,
                            pagePanel = pagePanel,
                            onKeywordChange = viewModel.search::onKeywordChange,
                            onSubmit = viewModel.search::submitSearch,
                            onResultClick = viewModel::onResultClick,
                            onAddToPlaylist = { viewModel.startSingleAction(it, com.biliseek.app.PendingAction.AddToPlaylist) },
                            // 加入队列 = 追加到播放队列末尾（多 P 走挑 P 页，默认全选）
                            onAppendToQueue = { viewModel.startSingleAction(it, com.biliseek.app.PendingAction.AppendToQueue) },
                            onPlayNext = { viewModel.startSingleAction(it, com.biliseek.app.PendingAction.EnqueueNext) },
                            onCopyLink = { video -> copyToClipboard(viewModel.videoUrl(video)) },
                            onShowPages = viewModel::showPages,
                            onDownload = { viewModel.startSingleAction(it, com.biliseek.app.PendingAction.Download) },
                            onSummarize = viewModel::summarizeVideo,
                            onPageSummarize = { viewModel.pageSingleAction(it, com.biliseek.app.PendingAction.Summarize) },
                            onViewUpOwner = { video ->
                                viewModel.openUpSpace(
                                    SearchUser(mid = video.mid, uname = video.author),
                                )
                                openUpSpaceRequests.tryEmit(Unit)
                            },
                            onLoadMore = viewModel.search::loadMore,
                            onPageClick = { viewModel.playPage(pagePanel.bvid, it) },
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
                            onPageDownload = { viewModel.pageSingleAction(it, com.biliseek.app.PendingAction.Download) },
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
                            onRetry = viewModel.search::retrySearch,
                            onOrderChange = { viewModel.search.setSearchFilters(it, search.duration) },
                            onDurationChange = { viewModel.search.setSearchFilters(search.order, it) },
                            onModeChange = viewModel.search::setSearchMode,
                            onUserClick = { user ->
                                viewModel.openUpSpace(user)
                                openUpSpaceRequests.tryEmit(Unit)
                            },
                            onRetryUser = viewModel.search::retryUserSearch,
                            onLoadMoreUsers = viewModel.search::loadMoreUsers,
                            searchHistory = searchHistory,
                            onHistoryClick = viewModel.search::searchKeyword,
                            onDeleteHistory = viewModel.search::deleteSearchKeyword,
                            onClearHistory = viewModel.search::clearSearchHistory,
                            onAvatarClick = { if (session.isLoggedIn) openOwnSpace() else showLoginHint() },
                            session = session,
                            emptyContent = {
                                if (showEmptyState) {
                                    HomeEmptyState(
                                        searchHistory = searchHistory,
                                        heatmap = heatmap,
                                        onKeywordClick = viewModel.search::searchKeyword,
                                        onDeleteKeyword = viewModel.search::deleteSearchKeyword,
                                        onClearHistory = viewModel.search::clearSearchHistory,
                                        onDayClick = { date -> viewModel.openDayHistory(date) },
                                        quickEntries = com.biliseek.app.data.visibleQuickEntries(settings.quickEntries),
                                        onOpenQuickEntry = viewModel::openQuickEntry,
                                        // 搜索历史改为聚焦时才弹出，未聚焦首页不直接显示
                                        showSearchHistory = false,
                                    )
                                }
                            },
                        )
                        }
                    },
                    libraryContent = { tab, onTab ->
                        if (summariesOpen) {
                            BackHandler(onBack = { summariesOpen = false })
                            com.biliseek.app.ui.SummaryListScreen(
                                state = summaryLibraryState,
                                onClose = { summariesOpen = false },
                                onOpen = { viewModel.summary.openReader(it.bvid, it.cid) },
                                onPlay = viewModel::playSummaryVideo,
                                onDelete = viewModel.summaryLibrary::requestDelete,
                                onClearAll = viewModel.summaryLibrary::clearAll,
                            )
                        } else if (downloadsOpen) {
                            // 系统返回手势应收起下载页，而不是弹走导航栈（tab 内状态）
                            BackHandler(onBack = { downloadsOpen = false })
                            DownloadsScreen(
                                tasks = downloadTasks,
                                onClose = { downloadsOpen = false },
                                onPlay = viewModel::playDownload,
                                onRetry = viewModel::retryDownload,
                                onDelete = viewModel::deleteDownload,
                                onRetryAllFailed = viewModel::retryAllFailedDownloads,
                                onClearFailed = viewModel::clearFailedDownloads,
                                onPause = viewModel::pauseDownload,
                                onResume = viewModel::resumeDownload,
                                onPauseAll = viewModel::pauseAllDownloads,
                                onResumeAll = viewModel::resumeAllDownloads,
                                onCancelAll = viewModel::cancelAllDownloads,
                                onRemoveDone = viewModel::removeDownloads,
                                onPlayGroup = viewModel::playDownloadGroup,
                                videoActions = videoMenuActions,
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
                                onEdit = viewModel::editPlaylist,
                                onTrackClick = viewModel::playTrack,
                                onTrackRemove = viewModel::removeFromPlaylist,
                                onTrackReorder = viewModel::reorderTrack,
                                downloads = downloadTasks,
                                onOpenDownloads = { downloadsOpen = true },
                                summaryCount = summaryLibraryState.groups.size,
                                onOpenSummaries = { summariesOpen = true },
                                favoriteFolders = favoriteFolders,
                                favoriteSync = favoriteSync,
                                favoriteContent = openedFavorite,
                                loginState = loginState,
                                session = session,
                                onSyncFavorites = viewModel.remoteLibrary::syncFavoriteFolders,
                                onOpenFavorite = viewModel.remoteLibrary::openFavorite,
                                onCloseFavorite = viewModel.remoteLibrary::closeFavorite,
                                onCreateFavoriteFolder = viewModel::createFavoriteFolder,
                                onEditFavoriteFolder = viewModel::editFavoriteFolder,
                                onDeleteFavoriteFolder = viewModel::deleteFavoriteFolder,
                                onFavoriteTrackClick = viewModel.remoteLibrary::playFavoriteTrack,
                                onFavoriteLoadMore = viewModel.remoteLibrary::loadFavoritePage,
                                onRemoveFavoriteTrack = viewModel.remoteLibrary::removeFavoriteTrack,
                                onStartLogin = viewModel::startLogin,
                                onCancelLogin = viewModel::cancelLogin,
                                watchLaterContent = watchLater,
                                onOpenWatchLater = viewModel.remoteLibrary::openWatchLater,
                                onCloseWatchLater = viewModel.remoteLibrary::closeWatchLater,
                                onWatchLaterRetry = viewModel.remoteLibrary::retryWatchLater,
                                onWatchLaterItemClick = viewModel.remoteLibrary::playWatchLaterItem,
                                onRemoveWatchLaterItem = viewModel.remoteLibrary::removeWatchLaterItem,
                                selectedTab = tab,
                                onTabSelected = onTab,
                                openedSeries = openedSeries,
                                onCloseSeries = viewModel.remoteLibrary::closeSeries,
                                collectedList = collectedList,
                                collectedListState = collectedListState,
                                onOpenSeries = viewModel.remoteLibrary::openSeries,
                                onCollectedLoadMore = viewModel.remoteLibrary::loadCollectedList,
                                onSeriesLoadMore = viewModel.remoteLibrary::loadSeriesPage,
                                onPlaySeriesArchive = viewModel.remoteLibrary::playSeriesArchive,
                                onUnsubscribeSeries = viewModel.remoteLibrary::unsubscribeOpenedSeries,
                                videoActions = videoMenuActions,
                            )
                        }
                    },
                    settingsContent = {
                        if (aiSettingsOpen) {
                            BackHandler(onBack = { aiSettingsOpen = false })
                            com.biliseek.app.ui.AiSettingsScreen(
                                settings = aiSettingsState,
                                tests = aiConnectionTests,
                                modelLists = aiModelLists,
                                onSave = viewModel.aiSettings::save,
                                onClear = viewModel.aiSettings::clear,
                                onTest = viewModel.aiSettings::test,
                                onFetchModels = viewModel.aiSettings::fetchModels,
                                onDefaultChange = viewModel.aiSettings::setDefaultPick,
                                onCustomPromptSave = viewModel.aiSettings::setCustomPrompt,
                                onAcceptPrivacy = viewModel.aiSettings::acceptPrivacy,
                                onClose = { aiSettingsOpen = false },
                                asrContent = {
                                    val asrState by viewModel.asrModels.state.collectAsState()
                                    com.biliseek.app.ui.AsrModelCard(
                                        spec = viewModel.asrModels.spec,
                                        state = asrState,
                                        isMetered = viewModel.asrModels::isMetered,
                                        onDownload = viewModel.asrModels::download,
                                        onImport = viewModel.asrModels::import,
                                        onCancel = viewModel.asrModels::cancel,
                                        onDelete = viewModel.asrModels::delete,
                                    )
                                },
                            )
                        } else if (quickEntriesOpen) {
                            BackHandler(onBack = { quickEntriesOpen = false })
                            com.biliseek.app.ui.QuickEntrySettingsScreen(
                                entries = settings.quickEntries,
                                onChange = viewModel::setQuickEntries,
                                onClose = { quickEntriesOpen = false },
                            )
                        } else SettingsScreen(
                            settings = settings,
                            onDefaultPresentationChange = viewModel::setDefaultPresentation,
                            onMiniBarStyleChange = viewModel::setMiniBarStyle,
                            onThemeModeChange = viewModel::setThemeMode,
                            onAllowVideoOnMeteredChange = viewModel::setAllowVideoOnMetered,
                            onAudioQualityChange = viewModel::setAudioQuality,
                            onVideoQualityWifiChange = viewModel::setVideoQualityWifi,
                            onVideoQualityMeteredChange = viewModel::setVideoQualityMetered,
                            onLoudnessChange = viewModel::setLoudnessNormalization,
                            onOpenAiSettings = { aiSettingsOpen = true },
                            onOpenQuickEntries = { quickEntriesOpen = true },
                            aiSettingsSummary = if (aiSettingsState.primaryReady) {
                                aiSettingsState.primary?.model.orEmpty()
                            } else {
                                "未配置"
                            },
                            accountContent = {
                                LoginPanel(
                                    loginState = loginState,
                                    session = session,
                                    onStart = viewModel::startLogin,
                                    onCancel = viewModel::cancelLogin,
                                    onLogout = viewModel::logout,
                                )
                            },
                            session = session,
                            loginFailureReason = (loginState as? com.biliseek.core.network.LoginState.Failed)?.reason,
                            onOpenOwnSpace = openOwnSpace,
                            onLogout = viewModel::logout,
                            playMode = playback.playMode,
                            onPlayModeChange = viewModel::setPlayMode,
                            onDanmakuSettingsChange = viewModel::updateDanmakuSettings,
                            onDownloadConcurrencyChange = viewModel::setDownloadConcurrency,
                        )
                    },
                    upSpaceContent = { onBack ->
                        BackHandler(onBack = { viewModel.upSpace.closeUpSpace(); onBack() })
                        UpSpaceScreen(
                            state = upSpace,
                            onClose = { viewModel.upSpace.closeUpSpace(); onBack() },
                            onViewChange = viewModel.upSpace::setUpSpaceView,
                            onPostClick = viewModel::playUpPost,
                            onLoadMorePosts = viewModel.upSpace::loadMoreUpPosts,
                            onRetryPosts = { viewModel.upSpace.loadUpPosts(1) },
                            onDynamicClick = viewModel::playDynamic,
                            onLoadMoreDynamic = viewModel.upSpace::loadMoreUpDynamic,
                            // Ch4
                            onToggleFollow = viewModel.upSpace::toggleRelation,
                            onOpenImagePreview = viewModel::openImagePreview,
                            onOpenRepost = { idStr ->
                                copyToClipboard(viewModel.dynamicUrl(idStr))
                                viewModel.notifyLinkCopied()
                            },
                            onOpenComment = viewModel.dynamics::openCommentDialog,
                            onToggleLike = viewModel.dynamics::toggleDynamicLike,
                            onAuthorClick = { entry ->
                                viewModel.openUpSpace(
                                    SearchUser(mid = entry.authorMid, uname = entry.authorName, upic = entry.authorFace),
                                )
                            },
                            dynamicExpand = dynamicExpand,
                            onToggleExpand = viewModel.dynamics::toggleDynamicExpand,
                            videoActions = dynamicVideoActions,
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
                            onPostDownload = { viewModel.startUpSingleAction(it, com.biliseek.app.PendingAction.Download) },
                            onPostSummarize = { viewModel.summarizeVideo(it.toSearchVideo()) },
                        )
                    },
                    followingListContent = { onBack ->
                        BackHandler(onBack = { onBack() })
                        FollowingListScreen(
                            followings = dynamic.upChips,
                            onSelectUp = { up ->
                                viewModel.openUpSpaceFromFollowing(up)
                                openUpSpaceRequests.tryEmit(Unit)
                                onBack()
                            },
                            onBack = onBack,
                        )
                    },
                    messagesContent = { onBack ->
                        val sessions by viewModel.messages.privateSessions.collectAsState()
                        MessageCenterScreen(
                            sessions = sessions,
                            unreadSummary = unreadSummary,
                            session = session,
                            loginState = loginState,
                            onInit = viewModel.messages::retryPrivateSessions,
                            onRetry = viewModel.messages::retryPrivateSessions,
                            onClose = onBack,
                            onStartLogin = viewModel::startLogin,
                            onCancelLogin = viewModel::cancelLogin,
                            onOpenChat = { s -> viewModel.messages.openPrivateChat(s) },
                            onOpenReplyFeed = viewModel.messages::openReplyFeed,
                            onOpenLikeFeed = viewModel.messages::openLikeFeed,
                            onOpenFollowerFeed = viewModel.messages::openFollowerFeed,
                        )
                    },
                    replyFeedContent = { onBack ->
                        val replyState by viewModel.messages.replyFeed.collectAsState()
                        ReplyAtScreen(
                            state = replyState,
                            onInit = viewModel.messages::retryReplyFeed,
                            onRetry = viewModel.messages::retryReplyFeed,
                            onBack = onBack,
                            onPlayItem = viewModel.messages::playReplyAtItem,
                            onSubmitReply = { aid, root, parent, content, onResult ->
                                viewModel.messages.submitCommentReply(aid, root, parent, content, onResult)
                            },
                            replyImages = viewModel.messages.replyImages.collectAsState().value,
                            replyStatus = viewModel.messages.replyStatus.collectAsState().value,
                            onAddReplyImages = viewModel.messages::addReplyImages,
                            onRemoveReplyImage = viewModel.messages::removeReplyImage,
                            onClearReplyImages = viewModel.messages::clearReplyImages,
                        )
                    },
                    likeFeedContent = { onBack ->
                        val likeState by viewModel.messages.likeFeed.collectAsState()
                        LikeScreen(
                            state = likeState,
                            onInit = viewModel.messages::retryLikeFeed,
                            onRetry = viewModel.messages::retryLikeFeed,
                            onBack = onBack,
                            onPlayItem = viewModel.messages::playLikeItem,
                        )
                    },
                    followerFeedContent = { onBack ->
                        val followerState by viewModel.messages.followerFeed.collectAsState()
                        FollowerScreen(
                            state = followerState,
                            onInit = viewModel.messages::retryFollowerFeed,
                            onRetry = viewModel.messages::retryFollowerFeed,
                            onBack = onBack,
                            onOpenSpace = { f ->
                                viewModel.messages.openFollowerSpace(f)
                                openUpSpaceRequests.tryEmit(Unit)
                            },
                        )
                    },
                    privateChatContent = { talkerId, peerName, peerFace, onBack ->
                        val chats by viewModel.messages.chatByTalker.collectAsState()
                        val state = chats[talkerId] ?: com.biliseek.app.message.PrivateChatState()
                        PrivateChatScreen(
                            peerName = peerName,
                            peerFace = peerFace,
                            currentUid = session.dedeUserId.toLongOrNull() ?: 0L,
                            state = state,
                            onBack = {
                                viewModel.messages.closeChat(talkerId)
                                onBack()
                            },
                            onOpen = { viewModel.messages.openChat(talkerId) },
                            onLoadMore = { viewModel.messages.loadMoreChat(talkerId) },
                            onDraftChange = { viewModel.messages.updateChatDraft(talkerId, it) },
                            onSend = { viewModel.messages.sendChatMessage(talkerId) },
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

                // 加入列表不再替用户选第一个：问清楚加到哪儿
                pendingBatchAdd?.let {
                    AddToListPickerDialog(
                        playlists = playlists,
                        favoriteFolders = favoriteFolders,
                        isLoggedIn = session.isLoggedIn,
                        onPickWatchLater = viewModel::confirmAddToWatchLater,
                        onPickPlaylist = viewModel::confirmAddToPlaylist,
                        onCreatePlaylist = viewModel::createPlaylistAndAdd,
                        onRenamePlaylist = viewModel::editPlaylist,
                        onDeletePlaylist = viewModel::deletePlaylist,
                        onPickFavorite = viewModel::confirmAddToFavoriteFolder,
                        onCreateFavorite = viewModel::createFavoriteFolderAndAdd,
                        onEditFavorite = viewModel::editFavoriteFolder,
                        onDeleteFavorite = viewModel::deleteFavoriteFolder,
                        deletingFavoriteMediaId = deletingFavoriteMediaId,
                        onDismiss = viewModel::dismissBatchAdd,
                    )
                }

                // AI 总结：风格弹窗与阅读页（工单 AI-6）
                summarySheet?.let { sheetState ->
                    com.biliseek.app.ui.SummaryStyleSheet(
                        state = sheetState,
                        onConfirm = viewModel.summary::confirm,
                        onDismiss = viewModel.summary::dismissSheet,
                        onOpenSettings = viewModel::requestOpenAiSettings,
                        onLogin = {
                            viewModel.summary.dismissSheet()
                            tabSwitchRequests.tryEmit(Destination.SETTINGS)
                        },
                    )
                }
                summaryReader?.let { target ->
                    val entity = summaryReaderState?.entity
                    // 换版本时正文要跟着换：以版本 id 为键，不让上一个版本的正文残留在新版本的标题下
                    var markdown by remember(target.bvid, target.cid, entity?.id) { mutableStateOf<String?>(null) }
                    var transcriptText by remember(target.bvid, target.cid) { mutableStateOf<String?>(null) }
                    LaunchedEffect(target.bvid, target.cid, entity?.id, entity?.status, entity?.updatedAt) {
                        markdown = entity?.let { viewModel.summary.readMarkdown(target.bvid, target.cid, it.id) }
                    }
                    val failureActions = entity?.let { row ->
                        com.biliseek.app.summary.FailureActionPolicy.actions(
                            kind = com.biliseek.app.summary.FailureKind.parse(row.failureKind)
                                ?: com.biliseek.app.summary.FailureKind.SOURCE,
                            hasTranscript = row.hasTranscript,
                            hasBackup = aiSettingsState.backupReady,
                            usingBackup = row.slot == com.biliseek.app.summary.EndpointSlot.BACKUP.name,
                            officialAvailable = false,
                        )
                    }.orEmpty()
                    com.biliseek.app.ui.SummaryReaderScreen(
                        state = summaryReaderState,
                        markdown = markdown,
                        transcript = transcriptText,
                        failureActions = failureActions,
                        onClose = viewModel.summary::closeReader,
                        onTimestamp = viewModel::openSummaryTimestamp,
                        onShare = {
                            entity?.let {
                                val file = viewModel.summaryManager.files(target.bvid, target.cid).summary(it.id)
                                com.biliseek.app.summary.SummaryShare.share(this@MainActivity, file, it.title)
                            }
                        },
                        onExport = {
                            markdown?.let { md ->
                                pendingSummaryExport = md
                                exportSummary.launch(com.biliseek.app.summary.SummaryShare.fileName(entity?.title.orEmpty()))
                            }
                        },
                        onRegenerate = { entity?.let(viewModel::regenerateSummary) },
                        onDelete = {
                            entity?.let {
                                viewModel.summary.deleteVersion(it.id, summaryReaderState?.versions?.size ?: 1)
                            }
                        },
                        onDeleteAll = { viewModel.summary.deleteAllVersions(target.bvid, target.cid) },
                        onSelectVersion = viewModel.summary::selectVersion,
                        onCancel = { entity?.let { viewModel.summaryManager.cancel(it.id) } },
                        onFailureAction = { action -> entity?.let { viewModel.onSummaryFailureAction(action, it) } },
                        onLoadTranscript = {
                            coroutineScope.launch {
                                transcriptText = viewModel.summary.readTranscriptText(target.bvid, target.cid)
                            }
                        },
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

                // Ch4：动态评论 / 播放页评论区
                for (comments in listOf(viewModel.dynamics.comments, viewModel.playerComments)) {
                    com.biliseek.app.ui.components.CommentThreadSheet(
                        controller = comments,
                        myMid = session.dedeUserId.toLongOrNull() ?: 0L,
                        onSaveImage = viewModel::saveImageToAlbum,
                        onOpenUser = { mid ->
                            val entry = topEntryId
                            // UP 空间里打开的评论区：再进 UP 空间会复用同一页面，回不到原处，照旧关掉
                            if (entry == null || topRoute == com.biliseek.app.ui.Destination.UP_SPACE.route) {
                                comments.dismiss()
                            } else {
                                comments.hideUntilBackOn(entry)
                            }
                            viewModel.openUpSpace(SearchUser(mid = mid, uname = ""))
                            openUpSpaceRequests.tryEmit(Unit)
                        },
                        // 播放页评论区、正在看画面时才能截当前帧当配图
                        onCaptureFrame = if (
                            comments === viewModel.playerComments &&
                            com.biliseek.app.comment.canCaptureVideoFrame(playback.mode, playback.presentation)
                        ) {
                            {
                                coroutineScope.launch {
                                    comments.addVideoFrame(com.biliseek.app.ui.captureVideoFrame(surfaceView))
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
                val currentPreviewImage = previewImage
                if (currentPreviewImage != null) {
                    BackHandler(onBack = viewModel::dismissImagePreview)
                    com.biliseek.app.ui.components.ImagePreviewDialog(
                        pics = currentPreviewImage.pics,
                        initialIndex = currentPreviewImage.initialIndex,
                        onDismiss = viewModel::dismissImagePreview,
                        onSaveImage = viewModel::saveImageToAlbum,
                    )
                }

                // Ch5：播放列表 sheet
                if (queueSheetOpen) {
                    com.biliseek.app.ui.components.PlayerQueueSheet(
                        state = playback,
                        onSkipTo = viewModel::queueSkipTo,
                        onClearQueue = viewModel::clearQueue,
                        onRemove = viewModel::removeFromQueue,
                        onDismiss = viewModel::closeQueueSheet,
                        snackbarHostState = queueSheetSnackbar,
                    )
                }

                // 下载前先确认内容与保存位置
                (pendingDownload ?: downloadFor?.let { listOf(it) })?.let { targets ->
                    DownloadDialog(
                        targets = targets,
                        settings = settings,
                        onDismiss = {
                            downloadFor = null
                            viewModel.dismissPendingDownload()
                        },
                        onConfirm = { requests ->
                            val location = requests.firstOrNull()?.location
                            if (location == com.biliseek.app.data.DownloadLocation.PUBLIC &&
                                needsStoragePermission()
                            ) {
                                pendingStorageDownload = requests
                                requestStoragePermission.launch(
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                )
                            } else {
                                viewModel.enqueueDownloads(requests)
                            }
                        },
                        onRememberLocation = viewModel::setDownloadLocation,
                        onRememberSafTree = viewModel::setDownloadSafTreeUri,
                    )
                }
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BiliSeek", text))
    }

    private fun showLoginHint() = showReplacingToast("请在设置中登录")

    /** 弹一条普通提示：走和 `transientMessage` 同一条管道（前台是 App 内提示、在迷你条上方），新的替换旧的。 */
    private fun showReplacingToast(text: String) {
        appMessages.tryEmit(text)
    }

    /** 弹一条系统 Toast（App 在后台 / 小窗里时用）；先取消上一条，所以连点只显示最新的。 */
    private fun showSystemToast(text: String) {
        replacingToast?.cancel()
        replacingToast = android.widget.Toast
            .makeText(this, text, android.widget.Toast.LENGTH_SHORT)
            .also { it.show() }
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
        handleSummaryIntent(intent)
    }

    /** 总结通知点进来：打开对应的阅读页，或「去设置」打开 AI 总结设置。 */
    private fun handleSummaryIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(com.biliseek.app.summary.SummaryService.EXTRA_OPEN_AI_SETTINGS, false)) {
            intent.removeExtra(com.biliseek.app.summary.SummaryService.EXTRA_OPEN_AI_SETTINGS)
            viewModel.requestOpenAiSettings()
            return
        }
        val bvid = intent.getStringExtra(com.biliseek.app.summary.SummaryService.EXTRA_OPEN_BVID) ?: return
        val cid = intent.getLongExtra(com.biliseek.app.summary.SummaryService.EXTRA_OPEN_CID, 0L)
        intent.removeExtra(com.biliseek.app.summary.SummaryService.EXTRA_OPEN_BVID)
        if (bvid.isNotBlank() && cid > 0) viewModel.summary.openReader(bvid, cid)
    }

    private fun handleSharedIntent(intent: Intent?) {
        val text = sharedTextFrom(
            action = intent?.action,
            type = intent?.type,
            extraText = intent?.getStringExtra(Intent.EXTRA_TEXT),
            dataString = intent?.dataString,
        ) ?: return
        viewModel.search.handleSharedText(text)
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
        val (num, den) = com.biliseek.app.ui.resolvePipAspectRatio(
            viewModel.connection.state.value.videoAspectRatio,
        )
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(num, den))
        // 从画面当前位置放大/缩小进小窗，而不是从整个窗口
        videoSurface?.let { view ->
            val rect = android.graphics.Rect()
            if (view.isAttachedToWindow && view.getGlobalVisibleRect(rect)) {
                builder.setSourceRectHint(rect)
            }
        }
        runCatching { enterPictureInPictureMode(builder.build()) }
    }

    /**
     * 离开 App 时的处置。
     *
     * 看模式下若支持 PiP 则进 PiP（呈现态仍为可见，**不回落音频**）；
     * 否则回落纯音频，避免解码没人看的画面。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
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
        inPipState.value = isInPictureInPictureMode
        viewModel.onPipChanged(isInPictureInPictureMode)
    }

    override fun onStop() {
        super.onStop()
        // PiP 窗口仍可见，不回落；真正不可见了才降回纯音频
        if (!wasInPip && !isInPictureInPictureMode) viewModel.onEnterBackground()
    }
}
