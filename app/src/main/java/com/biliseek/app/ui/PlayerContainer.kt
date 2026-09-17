package com.biliseek.app.ui

import android.view.SurfaceView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ModeComment
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.compose.AsyncImage
import com.biliseek.core.player.PlayMode
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode
import com.biliseek.core.player.formatDuration
import com.biliseek.designsystem.theme.AccentDecision
import com.biliseek.designsystem.theme.AdaptiveAccentColorPolicy
import com.biliseek.designsystem.theme.CoverPalette

/**
 * 播放器容器的展开程度。
 *
 * [FULLSCREEN] 的入口是**显式按钮**，不是手势。MVP 阶段用过「视频区上滑进全屏」，
 * 因触发判定有缺陷被整个移除（c006079）；重做时换成按钮，把那类误触从根上去掉。
 * 层级见《架构设计篇》§7.2。
 */
enum class PlayerExpansion { COLLAPSED, EXPANDED, FULLSCREEN }

@Composable
fun PlayerContainer(
    state: PlaybackState,
    expansion: PlayerExpansion,
    surfaceView: SurfaceView,
    onExpansionChange: (PlayerExpansion) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPresentationChange: (PresentationMode, SurfaceView?) -> Unit,
    modifier: Modifier = Modifier,
    onEnterPip: () -> Unit = {},
    onSkipNext: () -> Unit = {},
    onSkipPrevious: () -> Unit = {},
    onCyclePlayMode: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onSetSpeed: (Float) -> Unit = {},
    onSetSleepTimer: (Long) -> Unit = {},
    onRetryPlayback: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onOpenComments: () -> Unit = {},
    onShare: () -> Unit = {},
    onOpenAddToList: () -> Unit = {},
    onOpenUpSpace: () -> Unit = {},
    miniBarStyle: com.biliseek.app.data.MiniBarStyle = com.biliseek.app.data.MiniBarStyle.FLOAT,
    onExpand: () -> Unit = {},
) {
    when (expansion) {
        PlayerExpansion.COLLAPSED -> {
            val palette = rememberCoverPalette(state.coverUrl)
            val accent = rememberAccentDecision(palette)
            MiniPlayer(
                state = state,
                onExpand = onExpand,
                onTogglePlayPause = onTogglePlayPause,
                accent = accent,
                onOpenQueue = onOpenQueue,
                onSeek = onSeek,
                miniBarStyle = miniBarStyle,
                modifier = modifier,
            )
        }
        PlayerExpansion.FULLSCREEN -> Unit
        PlayerExpansion.EXPANDED -> {
            val palette = rememberCoverPalette(state.coverUrl)
            val accent = rememberAccentDecision(palette)
            PlayerScreen(
                state = state,
                surfaceView = surfaceView,
                onCollapse = { onExpansionChange(PlayerExpansion.COLLAPSED) },
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
                onOpenAddToList = onOpenAddToList,
                onOpenUpSpace = onOpenUpSpace,
                accent = accent,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun rememberAccentDecision(palette: CoverPalette): AccentDecision {
    val darkTheme = isSystemInDarkTheme()
    val surface = MaterialTheme.colorScheme.surface
    return remember(palette.dominant, darkTheme, surface) {
        AdaptiveAccentColorPolicy.resolve(palette.dominant, darkTheme, surface)
    }
}

/**
 * 全屏播放层。
 *
 * 调研依据：BiliPai `FullscreenPlayerOverlay`（4 秒自动隐藏、`originalOrientation` 保存恢复、
 * `LifecycleEventObserver` ON_RESUME 重应用 immersive、`FLAG_KEEP_SCREEN_ON`）。
 *
 * 底部控制行：左侧弹幕开关（UI 占位）、右侧退出全屏。
 */
private const val AUTO_HIDE_DELAY_MS = 4000L

@Composable
fun FullscreenPlayerOverlay(
    surfaceView: SurfaceView,
    state: PlaybackState,
    danmakuEnabled: Boolean,
    onDanmakuToggle: () -> Unit,
    onExit: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    modifier: Modifier = Modifier,
) {
    androidx.activity.compose.BackHandler { onExit() }

    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 未知宽高比时按 16:9 兜底（沿用原来的强制横屏行为）；已知的话竖屏视频
    // 强制竖屏、横屏视频强制横屏，而不是不分青红皂白全部转横屏。用"分类"
    // （而不是原始比例值）做 key，避免同一视频轻微的比例浮动反复重进这个 effect。
    val isPortraitVideo = (state.videoAspectRatio ?: (16f / 9f)) < 1f

    DisposableEffect(isPortraitVideo) {
        val activity = view.context.findActivity()
        val window = activity?.window
        val controller = window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, view)
        }
        val originalOrientation = activity?.requestedOrientation

        controller?.apply {
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        activity?.requestedOrientation = if (isPortraitVideo) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            if (activity != null && originalOrientation != null) {
                activity.requestedOrientation = originalOrientation
            }
        }
    }

    DisposableEffect(view) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val window = view.context.findActivity()?.window
                val controller = window?.let {
                    androidx.core.view.WindowCompat.getInsetsController(it, view)
                }
                controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(controlsVisible, lastInteractionTime) {
        if (controlsVisible && state.isPlaying) {
            kotlinx.coroutines.delay(AUTO_HIDE_DELAY_MS)
            if (System.currentTimeMillis() - lastInteractionTime >= AUTO_HIDE_DELAY_MS) {
                controlsVisible = false
            }
        }
    }

    DisposableEffect(state.isPlaying) {
        val activity = view.context.findActivity()
        if (state.isPlaying) {
            activity?.window?.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        onDispose {
            activity?.window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    Surface(color = Color.Black, modifier = modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        controlsVisible = !controlsVisible
                        lastInteractionTime = System.currentTimeMillis()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            PlayerSurface(surfaceView, videoSurfaceModifier(state.videoAspectRatio))

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
                exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(300)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.6f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.6f),
                                ),
                            )
                        )
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                lastInteractionTime = System.currentTimeMillis()
                                onExit()
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White,
                            )
                        }
                        Text(
                            state.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                        )
                    }

                    Column {
                        ProgressBar(
                            state,
                            onSeek,
                            sliderColors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            ),
                            labelColor = Color.White,
                            compact = true,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    lastInteractionTime = System.currentTimeMillis()
                                    onDanmakuToggle()
                                },
                            ) {
                                Icon(
                                    imageVector = if (danmakuEnabled) {
                                        Icons.Filled.Sms
                                    } else {
                                        Icons.Outlined.Sms
                                    },
                                    contentDescription = "弹幕开关",
                                    tint = if (danmakuEnabled) Color.White
                                           else Color.White.copy(alpha = 0.5f),
                                )
                            }

                            PlayerControlBar(
                                isPlaying = state.isPlaying,
                                isAudioMode = false,
                                onTogglePlayPause = {
                                    lastInteractionTime = System.currentTimeMillis()
                                    onTogglePlayPause()
                                },
                                onSkipPrevious = {
                                    lastInteractionTime = System.currentTimeMillis()
                                    onSkipPrevious()
                                },
                                onSkipNext = {
                                    lastInteractionTime = System.currentTimeMillis()
                                    onSkipNext()
                                },
                                onSwitchMode = {},
                                hasPrevious = hasPrevious,
                                hasNext = hasNext,
                                showModeSwitch = false,
                                iconColor = Color.White,
                                buttonSize = 36.dp,
                                iconSize = 22.dp,
                                mainButtonSize = 44.dp,
                                mainIconSize = 22.dp,
                                spacing = 16.dp,
                                verticalPadding = 4.dp,
                                fillWidth = false,
                            )

                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    lastInteractionTime = System.currentTimeMillis()
                                    onExit()
                                },
                            ) {
                                Icon(
                                    Icons.Filled.FullscreenExit,
                                    contentDescription = "退出全屏",
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    state: PlaybackState,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    accent: AccentDecision,
    onOpenQueue: () -> Unit,
    onSeek: (Long) -> Unit,
    miniBarStyle: com.biliseek.app.data.MiniBarStyle,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.mode != com.biliseek.core.player.PlaybackMode.IDLE,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        var revealed by remember { mutableStateOf(true) }

        val surfaceModifier = when (miniBarStyle) {
            com.biliseek.app.data.MiniBarStyle.FLOAT -> Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
                .shadow(elevation = 7.dp, shape = RoundedCornerShape(24.dp), clip = false)
                .clip(RoundedCornerShape(24.dp))
            com.biliseek.app.data.MiniBarStyle.BOTTOM -> Modifier
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        }
        Surface(
            tonalElevation = 3.dp,
            modifier = surfaceModifier.fillMaxWidth(),
        ) {
            AnimatedContent(
                targetState = revealed,
                transitionSpec = {
                    if (targetState) {
                        (slideInVertically { -it } + fadeIn()) togetherWith
                            (slideOutVertically { it } + fadeOut())
                    } else {
                        (slideInVertically { it } + fadeIn()) togetherWith
                            (slideOutVertically { -it } + fadeOut())
                    }
                },
                label = "minibar-reveal",
            ) { isRevealed ->
                if (isRevealed) {
                    Column {
                        MiniBarHandle(
                            onDrag = { dragAmount ->
                                if (dragAmount > HANDLE_DRAG_THRESHOLD) revealed = false
                            },
                        )
                        com.biliseek.app.ui.components.DraggableProgressBar(
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            onSeek = onSeek,
                            height = 2.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onExpand)
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures { _, dragAmount ->
                                        if (dragAmount < -DRAG_THRESHOLD) onExpand()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CoverThumbnail(state.coverUrl, width = 56.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    state.title.ifBlank { "BiliSeek" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    formatDuration(state.positionMs) + " / " +
                                        formatDuration(state.durationMs) +
                                        if (state.presentation != PresentationMode.NONE) " · 看" else " · 听",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            IconButton(onClick = onTogglePlayPause) {
                                Text(if (state.isPlaying) "⏸" else "▶")
                            }
                            IconButton(onClick = onOpenQueue) {
                                Text("☰")
                            }
                        }
                    }
                } else {
                    MiniBarHandle(
                        onDrag = { dragAmount ->
                            if (dragAmount < -HANDLE_DRAG_THRESHOLD) revealed = true
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniBarHandle(onDrag: (Float) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount -> onDrag(dragAmount) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(32.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

/**
 * 展开态播放器。
 *
 * 顶层可独立 compose 的函数：M12-A 重构后它被 NavHost 的 PLAYER destination 直接调用。
 *
 * 音视频模式统一 16:9 CoverArea 容器尺寸，布局完全一致，只有 CoverArea 内容不同。
 */
@Composable
fun PlayerScreen(
    state: PlaybackState,
    surfaceView: SurfaceView,
    onCollapse: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPresentationChange: (PresentationMode, SurfaceView?) -> Unit,
    onEnterPip: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onCyclePlayMode: () -> Unit,
    onSeek: (Long) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetSleepTimer: (Long) -> Unit,
    onRetryPlayback: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenComments: () -> Unit,
    onShare: () -> Unit,
    onOpenAddToList: () -> Unit,
    onOpenUpSpace: () -> Unit,
    accent: AccentDecision,
    modifier: Modifier = Modifier,
    // 全屏 overlay 是否处于激活状态：激活时 CoverArea 让出 SurfaceView 的挂载权，
    // 避免和 FullscreenPlayerOverlay 同时持有同一个 view（见 PlayerSurface.kt 的说明）。
    isFullscreenActive: Boolean = false,
) {
    var showMenu by remember { mutableStateOf(false) }
    val isAudio = state.presentation == PresentationMode.NONE

    Box(
        modifier
            .fillMaxSize()
            // 不再自己加 windowInsetsPadding：AppShell 里的 Scaffold 没有覆盖
            // contentWindowInsets，Material3 默认值是 safeDrawing，NavHost 传给
            // 这里的内容区已经把状态栏高度让出来了。这里如果再加一层，状态栏高度
            // 会被算两遍，顶部空出一大截。
            // 音频/视频模式背景统一用封面取色渐变——视频模式不整页变黑，
            // 只有 CoverArea 内部（视频帧本身的 letterbox）是黑底，其余顶部栏/
            // 标题/进度条/控制条/底栏都应该跟音频模式长一样。
            .background(
                Brush.verticalGradient(listOf(accent.gradientTop, accent.gradientBottom))
            ),
    ) {
        accent.scrim?.let { scrim ->
            Box(Modifier.fillMaxSize().background(scrim))
        }

        // 顶部栏 → CoverArea → 标题/进度条/控制条/底栏，统一放进同一条纵向流里、
        // 始终显示——音频/视频模式布局完全一致，只有 CoverArea 内容不同。
        // 不再各自作为 Box 的平级子项——否则它们会叠在一起画（曾经的重叠 bug）。
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerTopBar(
                onCollapse = onCollapse,
                onOpenMenu = { showMenu = true },
            )

            CoverArea(
                coverUrl = state.coverUrl,
                presentation = state.presentation,
                surfaceView = if (!isAudio) surfaceView else null,
                isResolving = state.isResolving,
                onEnterFullscreen = onEnterFullscreen,
                isFullscreenActive = isFullscreenActive,
                videoAspectRatio = state.videoAspectRatio,
            )

            Text(
                state.title.ifBlank { "BiliSeek" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            ProgressBar(state, onSeek)

            PlayerControlBar(
                isPlaying = state.isPlaying,
                isAudioMode = isAudio,
                onTogglePlayPause = onTogglePlayPause,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onSwitchMode = {
                    if (isAudio) {
                        onPresentationChange(PresentationMode.INLINE, surfaceView)
                    } else {
                        onPresentationChange(PresentationMode.NONE, null)
                    }
                },
                hasPrevious = state.hasPrevious,
                hasNext = state.hasNext,
            )

            PlayerBottomBar(
                playMode = state.playMode,
                onCyclePlayMode = onCyclePlayMode,
                onOpenQueue = onOpenQueue,
                onOpenComments = onOpenComments,
            )

            state.error?.let {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Color.Red)
                    Button(onClick = onRetryPlayback) { Text("重试") }
                }
            }
        }

        if (showMenu) {
            PlayerMoreMenuSheet(
                state = state,
                onDismiss = { showMenu = false },
                onSetSpeed = onSetSpeed,
                onSetSleepTimer = onSetSleepTimer,
                onDownload = {},
                onAddToList = onOpenAddToList,
                onViewAuthor = onOpenUpSpace,
                onShare = onShare,
            )
        }
    }
}

/**
 * 播放页顶部栏：收起 + 标题 + 三点菜单。
 */
@Composable
private fun PlayerTopBar(
    onCollapse: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起")
        }
        Text("正在播放", style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = onOpenMenu) {
            Icon(Icons.Filled.MoreVert, contentDescription = "菜单")
        }
    }
}

/**
 * 封面/视频区。音视频模式统一 16:9 容器，圆角差异化（音频 16dp，视频 8dp）。
 * 视频模式下右下角可显示全屏入口按钮（控制层可见时）。
 */
@Composable
private fun CoverArea(
    coverUrl: String,
    presentation: PresentationMode,
    surfaceView: SurfaceView?,
    isResolving: Boolean,
    onEnterFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    // 全屏 overlay 激活时，SurfaceView 的挂载权完全交给 FullscreenPlayerOverlay；
    // 这里必须让路，否则同一个 SurfaceView 被两处 AndroidView 同时争抢父容器，
    // 退出全屏后内联视频区会拿不回 view，黑屏。
    isFullscreenActive: Boolean = false,
    // 视频原始宽高比：按原比例居中显示，不拉伸变形，多出来的地方露黑边。
    videoAspectRatio: Float? = null,
) {
    val isAudio = presentation == PresentationMode.NONE
    val cornerRadius = if (isAudio) 16.dp else 8.dp
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .aspectRatio(16f / 9f)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (isAudio) {
            if (coverUrl.isNotBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (isResolving) {
                CircularProgressIndicator(color = Color.White)
            } else if (coverUrl.isBlank()) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(64.dp),
                )
            }
        } else {
            if (!isFullscreenActive) {
                surfaceView?.let { PlayerSurface(it, videoSurfaceModifier(videoAspectRatio)) }
            }

            // 控制层常显，全屏入口按钮跟着一直显示，不再跟"点击唤起"挂钩。
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.5f),
            ) {
                IconButton(onClick = onEnterFullscreen) {
                    Icon(
                        imageVector = Icons.Filled.Fullscreen,
                        contentDescription = "全屏",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * 播放控制条：上一首 / 播放 / 下一首 + 模式切换按钮。
 *
 * 调研要点（BBPlayer 参考）：
 * - Optimistic UI：点击立即翻转本地显示状态，prop 变化自动覆盖（`remember(isPlaying)`）
 * - Haptics：传输控制 LongPress / 模式切换 Confirm
 * - Skip 禁用态：!hasPrevious/hasNext 时透明度 0.38f + 不响应点击
 */
@Composable
private fun PlayerControlBar(
    isPlaying: Boolean,
    isAudioMode: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSwitchMode: () -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    modifier: Modifier = Modifier,
    // 全屏控制层没有听/看切换的概念，复用本组件但不渲染右侧模式切换按钮。
    showModeSwitch: Boolean = true,
    // 上一首/下一首图标颜色：全屏黑底下默认的 onSurface 太暗，调用方传白色。
    iconColor: Color = MaterialTheme.colorScheme.onSurface,
    // 全屏下默认尺寸太大太占地方，调用方可以整体传一份更小的。
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 32.dp,
    mainButtonSize: Dp = 56.dp,
    mainIconSize: Dp = 32.dp,
    spacing: Dp = 24.dp,
    verticalPadding: Dp = 16.dp,
    // 独占一整行时（正常播放页）要 fillMaxWidth 才能靠 Arrangement.Center 居中；
    // 嵌套在别的 Row 里由外层 SpaceBetween 负责居中时（全屏控制层），
    // fillMaxWidth 反而会把外层其他兄弟节点的空间占满，必须关掉，让本组件
    // 只按内容大小占位。
    fillWidth: Boolean = true,
) {
    var optimisticPlaying by remember(isPlaying) { mutableStateOf(isPlaying) }
    val displayPlaying = optimisticPlaying
    val haptic = LocalHapticFeedback.current

    Row(
        modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).padding(vertical = verticalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSkipPrevious()
            },
            modifier = Modifier.size(buttonSize),
            enabled = hasPrevious,
        ) {
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = "上一首",
                modifier = Modifier.size(iconSize),
                tint = if (hasPrevious) iconColor else iconColor.copy(alpha = 0.38f),
            )
        }
        Spacer(Modifier.width(spacing))
        FilledIconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                optimisticPlaying = !optimisticPlaying
                onTogglePlayPause()
            },
            modifier = Modifier.size(mainButtonSize),
            shape = CircleShape,
        ) {
            Icon(
                if (displayPlaying) Icons.Filled.Pause else Icons.Filled.PlayCircle,
                contentDescription = if (displayPlaying) "暂停" else "播放",
                modifier = Modifier.size(mainIconSize),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.width(spacing))
        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSkipNext()
            },
            modifier = Modifier.size(buttonSize),
            enabled = hasNext,
        ) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = "下一首",
                modifier = Modifier.size(iconSize),
                tint = if (hasNext) iconColor else iconColor.copy(alpha = 0.38f),
            )
        }
        if (showModeSwitch) {
            Spacer(Modifier.width(16.dp))
            TextButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onSwitchMode()
            }) {
                if (isAudioMode) {
                    Icon(Icons.Filled.PlayCircle, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("看")
                } else {
                    Icon(Icons.Filled.Headphones, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("退回听")
                }
            }
        }
    }
}

/**
 * 底栏：随机播放 / 评论 / 播放列表。
 */
@Composable
private fun PlayerBottomBar(
    playMode: PlayMode,
    onCyclePlayMode: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenComments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(onClick = onCyclePlayMode) {
            Icon(
                when (playMode) {
                    PlayMode.SHUFFLE -> Icons.Filled.Shuffle
                    PlayMode.REPEAT_ONE -> Icons.Filled.RepeatOne
                    else -> Icons.Filled.Repeat
                },
                contentDescription = "播放模式",
                tint = if (playMode != PlayMode.SEQUENTIAL) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onOpenComments) {
            Icon(Icons.Filled.ModeComment, contentDescription = "评论")
        }
        IconButton(onClick = onOpenQueue) {
            Icon(Icons.Filled.QueueMusic, contentDescription = "播放列表")
        }
    }
}

/**
 * 可拖动的进度条。
 *
 * **拖动期间必须用本地状态。** 250ms 的 `pushState()` 轮询会不停推来播放器的真实位置，
 * 若 `Slider` 直接绑 `state.positionMs`，手指还没松开就会被拽回去。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgressBar(
    state: PlaybackState,
    onSeek: (Long) -> Unit,
    // 全屏黑底下默认配色（蓝色滑块 + 深色文字）太扎眼，调用方按需覆盖。
    sliderColors: SliderColors = SliderDefaults.colors(),
    labelColor: Color = Color.Unspecified,
    // 全屏下默认的轨道粗细 + 圆点大小太显眼，调用方可以要一份更细的。
    compact: Boolean = false,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val duration = state.durationMs
    val max = duration.coerceAtLeast(1L).toFloat()
    val value = (if (dragging) dragValue else state.positionMs.toFloat()).coerceIn(0f, max)

    Column {
        Slider(
            value = value,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                onSeek(dragValue.toLong())
                dragging = false
            },
            valueRange = 0f..max,
            enabled = duration > 0,
            colors = sliderColors,
            thumb = {
                Box(
                    Modifier
                        .size(if (compact) 10.dp else 20.dp)
                        .background(sliderColors.thumbColor, CircleShape),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    colors = sliderColors,
                    modifier = Modifier.height(if (compact) 2.dp else 4.dp),
                )
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(value.toLong()), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
    }
}

/**
 * 三点菜单 ModalBottomSheet（BBPlayer `PlayerFunctionalMenu` 同款架构）。
 *
 * 调研要点：
 * - 顶部高频方块按钮（倍速/定时/下载）+ Divider + 低频列表项
 * - Curried closer：所有菜单项先 onDismiss 再执行动作（避免 sheet 动画冲突）
 * - Haptics：菜单项点击触发 LongPress
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlayerMoreMenuSheet(
    state: PlaybackState,
    onDismiss: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetSleepTimer: (Long) -> Unit,
    onDownload: () -> Unit,
    onAddToList: () -> Unit,
    onViewAuthor: () -> Unit,
    onShare: () -> Unit,
) {
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showTimerSheet by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    fun closeThen(action: () -> Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onDismiss()
        action()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MenuQuickButton(
                    icon = Icons.Filled.Speed,
                    label = "倍速",
                    onClick = { showSpeedSheet = true },
                    modifier = Modifier.weight(1f),
                )
                MenuQuickButton(
                    icon = Icons.Filled.Timer,
                    label = "定时关闭",
                    onClick = { showTimerSheet = true },
                    modifier = Modifier.weight(1f),
                )
                MenuQuickButton(
                    icon = Icons.Filled.Download,
                    label = "下载",
                    onClick = { closeThen(onDownload) },
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            MenuItemRow(Icons.Filled.PlaylistAdd, "添加到列表") { closeThen(onAddToList) }
            MenuItemRow(Icons.Filled.Person, "查看作者") { closeThen(onViewAuthor) }
            MenuItemRow(Icons.Filled.Share, "分享") { closeThen(onShare) }
        }
    }

    if (showSpeedSheet) {
        SpeedSelectionSheet(
            currentSpeed = state.speed,
            onSpeedSelected = { onSetSpeed(it); showSpeedSheet = false },
            onDismiss = { showSpeedSheet = false },
        )
    }

    if (showTimerSheet) {
        TimerSelectionSheet(
            isActive = state.sleepTimerActive,
            remainingMs = state.sleepTimerRemainingMs,
            endOfTrack = state.sleepTimerEndOfTrack,
            onSetTimer = { onSetSleepTimer(it); showTimerSheet = false },
            onDismiss = { showTimerSheet = false },
        )
    }
}

@Composable
private fun MenuQuickButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MenuItemRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSelectionSheet(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("倍速播放", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEEDS.forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(currentSpeed - speed) < 0.01f,
                        onClick = { onSpeedSelected(speed) },
                        label = { Text("${speed}×") },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TimerSelectionSheet(
    isActive: Boolean,
    remainingMs: Long?,
    endOfTrack: Boolean,
    onSetTimer: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("定时关闭", style = MaterialTheme.typography.titleMedium)
                if (isActive) {
                    TextButton(onClick = { onSetTimer(0) }) { Text("取消") }
                }
            }
            if (isActive) {
                Text(
                    buildString {
                        append("剩余 ")
                        remainingMs?.let { append(formatDuration(it)) }
                        if (endOfTrack) append(" · 播完当前")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SLEEP_MINUTES.forEach { minutes ->
                    FilterChip(
                        selected = false,
                        onClick = { onSetTimer(minutes * 60_000L) },
                        label = { Text("$minutes 分钟") },
                    )
                }
                FilterChip(
                    selected = endOfTrack,
                    onClick = { onSetTimer(-1) },
                    label = { Text("播完当前") },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 视频按原始宽高比居中显示、不拉伸——比例未知时（还没拿到首帧尺寸）先铺满兜底，
 * 拿到之后立刻纠正。内联 CoverArea 和全屏 overlay 共用同一份逻辑。
 */
private fun videoSurfaceModifier(videoAspectRatio: Float?): Modifier =
    if (videoAspectRatio != null && videoAspectRatio > 0f) {
        Modifier.aspectRatio(videoAspectRatio)
    } else {
        Modifier.fillMaxSize()
    }

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private val SLEEP_MINUTES = listOf(15, 30, 60)

private const val DRAG_THRESHOLD = 8f
private const val HANDLE_DRAG_THRESHOLD = 24f

/** Compose 里拿 Activity：主题包装后 context 往往是 ContextWrapper，得一层层剥。 */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}