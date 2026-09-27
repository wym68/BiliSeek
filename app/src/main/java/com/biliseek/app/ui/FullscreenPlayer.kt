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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.filled.PictureInPictureAlt
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
 * 全屏播放层。
 *
 * 调研依据：BiliPai `FullscreenPlayerOverlay`（4 秒自动隐藏、`originalOrientation` 保存恢复、
 * `LifecycleEventObserver` ON_RESUME 重应用 immersive）。
 *
 * 屏幕常亮不归这里管：内联看视频同样需要，统一由 MainActivity 按 [shouldKeepScreenOn] 设置。
 *
 * [isInPip] 为真时整层只剩画面：PiP 小窗里的控件既点不到也看不清。
 */
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
    onEnterPip: () -> Unit,
    modifier: Modifier = Modifier,
    isInPip: Boolean = false,
) {
    androidx.activity.compose.BackHandler(enabled = !isInPip) { onExit() }

    var controlsVisible by remember { mutableStateOf(true) }
    // 每次操作都换一个值，让自动隐藏的倒计时重新开始
    var interactionTick by remember { mutableStateOf(0L) }
    var draggingProgress by remember { mutableStateOf(false) }
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 用"分类"（而不是原始比例值）做 key，避免同一视频轻微的比例浮动反复重进这个 effect
    val orientation = resolveFullscreenOrientation(state.videoAspectRatio)

    fun touch() {
        interactionTick++
    }

    DisposableEffect(orientation) {
        val activity = view.context.findActivity()
        val window = activity?.window
        val controller = window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, view)
        }
        val originalOrientation = activity?.requestedOrientation
        // 刘海 / 挖孔屏：横屏时让画面铺进短边的刘海区，不留一条黑边
        val originalCutoutMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window?.attributes?.layoutInDisplayCutoutMode
        } else {
            null
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && window != null) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        controller?.apply {
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        activity?.requestedOrientation = when (orientation) {
            FullscreenOrientation.PORTRAIT ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            FullscreenOrientation.LANDSCAPE ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            if (activity != null && originalOrientation != null) {
                activity.requestedOrientation = originalOrientation
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P &&
                window != null && originalCutoutMode != null
            ) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = originalCutoutMode
                }
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

    // key 带上 isPlaying：暂停后恢复播放要重新开始倒计时，否则控制层一直挂着
    LaunchedEffect(controlsVisible, interactionTick, state.isPlaying, draggingProgress) {
        if (shouldAutoHideControls(controlsVisible, state.isPlaying, interacting = draggingProgress)) {
            kotlinx.coroutines.delay(AUTO_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }

    Surface(color = Color.Black, modifier = modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (isInPip) {
                        Modifier
                    } else {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures {
                                controlsVisible = !controlsVisible
                                touch()
                            }
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            PlayerSurface(surfaceView, videoSurfaceModifier(state.videoAspectRatio))

            if (isInPip) return@Box

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
                        // 横屏时刘海在左右两侧：画面铺进去，按钮不能
                        .windowInsetsPadding(WindowInsets.displayCutout)
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
                                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                onExit()
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "退出全屏",
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
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                onEnterPip()
                            },
                        ) {
                            Icon(
                                Icons.Filled.PictureInPictureAlt,
                                contentDescription = "小窗播放",
                                tint = Color.White,
                            )
                        }
                    }

                    Column {
                        ProgressBar(
                            state,
                            onSeek = {
                                touch()
                                onSeek(it)
                            },
                            sliderColors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            ),
                            labelColor = Color.White,
                            compact = true,
                            onDraggingChange = {
                                draggingProgress = it
                                touch()
                            },
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        touch()
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
                            }

                            PlayerControlBar(
                                isPlaying = state.isPlaying,
                                isAudioMode = false,
                                onTogglePlayPause = {
                                    touch()
                                    onTogglePlayPause()
                                },
                                onSkipPrevious = {
                                    touch()
                                    onSkipPrevious()
                                },
                                onSkipNext = {
                                    touch()
                                    onSkipNext()
                                },
                                onSwitchMode = {},
                                hasPrevious = state.hasPrevious,
                                hasNext = state.hasNext,
                                showModeSwitch = false,
                                iconColor = Color.White,
                                buttonSize = 36.dp,
                                iconSize = 22.dp,
                                mainButtonSize = 44.dp,
                                mainIconSize = 22.dp,
                                spacing = 16.dp,
                                verticalPadding = 4.dp,
                                fillWidth = false,
                                hapticType = HapticFeedbackType.VirtualKey,
                            )

                            Row(
                                Modifier.weight(1f),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {}
                        }
                    }
                }
            }
        }
    }
}

/**
 * 从展开态（内联看）进的 PiP：窗口里只留画面。
 *
 * 盖在整个 Scaffold 之上，而不是把 Scaffold 拿掉——拿掉会连带销毁 NavHost 里
 * 各页面的状态，PiP 回来滚动位置全丢。SurfaceView 的挂载权此时交给这里，
 * 内联 CoverArea 让路（同全屏）。
 */
@Composable
fun PipVideoOverlay(
    surfaceView: SurfaceView,
    state: PlaybackState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        PlayerSurface(surfaceView, videoSurfaceModifier(state.videoAspectRatio))
    }
}

private const val AUTO_HIDE_DELAY_MS = 4000L

/** Compose 里拿 Activity：主题包装后 context 往往是 ContextWrapper，得一层层剥。 */
internal tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
