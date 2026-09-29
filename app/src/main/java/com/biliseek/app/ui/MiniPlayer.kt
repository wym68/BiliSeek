package com.biliseek.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode
import com.biliseek.core.player.formatDuration

@Composable
fun MiniPlayer(
    state: PlaybackState,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
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
                                Icon(
                                    if (state.showPauseButton) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (state.showPauseButton) "暂停" else "播放",
                                )
                            }
                            IconButton(onClick = onOpenQueue) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "播放列表")
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

private const val DRAG_THRESHOLD = 8f
private const val HANDLE_DRAG_THRESHOLD = 24f
