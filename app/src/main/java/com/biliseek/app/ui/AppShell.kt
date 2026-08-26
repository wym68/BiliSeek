package com.biliseek.app.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode

/** 三个入口。没有第四个——没有推荐页。 */
enum class Destination(val route: String, val label: String) {
    SEARCH("search", "搜索"),
    LIBRARY("library", "音乐库"),
    SETTINGS("settings", "设置"),
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
    searchContent: @Composable () -> Unit,
    libraryContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    // 展开态下按返回键收回播放器，而不是退出页面——不动导航栈
    BackHandler(enabled = expansion != PlayerExpansion.COLLAPSED) {
        onExpansionChange(PlayerExpansion.COLLAPSED)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                Column {
                    if (expansion == PlayerExpansion.COLLAPSED) {
                        PlayerContainer(
                            state = playback,
                            expansion = PlayerExpansion.COLLAPSED,
                            surfaceView = surfaceView,
                            onExpansionChange = onExpansionChange,
                            onTogglePlayPause = onTogglePlayPause,
                            onPresentationChange = onPresentationChange,
                        )
                    }
                    NavigationBar {
                        Destination.entries.forEach { dest ->
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
                composable(Destination.LIBRARY.route) { libraryContent() }
                composable(Destination.SETTINGS.route) { settingsContent() }
            }
        }

        // 展开态覆盖在 Scaffold 之上，底部导航保持原状
        if (expansion == PlayerExpansion.EXPANDED) {
            PlayerContainer(
                state = playback,
                expansion = PlayerExpansion.EXPANDED,
                surfaceView = surfaceView,
                onExpansionChange = onExpansionChange,
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
            )
        }

    }
}
