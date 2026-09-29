package com.biliseek.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import com.biliseek.designsystem.theme.AdaptiveAccentColorPolicy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.biliseek.core.player.PlaybackMode
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow

/** 四个入口 + 两个 push 型 destination（Player、UP 空间页）。 */
enum class Destination(val route: String, val label: String) {
    SEARCH("search", "搜索"),
    DYNAMIC("dynamic", "动态"),
    LIBRARY("library", "列表"),
    SETTINGS("settings", "设置"),
    // Player 是 nav 栈上的一个 destination，点视频时 push、back 时 pop。
    // 与 MiniPlayer（仍在 Scaffold bottomBar 外挂）的区别：player route 负责全屏
    // 展开态的渲染与返回手势，M12-A 之前是手动 expansion 状态机控制。
    PLAYER("player", "播放器"),
    // UP 空间页：从动态页或搜索页点 UP 主头像/昵称进入，back 返回来源 tab。
    UP_SPACE("upSpace", "UP主页"),
    FOLLOWING_LIST("followingList", "关注列表"),
    /** 消息中心（push，不入底部导航）。 */
    MESSAGES("messages", "消息"),
    /** 回复与@ 子页面（push，从消息中心图标进入）。 */
    MESSAGE_REPLY("messageReply", "回复与@"),
    /** 收到喜欢 子页面（push）。 */
    MESSAGE_LIKE("messageLike", "收到喜欢"),
    /** 新增粉丝 子页面（push）。 */
    MESSAGE_FOLLOWER("messageFollower", "新增粉丝"),
    /** 私信聊天页（push，参数化 talkerId/peerName/peerFace）。 */
    PRIVATE_CHAT("privateChat/{talkerId}/{peerName}/{peerFace}", "私信"),
    ;

    /** 底部导航图标：选中实心、未选中描边（M3 导航栏惯例）。只有四个 tab 会用到。 */
    fun navIcon(selected: Boolean): ImageVector = when (this) {
        SEARCH -> Icons.Filled.Search
        DYNAMIC -> if (selected) Icons.Filled.DynamicFeed else Icons.Outlined.DynamicFeed
        LIBRARY -> if (selected) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic
        SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
        else -> Icons.Outlined.Circle
    }

    companion object {
        fun privateChatRoute(talkerId: Long, peerName: String, peerFace: String): String =
            "privateChat/$talkerId/${java.net.URLEncoder.encode(peerName, "UTF-8")}/${java.net.URLEncoder.encode(peerFace, "UTF-8")}"
    }
}

/**
 * 应用骨架。
 *
 * 结构见《架构设计篇》§7.2：
 * ```
 * Box
 *  ├─ Scaffold(NavHost + MiniPlayer + BottomNavigation)   ← 保持原状
 *  └─ FullscreenPlayerOverlay                             ← 仅全屏时存在
 * ```
 *
 * **MiniPlayer 挂在 NavHost 之外**，页面切换时不重建，播放不中断。
 * 全屏提升为顶层 overlay，退出即销毁，无需恢复底部导航状态。
 *
 * M12-A 改造：把 EXPANDED 提升为 NavController 的一个 destination（PLAYER），
 * 由 NavController 接管 back 语义。DYNAMIC 点视频后 1 次返回 = 回 DYNAMIC（带 MiniPlayer），
 * 不再跳到 SEARCH。详见《架构设计篇》§7.2 更新版。
 */
@Composable
fun AppShell(
    playback: PlaybackState,
    expansion: PlayerExpansion,
    surfaceView: SurfaceView,
    onExpansionChange: (PlayerExpansion) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPresentationChange: (PresentationMode, SurfaceView?) -> Unit,
    onEnterPip: () -> Unit = {},
    onSkipNext: () -> Unit = {},
    onSkipPrevious: () -> Unit = {},
    onCyclePlayMode: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onSetSpeed: (Float) -> Unit = {},
    onSetSleepTimer: (Long) -> Unit = {},
    onRetryPlayback: () -> Unit = {},
    /** 打开播放列表 sheet。 */
    onOpenQueue: () -> Unit = {},
    /** 打开评论区。 */
    onOpenComments: () -> Unit = {},
    /** 分享当前视频。 */
    onShare: () -> Unit = {},
    /** AI 总结当前视频（工单 AI-6）。 */
    onSummary: () -> Unit = {},
    /** 播放页视频互动栏（点赞 / 投币 / 收藏）状态与动作。 */
    videoInteraction: com.biliseek.app.interaction.VideoInteractionState? = null,
    videoActionHandlers: VideoActionHandlers = VideoActionHandlers(),
    /** 添加到列表（B站收藏夹 / 本地歌单二选一，走 AddToListPickerDialog）。 */
    onOpenAddToList: () -> Unit = {},
    /** 打开 UP 空间页。 */
    onOpenUpSpace: () -> Unit = {},
    /** 下载当前在播的视频（走与搜索结果相同的下载弹窗）。 */
    onDownloadCurrent: () -> Unit = {},
    /** 切画质（只锁定已有视频轨，不重新取流）。 */
    onSetVideoQuality: (Int) -> Unit = {},
    /** 设置页的默认音质。播放页里临时选的与它不同时，提供「设为默认」。 */
    defaultAudioQuality: com.biliseek.core.player.AudioQuality = com.biliseek.core.player.AudioQuality.AUTO,
    /** 播放页换音质：本次会话生效，当前视频带进度重新取流。 */
    onSetAudioQuality: (com.biliseek.core.player.AudioQuality) -> Unit = {},
    /** 把音质写进设置页默认值。 */
    onSetDefaultAudioQuality: (com.biliseek.core.player.AudioQuality) -> Unit = {},
    /** 全屏弹幕：已过滤排序的弹幕、显示设置与修改入口。 */
    danmakuItems: List<com.biliseek.core.network.dto.DanmakuItem> = emptyList(),
    danmakuSettings: com.biliseek.app.danmaku.DanmakuSettings = com.biliseek.app.danmaku.DanmakuSettings(),
    onDanmakuSettingsChange: (
        (com.biliseek.app.danmaku.DanmakuSettings) -> com.biliseek.app.danmaku.DanmakuSettings,
    ) -> Unit = {},
    /** 全屏弹幕开关状态。 */
    danmakuEnabled: Boolean = false,
    /** 全屏弹幕开关切换。 */
    onDanmakuToggle: () -> Unit = {},
    /** Activity 当前处于画中画。此时只渲染画面，不渲染任何控件。 */
    isInPip: Boolean = false,
    /** Ch5：minibar 风格（FLOAT/BOTTOM）。 */
    miniBarStyle: com.biliseek.app.data.MiniBarStyle = com.biliseek.app.data.MiniBarStyle.FLOAT,
    /**
     * 「开始播放」信号。AppShell 订阅后 push PLAYER destination。
     * 传 SharedFlow 而不是 () -> Unit 回调：同一个源可被多个 LaunchedEffect 订阅，
     * 且让起播事件的产生与响应解耦——ViewModel 不知道 navigation 存在。
     */
    playbackStarted: SharedFlow<Unit> = MutableSharedFlow(),
    /**
     * 程序化切换 tab（如首页快捷入口「近期歌单」→ 列表 tab）。
     * 与 [playbackStarted] 同理：AppShell 拥有 navController，响应这里。
     */
    tabSwitchRequests: SharedFlow<Destination> = MutableSharedFlow(),
    /**
     * 打开 UP 空间页的信号。AppShell 订阅后 push UP_SPACE destination。
     * 与 [playbackStarted] 同理：AppShell 拥有 navController，响应这里。
     */
    openUpSpaceRequests: SharedFlow<Unit> = MutableSharedFlow(),
    /**
     * 打开关注列表页的信号。AppShell 订阅后 push FOLLOWING_LIST destination。
     */
    openFollowingListRequests: SharedFlow<Unit> = MutableSharedFlow(),
    /** 打开消息中心的信号。AppShell 订阅后 push MESSAGES destination。 */
    openMessagesRequests: SharedFlow<Unit> = MutableSharedFlow(),
    /** 打开"回复与@"子页面的信号。AppShell 订阅后 push MESSAGE_REPLY destination。 */
    openReplyFeedRequests: SharedFlow<Unit> = MutableSharedFlow(),
    /** 打开"收到喜欢"子页面的信号。AppShell 订阅后 push MESSAGE_LIKE destination。 */
    openLikeFeedRequests: SharedFlow<Unit> = MutableSharedFlow(),
    /** 打开"新增粉丝"子页面的信号。AppShell 订阅后 push MESSAGE_FOLLOWER destination。 */
    openFollowerFeedRequests: SharedFlow<Unit> = MutableSharedFlow(),
    /** 进入私信聊天页的信号（参数化）。AppShell 订阅后 push PRIVATE_CHAT destination。 */
    openPrivateChatRequests: SharedFlow<com.biliseek.app.message.PrivateChatNav> = MutableSharedFlow(),
    /** 当前列表 tab 选中的页。 */
    selectedTab: LibraryTab = LibraryTab.PLAYLISTS,
    /** 列表 tab 切换回调。 */
    onTabSelected: (LibraryTab) -> Unit = {},
    searchContent: @Composable () -> Unit,
    dynamicContent: @Composable () -> Unit,
    libraryContent: @Composable (LibraryTab, (LibraryTab) -> Unit) -> Unit,
    settingsContent: @Composable () -> Unit,
    /** UP 空间页内容。onBack 由 AppShell 传入（navController.popBackStack()）。 */
    upSpaceContent: @Composable (onBack: () -> Unit) -> Unit = { },
    /** 关注列表页内容。onBack 由 AppShell 传入（navController.popBackStack()）。 */
    followingListContent: @Composable (onBack: () -> Unit) -> Unit = { },
    /** 消息中心页内容。onBack 由 AppShell 传入。 */
    messagesContent: @Composable (onBack: () -> Unit) -> Unit = { },
    /** 回复与@ 子页面内容。onBack 由 AppShell 传入。 */
    replyFeedContent: @Composable (onBack: () -> Unit) -> Unit = { },
    /** 收到喜欢 子页面内容。onBack 由 AppShell 传入。 */
    likeFeedContent: @Composable (onBack: () -> Unit) -> Unit = { },
    /** 新增粉丝 子页面内容。onBack 由 AppShell 传入。 */
    followerFeedContent: @Composable (onBack: () -> Unit) -> Unit = { },
    /** 私信聊天页内容。onBack 由 AppShell 传入。 */
    privateChatContent: @Composable (talkerId: Long, peerName: String, peerFace: String, onBack: () -> Unit) -> Unit = { _, _, _, _ -> },
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination
    // MiniPlayer 常驻显示：只要有播放内容就显示。
    // 全屏模式或正在播放页（PLAYER route）时隐藏。
    val showMiniPlayer = playback.mode != PlaybackMode.IDLE &&
        expansion != PlayerExpansion.FULLSCREEN &&
        currentRoute?.route != Destination.PLAYER.route

    // 只拦全屏态的返回。EXPANDED 已变为 PLAYER destination，由 NavController pop 处理。
    BackHandler(enabled = expansion == PlayerExpansion.FULLSCREEN) {
        onExpansionChange(PlayerExpansion.EXPANDED)
    }

    // 起播不再自动跳转播放页——minibar 常驻显示曲目信息，点击 minibar 才进播放页。

    // 程序化 tab 切换（快捷入口「近期歌单」）。
    LaunchedEffect(tabSwitchRequests) {
        tabSwitchRequests.collect { dest ->
            navController.navigate(dest.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // UP 空间页导航：收到信号后 push UP_SPACE destination（launchSingleTop 防重复压栈）。
    LaunchedEffect(openUpSpaceRequests) {
        openUpSpaceRequests.collect {
            navController.navigate(Destination.UP_SPACE.route) {
                launchSingleTop = true
            }
        }
    }

    // 关注列表页导航：收到信号后 push FOLLOWING_LIST destination。
    LaunchedEffect(openFollowingListRequests) {
        openFollowingListRequests.collect {
            navController.navigate(Destination.FOLLOWING_LIST.route) {
                launchSingleTop = true
            }
        }
    }

    // 消息中心导航：收到信号后 push MESSAGES destination（launchSingleTop 防重复压栈）。
    LaunchedEffect(openMessagesRequests) {
        openMessagesRequests.collect {
            navController.navigate(Destination.MESSAGES.route) {
                launchSingleTop = true
            }
        }
    }

    // "回复与@" 子页面导航：收到信号后 push MESSAGE_REPLY destination。
    LaunchedEffect(openReplyFeedRequests) {
        openReplyFeedRequests.collect {
            navController.navigate(Destination.MESSAGE_REPLY.route) {
                launchSingleTop = true
            }
        }
    }

    // "收到喜欢" 子页面导航：收到信号后 push MESSAGE_LIKE destination。
    LaunchedEffect(openLikeFeedRequests) {
        openLikeFeedRequests.collect {
            navController.navigate(Destination.MESSAGE_LIKE.route) {
                launchSingleTop = true
            }
        }
    }

    // "新增粉丝" 子页面导航：收到信号后 push MESSAGE_FOLLOWER destination。
    LaunchedEffect(openFollowerFeedRequests) {
        openFollowerFeedRequests.collect {
            navController.navigate(Destination.MESSAGE_FOLLOWER.route) {
                launchSingleTop = true
            }
        }
    }

    // 私信聊天导航：参数化路径 + launchSingleTop。
    LaunchedEffect(openPrivateChatRequests) {
        openPrivateChatRequests.collect { nav ->
            val route = Destination.privateChatRoute(nav.talkerId, nav.peerName, nav.peerFace)
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                Column {
                    if (showMiniPlayer) {
                        MiniPlayer(
                            state = playback,
                            onTogglePlayPause = onTogglePlayPause,
                            onSeek = onSeek,
                            onOpenQueue = onOpenQueue,
                            miniBarStyle = miniBarStyle,
                            // 点 MiniPlayer 跳 player route；EXPANDED 不再是状态
                            onExpand = {
                                navController.navigate(Destination.PLAYER.route) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                    NavigationBar {
                        Destination.entries.forEach { dest ->
                            // PLAYER / UP_SPACE / FOLLOWING_LIST 不在底部导航中点击跳转，仅作为 nav destination 存在
                            if (dest == Destination.PLAYER || dest == Destination.UP_SPACE ||
                                dest == Destination.FOLLOWING_LIST || dest == Destination.MESSAGES ||
                                dest == Destination.PRIVATE_CHAT || dest == Destination.MESSAGE_REPLY ||
                                dest == Destination.MESSAGE_LIKE || dest == Destination.MESSAGE_FOLLOWER
                            ) return@forEach
                            NavigationBarItem(
                                selected = currentRoute?.hierarchy?.any { it.route == dest.route } == true,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    val selected = currentRoute?.hierarchy?.any { it.route == dest.route } == true
                                    Icon(dest.navIcon(selected), contentDescription = null)
                                },
                                label = { Text(dest.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Destination.SEARCH.route,
                modifier = Modifier.padding(padding),
            ) {
                composable(Destination.SEARCH.route) { searchContent() }
                composable(Destination.DYNAMIC.route) { dynamicContent() }
                composable(Destination.LIBRARY.route) { libraryContent(selectedTab, onTabSelected) }
                composable(Destination.SETTINGS.route) { settingsContent() }
                composable(Destination.PLAYER.route) {
                    // 只有进入 player route 才计算取色，避免每个 tab 都跳调色
                    val accent = rememberAccentDecision(rememberCoverPalette(playback.coverUrl))

                    // z-ordering fix: 如果当前处于视频模式，先切回音频模式
                    // （触发 clearVideoSurface），等状态更新后再 popBackStack。
                    // 否则 SurfaceView 的 surface 在 exit 动画期间仍被 SurfaceFlinger
                    // 合成，覆盖整个屏幕。
                    var pendingCollapse by remember { mutableStateOf(false) }
                    LaunchedEffect(playback.presentation) {
                        if (pendingCollapse && playback.presentation == PresentationMode.NONE) {
                            pendingCollapse = false
                            navController.popBackStack()
                        }
                    }

                    PlayerScreen(
                        state = playback,
                        surfaceView = surfaceView,
                        onCollapse = {
                            if (playback.presentation != PresentationMode.NONE) {
                                onPresentationChange(PresentationMode.NONE, null)
                                pendingCollapse = true
                            } else {
                                navController.popBackStack()
                            }
                        },
                        onTogglePlayPause = onTogglePlayPause,
                        onPresentationChange = onPresentationChange,
                        onEnterPip = onEnterPip,
                        onSkipNext = onSkipNext,
                        onSkipPrevious = onSkipPrevious,
                        onCyclePlayMode = onCyclePlayMode,
                        onSeek = onSeek,
                        onSetSpeed = onSetSpeed,
                        onSetSleepTimer = onSetSleepTimer,
                        onRetryPlayback = onRetryPlayback,
                        onEnterFullscreen = { onExpansionChange(PlayerExpansion.FULLSCREEN) },
                        onOpenQueue = onOpenQueue,
                        onOpenComments = onOpenComments,
                        onShare = onShare,
                        onSummary = onSummary,
                        onOpenAddToList = onOpenAddToList,
                        onOpenUpSpace = onOpenUpSpace,
                        onDownload = onDownloadCurrent,
                        onSetVideoQuality = onSetVideoQuality,
                        defaultAudioQuality = defaultAudioQuality,
                        onSetAudioQuality = onSetAudioQuality,
                        onSetDefaultAudioQuality = onSetDefaultAudioQuality,
                        accent = accent,
                        interaction = videoInteraction,
                        interactionHandlers = videoActionHandlers,
                        // 全屏层或 PiP 层接管 SurfaceView 时，内联封面区让路
                        isFullscreenActive = expansion == PlayerExpansion.FULLSCREEN || isInPip,
                    )
                }
                composable(Destination.UP_SPACE.route) {
                    upSpaceContent({ navController.popBackStack() })
                }
                composable(Destination.FOLLOWING_LIST.route) {
                    followingListContent({ navController.popBackStack() })
                }
                composable(Destination.MESSAGES.route) {
                    messagesContent({ navController.popBackStack() })
                }
                composable(Destination.MESSAGE_REPLY.route) {
                    replyFeedContent({ navController.popBackStack() })
                }
                composable(Destination.MESSAGE_LIKE.route) {
                    likeFeedContent({ navController.popBackStack() })
                }
                composable(Destination.MESSAGE_FOLLOWER.route) {
                    followerFeedContent({ navController.popBackStack() })
                }
                composable(
                    Destination.PRIVATE_CHAT.route,
                    arguments = listOf(
                        navArgument("talkerId") { type = NavType.LongType },
                        navArgument("peerName") { type = NavType.StringType },
                        navArgument("peerFace") { type = NavType.StringType },
                    ),
                ) { backStackEntry ->
                    val talkerId = backStackEntry.arguments?.getLong("talkerId") ?: 0L
                    val peerName = backStackEntry.arguments?.getString("peerName").orEmpty()
                    val peerFace = backStackEntry.arguments?.getString("peerFace").orEmpty()
                    privateChatContent(
                        talkerId,
                        java.net.URLDecoder.decode(peerName, "UTF-8"),
                        java.net.URLDecoder.decode(peerFace, "UTF-8"),
                    ) { navController.popBackStack() }
                }
            }
        }

        // 全屏提升到 Scaffold 之上（《架构设计篇》§7.2）。Scaffold 原样不动，
        // 退出即销毁，不必逐个恢复底部导航与滚动位置
        if (expansion == PlayerExpansion.FULLSCREEN) {
            FullscreenPlayerOverlay(
                surfaceView = surfaceView,
                state = playback,
                danmakuEnabled = danmakuEnabled,
                onDanmakuToggle = onDanmakuToggle,
                onExit = { onExpansionChange(PlayerExpansion.EXPANDED) },
                onTogglePlayPause = onTogglePlayPause,
                onSeek = onSeek,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onEnterPip = onEnterPip,
                isInPip = isInPip,
                onSetSpeed = onSetSpeed,
                onSetSleepTimer = onSetSleepTimer,
                onRetryPlayback = onRetryPlayback,
                onDownload = onDownloadCurrent,
                onAddToList = onOpenAddToList,
                onShare = onShare,
                onViewAuthor = onOpenUpSpace,
                onSetVideoQuality = onSetVideoQuality,
                defaultAudioQuality = defaultAudioQuality,
                onSetAudioQuality = onSetAudioQuality,
                onSetDefaultAudioQuality = onSetDefaultAudioQuality,
                danmakuItems = danmakuItems,
                danmakuSettings = danmakuSettings,
                onDanmakuSettingsChange = onDanmakuSettingsChange,
            )
        } else if (isInPip && playback.presentation != PresentationMode.NONE) {
            PipVideoOverlay(surfaceView = surfaceView, state = playback)
        }
    }
}
