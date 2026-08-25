package com.biliseek.app.ui

import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode

/**
 * 播放器容器的展开程度。
 *
 * 暂无全屏态：视频区上滑进全屏的手势有缺陷已移除，
 * 播放与显示的更多形态留待后续优化（见《架构设计篇》§7.2）。
 */
enum class PlayerExpansion { COLLAPSED, EXPANDED }

/**
 * 常驻播放器容器。
 *
 * 见《架构设计篇》§7.2：**挂在 NavHost 之外**，页面切换时不重建，播放不中断。
 * 从听 → 看 → 全屏全程不动导航栈，「退回听」始终是一步可逆的状态变更。
 */
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
) {
    when (expansion) {
        PlayerExpansion.COLLAPSED -> MiniPlayer(
            state = state,
            onExpand = { onExpansionChange(PlayerExpansion.EXPANDED) },
            onTogglePlayPause = onTogglePlayPause,
            modifier = modifier,
        )
        PlayerExpansion.EXPANDED -> ExpandedPlayer(
            state = state,
            surfaceView = surfaceView,
            onCollapse = { onExpansionChange(PlayerExpansion.COLLAPSED) },
            onTogglePlayPause = onTogglePlayPause,
            onPresentationChange = onPresentationChange,
            onEnterPip = onEnterPip,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            onCyclePlayMode = onCyclePlayMode,
            modifier = modifier,
        )
    }
}

@Composable
private fun MiniPlayer(
    state: PlaybackState,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.mode != com.biliseek.core.player.PlaybackMode.IDLE,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    // 上滑展开
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount < -DRAG_THRESHOLD) onExpand()
                    }
                },
        ) {
            Column {
                val progress = if (state.durationMs > 0) {
                    state.positionMs.toFloat() / state.durationMs
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onExpand)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.title.ifBlank { "BiliSeek" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${state.positionMs / 1000}s / ${state.durationMs / 1000}s" +
                                if (state.presentation != PresentationMode.NONE) " · 看" else " · 听",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    IconButton(onClick = onTogglePlayPause) {
                        Text(if (state.isPlaying) "⏸" else "▶")
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandedPlayer(
    state: PlaybackState,
    surfaceView: SurfaceView,
    onCollapse: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPresentationChange: (PresentationMode, SurfaceView?) -> Unit,
    onEnterPip: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onCyclePlayMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCollapse) { Text("▾") }
                Text("正在播放", style = MaterialTheme.typography.titleSmall)
                Text("")
            }

            // 封面区原地展开为视频，页面不跳转
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (state.presentation == PresentationMode.NONE) {
                    Text(
                        if (state.isResolving) "取流中…" else "纯音频模式",
                        color = Color.White,
                    )
                } else {
                    PlayerSurface(surfaceView, Modifier.fillMaxSize())
                }
            }

            Text(
                state.title.ifBlank { "BiliSeek" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            PlayerControls(state, surfaceView, onTogglePlayPause, onPresentationChange)

            QueueControls(state, onSkipPrevious, onSkipNext, onCyclePlayMode, onEnterPip)

            state.error?.let {
                Text("错误：$it", style = MaterialTheme.typography.bodySmall, color = Color.Red)
            }
        }
    }
}

@Composable
private fun PlayerControls(
    state: PlaybackState,
    surfaceView: SurfaceView,
    onTogglePlayPause: () -> Unit,
    onPresentationChange: (PresentationMode, SurfaceView?) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedButton(onClick = onTogglePlayPause) {
            Text(if (state.isPlaying) "暂停" else "播放")
        }
        if (state.presentation == PresentationMode.NONE) {
            androidx.compose.material3.Button(
                onClick = { onPresentationChange(PresentationMode.INLINE, surfaceView) },
            ) { Text("切「看」") }
        } else {
            androidx.compose.material3.Button(
                onClick = { onPresentationChange(PresentationMode.NONE, null) },
            ) { Text("退回「听」") }
        }
    }
}

@Composable
private fun QueueControls(
    state: PlaybackState,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onCyclePlayMode: () -> Unit,
    onEnterPip: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedButton(
            onClick = onSkipPrevious,
            enabled = state.hasPrevious,
        ) { Text("上一首") }
        androidx.compose.material3.OutlinedButton(
            onClick = onSkipNext,
            enabled = state.hasNext,
        ) { Text("下一首") }
        androidx.compose.material3.OutlinedButton(onClick = onCyclePlayMode) {
            Text(
                when (state.playMode) {
                    com.biliseek.core.player.PlayMode.SEQUENTIAL -> "顺序"
                    com.biliseek.core.player.PlayMode.REPEAT_ALL -> "列表循环"
                    com.biliseek.core.player.PlayMode.REPEAT_ONE -> "单曲"
                    com.biliseek.core.player.PlayMode.SHUFFLE -> "随机"
                },
            )
        }
        if (state.presentation != PresentationMode.NONE) {
            androidx.compose.material3.OutlinedButton(onClick = onEnterPip) { Text("画中画") }
        }
    }
    if (state.queue.size > 1) {
        Text(
            "队列 ${state.queueIndex + 1} / ${state.queue.size}",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private const val DRAG_THRESHOLD = 8f
