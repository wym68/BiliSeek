package com.biliseek.app.ui

import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.biliseek.core.player.PlaybackMode
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode
import com.biliseek.designsystem.theme.AdaptiveAccentColorPolicy
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow

/** 四个入口。没有推荐页——动态是「你关注的人」，不是算法推荐。 */
enum class Destination(val route: String, val label: String) {
    SEARCH("search", "搜索"),
    DYNAMIC("dynamic", "动态"),
    LIBRARY("library", "列表"),
    SETTINGS("settings", "设置"),
    // Player 是 nav 栈上的一个 destination，点视频时 push、back 时 pop。
    // 与 MiniPlayer（仍在 Scaffold bottomBar 外挂）的区别：player route 负责全屏
    // 展开态的渲染与返回手势，M12-A 之前是手动 expansion 状态机控制。
    PLAYER("player", "播放器"),
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
    onQueueSkipTo: (Int) -> Unit = {},
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
    /** 当前列表 tab 选中的页。 */
    selectedTab: LibraryTab = LibraryTab.PLAYLISTS,
    /** 列表 tab 切换回调。 */
    onTabSelected: (LibraryTab) -> Unit = {},
    searchContent: @Composable () -> Unit,
    dynamicContent: @Composable () -> Unit,
    libraryContent: @Composable (LibraryTab, (LibraryTab) -> Unit) -> Unit,
    settingsContent: @Composable () -> Unit,
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

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                Column {
                    if (showMiniPlayer) {
                        PlayerContainer(
                            state = playback,
                            expansion = PlayerExpansion.COLLAPSED,
                            surfaceView = surfaceView,
                            onExpansionChange = onExpansionChange,
                            onTogglePlayPause = onTogglePlayPause,
                            onPresentationChange = onPresentationChange,
                            // 点 MiniPlayer 跳 player route；EXPANDED 不再是状态，
                            // 设了反而让上面对的 showMiniPlayer 判走错。
                            onExpand = {
                                navController.navigate(Destination.PLAYER.route) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                    NavigationBar {
                        Destination.entries.forEach { dest ->
                            // PLAYER 入口不在底部导航中点击跳转，仅作为 nav destination 存在
                            if (dest == Destination.PLAYER) return@forEach
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
                                icon = { Text(dest.label.take(1)) },
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
                    val palette = rememberCoverPalette(playback.coverUrl)
                    val darkTheme = isSystemInDarkTheme()
                    val surface = MaterialTheme.colorScheme.surface
                    val accent = remember(palette.dominant, darkTheme, surface) {
                        AdaptiveAccentColorPolicy.resolve(palette.dominant, darkTheme, surface)
                    }
                    PlayerScreen(
                        state = playback,
                        surfaceView = surfaceView,
                        onCollapse = { navController.popBackStack() },
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
                        onQueueSkipTo = onQueueSkipTo,
                        onEnterFullscreen = { onExpansionChange(PlayerExpansion.FULLSCREEN) },
                        accent = accent,
                    )
                }
            }
        }

        // 全屏提升到 Scaffold 之上（《架构设计篇》§7.2）。Scaffold 原样不动，
        // 退出即销毁，不必逐个恢复底部导航与滚动位置
        if (expansion == PlayerExpansion.FULLSCREEN) {
            FullscreenPlayerOverlay(
                surfaceView = surfaceView,
                onExit = { onExpansionChange(PlayerExpansion.EXPANDED) },
            )
        }
    }
}
