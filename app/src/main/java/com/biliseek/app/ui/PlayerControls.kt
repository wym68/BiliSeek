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
 * 封面/视频区。音视频模式统一 16:9 容器，圆角差异化（音频 16dp，视频 8dp）。
 * 视频模式下右下角可显示全屏入口按钮（控制层可见时）。
 */
@Composable
internal fun CoverArea(
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
internal fun PlayerControlBar(
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
    // 上一首 / 播放 / 下一首的触感。全屏控制层用更轻的一档
    hapticType: HapticFeedbackType = HapticFeedbackType.LongPress,
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
                haptic.performHapticFeedback(hapticType)
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
                haptic.performHapticFeedback(hapticType)
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
                haptic.performHapticFeedback(hapticType)
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
internal fun PlayerBottomBar(
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
internal fun ProgressBar(
    state: PlaybackState,
    onSeek: (Long) -> Unit,
    // 全屏黑底下默认配色（蓝色滑块 + 深色文字）太扎眼，调用方按需覆盖。
    sliderColors: SliderColors = SliderDefaults.colors(),
    labelColor: Color = Color.Unspecified,
    // 全屏下默认的轨道粗细 + 圆点大小太显眼，调用方可以要一份更细的。
    compact: Boolean = false,
    // 拖动开始 / 结束。全屏控制层靠它在拖动期间暂停自动隐藏
    onDraggingChange: (Boolean) -> Unit = {},
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
                if (!dragging) onDraggingChange(true)
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                onSeek(dragValue.toLong())
                dragging = false
                onDraggingChange(false)
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
 * 视频按原始宽高比居中显示、不拉伸——比例未知时（还没拿到首帧尺寸）先铺满兜底，
 * 拿到之后立刻纠正。内联 CoverArea 和全屏 overlay 共用同一份逻辑。
 */
internal fun videoSurfaceModifier(videoAspectRatio: Float?): Modifier =
    if (videoAspectRatio != null && videoAspectRatio > 0f) {
        Modifier.aspectRatio(videoAspectRatio)
    } else {
        Modifier.fillMaxSize()
    }
