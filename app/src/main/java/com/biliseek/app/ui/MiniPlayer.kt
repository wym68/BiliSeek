package com.biliseek.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.biliseek.app.hint.HintKey
import com.biliseek.app.ui.components.DemoFinger
import com.biliseek.app.ui.components.HintBubble
import com.biliseek.app.ui.components.HintTiming
import com.biliseek.app.ui.components.LocalHints
import com.biliseek.app.ui.components.rememberDemoFingerState
import com.biliseek.app.ui.components.rememberHintDemo
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode
import com.biliseek.core.player.formatDuration
import kotlinx.coroutines.delay

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

        // 首次演示 ①：整条上移再弹回、下移再弹回（设计稿 2026-10-07-first-use-hints）
        val hints = LocalHints.current
        val swipeDemo = rememberHintDemo(HintKey.MINIBAR_SWIPE, ready = revealed)
        val swipeFinger = rememberDemoFingerState()
        val lift = remember { Animatable(0f) }
        LaunchedEffect(swipeDemo.running) {
            if (!swipeDemo.running) {
                swipeFinger.reset()
                lift.animateTo(0f)
                return@LaunchedEffect
            }
            delay(HintTiming.START_DELAY_MS)
            swipeFinger.show()
            lift.animateTo(-MINIBAR_DEMO_UP_DP, tween(MINIBAR_DEMO_MOVE_MS))
            lift.animateTo(0f, tween(MINIBAR_DEMO_MOVE_MS))
            delay(MINIBAR_DEMO_PAUSE_MS)
            lift.animateTo(MINIBAR_DEMO_DOWN_DP, tween(MINIBAR_DEMO_MOVE_MS))
            lift.animateTo(0f, tween(MINIBAR_DEMO_MOVE_MS))
            swipeFinger.hide()
            delay(HintTiming.BUBBLE_HOLD_MS)
            swipeDemo.finish()
        }

        val surfaceModifier = when (miniBarStyle) {
            com.biliseek.app.data.MiniBarStyle.FLOAT -> Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
                .shadow(elevation = 7.dp, shape = RoundedCornerShape(24.dp), clip = false)
                .clip(RoundedCornerShape(24.dp))
            com.biliseek.app.data.MiniBarStyle.BOTTOM -> Modifier
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        }
        Box {
            Surface(
                tonalElevation = 3.dp,
                // 演示 ① 时整条上下挪：offset 放在 clip 前面，挪的是整条，不是被裁掉的内容
                modifier = Modifier
                    .offset { IntOffset(0, lift.value.dp.roundToPx()) }
                    .then(surfaceModifier)
                    .fillMaxWidth(),
            ) {
                Box {
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
                            // 拖进度时的目标位置（松手后跳到哪），显示在时间文字里
                            var scrubMs by remember { mutableStateOf<Long?>(null) }
                            val currentOnExpand by rememberUpdatedState(onExpand)
                            // 整条迷你条上竖着滑：往上打开播放页，往下收起（顶部没有横杠了，见 MiniBarPolicy）
                            Column(
                                Modifier.pointerInput(Unit) {
                                    var totalPx = 0f
                                    var fired = false
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            totalPx = 0f
                                            fired = false
                                        },
                                    ) { _, dragAmount ->
                                        totalPx += dragAmount
                                        if (!fired) {
                                            when (resolveMiniBarSwipe(totalPx.toDp().value)) {
                                                MiniBarSwipe.EXPAND -> {
                                                    fired = true
                                                    hints?.onUserAction(HintKey.MINIBAR_SWIPE)
                                                    currentOnExpand()
                                                }
                                                MiniBarSwipe.COLLAPSE -> {
                                                    fired = true
                                                    hints?.onUserAction(HintKey.MINIBAR_SWIPE)
                                                    revealed = false
                                                }
                                                null -> Unit
                                            }
                                        }
                                    }
                                },
                            ) {
                                com.biliseek.app.ui.components.DraggableProgressBar(
                                    positionMs = state.positionMs,
                                    durationMs = state.durationMs,
                                    onSeek = onSeek,
                                    onScrub = { scrubMs = it },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(onClick = onExpand)
                                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
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
                                        val primary = MaterialTheme.colorScheme.primary
                                        Text(
                                            buildAnnotatedString {
                                                val scrub = scrubMs
                                                if (scrub != null) {
                                                    withStyle(SpanStyle(color = primary, fontWeight = FontWeight.SemiBold)) {
                                                        append(formatDuration(scrub))
                                                    }
                                                } else {
                                                    append(formatDuration(state.positionMs))
                                                }
                                                append(" / " + formatDuration(state.durationMs))
                                                append(if (state.presentation != PresentationMode.NONE) " · 看" else " · 听")
                                            },
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
                    // 手指在迷你条正中，跟着整条一起挪
                    DemoFinger(swipeFinger, Modifier.align(Alignment.Center))
                }
            }
            if (swipeDemo.running) HintBubble(MINIBAR_SWIPE_HINT_TEXT, preferAbove = true)
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

private const val HANDLE_DRAG_THRESHOLD = 24f

// 首次演示 ① 的节奏（设计稿 2026-10-07-first-use-hints；效果图 minibar-sequence.html）
private const val MINIBAR_DEMO_UP_DP = 18f
private const val MINIBAR_DEMO_DOWN_DP = 12f
private const val MINIBAR_DEMO_MOVE_MS = 250
private const val MINIBAR_DEMO_PAUSE_MS = 400L
private const val MINIBAR_SWIPE_HINT_TEXT = "往上滑打开播放页，往下滑收起"
