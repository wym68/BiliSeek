package com.biliseek.app.ui

import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.core.player.PlayMode
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode
import com.biliseek.core.player.formatDuration

/**
 * 封面/视频区。音视频模式统一 16:9 容器，圆角差异化（音频 16dp，视频 8dp）。
 * 视频模式下右下角可显示全屏入口按钮（控制层可见时）；播放中长按画面临时加速、松手恢复（同全屏）。
 * 离开播放页时（[frozenFrame] 非空）显示截下的最后一帧顶替视频，和整页一起淡出
 * （设计稿：docs/superpowers/specs/2026-10-04-player-long-press-and-exit-design.md）。
 */
@Composable
internal fun CoverArea(
    coverUrl: String,
    presentation: PresentationMode,
    surfaceView: SurfaceView?,
    /** 取流中或缓冲中：封面上转圈。只看取流的话，切网缓冲时画面纹丝不动，像卡死了。 */
    isLoading: Boolean,
    onEnterFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    // 全屏 overlay 激活时，SurfaceView 的挂载权完全交给 FullscreenPlayerOverlay；
    // 这里必须让路，否则同一个 SurfaceView 被两处 AndroidView 同时争抢父容器，
    // 退出全屏后内联视频区会拿不回 view，黑屏。
    isFullscreenActive: Boolean = false,
    // 视频原始宽高比：按原比例居中显示，不拉伸变形，多出来的地方露黑边。
    videoAspectRatio: Float? = null,
    // 看模式下长按画面临时加速、松手恢复（见 LongPressSpeedBoost）。为空时不响应长按
    playback: State<PlaybackState>? = null,
    onSetSpeed: ((Float) -> Unit)? = null,
    // 离开播放页时截下的最后一帧：画面撤掉后顶着，和整页一起淡出；为空时照常显示封面
    frozenFrame: ImageBitmap? = null,
) {
    val isAudio = presentation == PresentationMode.NONE
    // 定格的那一帧要和视频一模一样，圆角也按视频的来
    val cornerRadius = if (isAudio && frozenFrame == null) 16.dp else 8.dp

    val haptic = LocalHapticFeedback.current
    var speedHud by remember { mutableStateOf<GestureHud?>(null) }
    val currentOnSetSpeed by rememberUpdatedState(onSetSpeed)
    val speedBoost = remember(playback) {
        playback?.let { state ->
            LongPressSpeedBoost(
                state = state,
                onSetSpeed = { currentOnSetSpeed?.invoke(it) },
                onHud = { speedHud = it },
                onBoosted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
            )
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .aspectRatio(16f / 9f)
            .background(Color.Black)
            .then(
                if (!isAudio && speedBoost != null) {
                    Modifier
                        .testTag("player_video")
                        .pointerInput(speedBoost) {
                            detectTapGestures(
                                onLongPress = { speedBoost.onLongPress() },
                                onPress = { speedBoost.awaitRelease(this) },
                            )
                        }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isAudio && frozenFrame != null) {
            Image(
                frozenFrame,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = videoSurfaceModifier(videoAspectRatio),
            )
        } else if (isAudio) {
            if (coverUrl.isNotBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (isLoading) {
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
            speedHud?.let { GestureHudBubble(it) }

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
 * 播放控制条：上一首 / 播放 / 下一首。全屏控制层在用；播放页用 [PlayerPageControlRow]。
 *
 * 调研要点（BBPlayer 参考）：
 * - Optimistic UI：点击立即翻转本地显示状态，prop 变化自动覆盖（`remember(isPlaying)`）
 * - Haptics：传输控制 LongPress
 * - Skip 禁用态：!hasPrevious/hasNext 时透明度 0.38f + 不响应点击
 */
@Composable
internal fun PlayerControlBar(
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    modifier: Modifier = Modifier,
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

    Row(
        modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).padding(vertical = verticalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton(Icons.Filled.SkipPrevious, "上一首", hasPrevious, iconColor, buttonSize, iconSize, hapticType, onSkipPrevious)
        Spacer(Modifier.width(spacing))
        PlayPauseButton(optimisticPlaying, mainButtonSize, mainIconSize, hapticType) {
            optimisticPlaying = !optimisticPlaying
            onTogglePlayPause()
        }
        Spacer(Modifier.width(spacing))
        SkipButton(Icons.Filled.SkipNext, "下一首", hasNext, iconColor, buttonSize, iconSize, hapticType, onSkipNext)
    }
}

/**
 * 播放页控制行：播放模式 · 上一首 · 播放 · 下一首 · 播放列表，五个按钮等间距一行排开。
 *
 * 左右两组宽度对称（48 / 56 / 72 / 56 / 48dp），所以播放键正好在屏幕中线、间距一致。
 * 听 / 看开关与倍速、定时在下面一排（[PlayerSwitchRow]）——六个按钮放一行既不能以播放键为轴对称、也放不下。
 */
@Composable
internal fun PlayerPageControlRow(
    isPlaying: Boolean,
    playMode: PlayMode,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onCyclePlayMode: () -> Unit,
    onOpenQueue: () -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    modifier: Modifier = Modifier,
) {
    var optimisticPlaying by remember(isPlaying) { mutableStateOf(isPlaying) }
    val haptic = LocalHapticFeedback.current
    val iconColor = MaterialTheme.colorScheme.onSurface

    Row(
        modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onCyclePlayMode()
            },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                playModeIcon(playMode),
                contentDescription = "播放模式：${playModeLabel(playMode)}",
                modifier = Modifier.size(26.dp),
                tint = if (playMode != PlayMode.SEQUENTIAL) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SkipButton(
            Icons.Filled.SkipPrevious, "上一首", hasPrevious, iconColor,
            56.dp, 34.dp, HapticFeedbackType.LongPress, onSkipPrevious,
        )
        PlayPauseButton(optimisticPlaying, 72.dp, 40.dp, HapticFeedbackType.LongPress) {
            optimisticPlaying = !optimisticPlaying
            onTogglePlayPause()
        }
        SkipButton(
            Icons.Filled.SkipNext, "下一首", hasNext, iconColor,
            56.dp, 34.dp, HapticFeedbackType.LongPress, onSkipNext,
        )
        IconButton(onClick = onOpenQueue, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "播放列表",
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/** 播放模式按钮上的图标：顺序播放是平的箭头，与循环类的三种分开（原来顺序和列表循环共用一个图标，只差颜色）。 */
private fun playModeIcon(mode: PlayMode): ImageVector = when (mode) {
    PlayMode.SEQUENTIAL -> Icons.AutoMirrored.Filled.ArrowRightAlt
    PlayMode.REPEAT_ALL -> Icons.Filled.Repeat
    PlayMode.REPEAT_ONE -> Icons.Filled.RepeatOne
    PlayMode.SHUFFLE -> Icons.Filled.Shuffle
}

/** 上一首 / 下一首。不可用时半透明且不响应点击。 */
@Composable
private fun SkipButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    iconColor: Color,
    buttonSize: Dp,
    iconSize: Dp,
    hapticType: HapticFeedbackType,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    IconButton(
        onClick = {
            haptic.performHapticFeedback(hapticType)
            onClick()
        },
        modifier = Modifier.size(buttonSize),
        enabled = enabled,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = if (enabled) iconColor else iconColor.copy(alpha = 0.38f),
        )
    }
}

/**
 * 播放 / 暂停主按钮：实心圆底 + 深色图标。
 * 播放态用不带圆圈的三角（[Icons.Filled.PlayArrow]）——按钮本身就是圆，再套一个带圈图标会成「圆里套圆」。
 */
@Composable
private fun PlayPauseButton(
    playing: Boolean,
    buttonSize: Dp,
    iconSize: Dp,
    hapticType: HapticFeedbackType,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    FilledIconButton(
        onClick = {
            haptic.performHapticFeedback(hapticType)
            onClick()
        },
        modifier = Modifier.size(buttonSize),
        shape = CircleShape,
    ) {
        Icon(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (playing) "暂停" else "播放",
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
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
