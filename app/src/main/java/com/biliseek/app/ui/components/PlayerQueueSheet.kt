package com.biliseek.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.biliseek.core.player.PlaybackRequest
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.formatDuration
import com.biliseek.designsystem.theme.BsSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 播放列表 sheet（Ch5 §5.1.3，docs/10-播放队列工单.md）。
 *
 * - 标题行：「播放队列 · N 首」+「清空队列」+ 关闭；下面一行常驻提示「右滑删除单条」
 * - 每行：序号（01/02…）、标题（单行省略）、时长
 * - 当前播放高亮（主色 + 波形图标）
 * - 点击条目：onSkipTo(index)；点当前条目仅关闭 sheet
 * - 向右滑动删除单条；正在播的那一行滑不动
 * - [showSwipeHint] 为真时，第一条可删除的行自动右滑一段再弹回，演示一次
 * - 队列为空时显示「队列为空」+ 加入方式提示
 *
 * 「清空队列」放在标题行而不是列表下面：列表一长，底部按钮会被挤出屏幕（早期就是这样）。
 * 撤销提示也显示在 sheet 里：sheet 是独立窗口，会挡住主界面的 Snackbar。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerQueueSheet(
    state: PlaybackState,
    onSkipTo: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState,
    showSwipeHint: Boolean = false,
    onSwipeHintShown: () -> Unit = {},
) {
    val queue = state.queue
    val currentIndex = state.queueIndex
    // 清空保留正在播的那一项：只剩它时没东西可清
    val canClear = queue.size > 1 || (queue.isNotEmpty() && currentIndex !in queue.indices)
    // 演示放在第一条能删的行上（不是当前播放那一行）
    val hintIndex = if (showSwipeHint) queue.indices.firstOrNull { it != currentIndex } else null
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "播放队列 · ${queue.size} 首",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClearQueue, enabled = canClear) { Text("清空队列") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                if (queue.isNotEmpty()) {
                    Text(
                        "右滑删除单条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
                    )
                }
                HorizontalDivider()
                if (queue.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("队列为空", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "从搜索结果 ⋮ 菜单 / 复选工具栏「加入队列」",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                } else {
                    // weight(fill = false)：列表只占自身高度、最多占满剩余空间，不会把别的东西挤出屏幕
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        itemsIndexed(
                            queue,
                            // 同一视频可能在队列里出现多次，key 带上下标才唯一；
                            // 删掉一条后后面的行 key 都变了，滑动状态不会串到别的行上
                            key = { idx, item -> "$idx:${item.keyPart()}" },
                        ) { idx, item ->
                            val isCurrent = idx == currentIndex
                            SwipeToRemoveRow(
                                enabled = !isCurrent,
                                demo = idx == hintIndex,
                                onDemoDone = onSwipeHintShown,
                                onRemove = { onRemove(idx) },
                            ) {
                                QueueRow(
                                    index = idx,
                                    request = item,
                                    isCurrent = isCurrent,
                                    onClick = {
                                        if (!isCurrent) onSkipTo(idx) else onDismiss()
                                    },
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
        }
    }
}

private fun PlaybackRequest.keyPart(): String = when (this) {
    is PlaybackRequest.Bili -> "$bvid:${cid ?: 0}"
    is PlaybackRequest.Direct -> uri
}

/**
 * 可向右滑动删除的一行。
 *
 * 不用 Material3 的 SwipeToDismissBox：自己控制偏移量，首次演示和手势共用同一个 [Animatable]。
 * 滑过行宽 40%（或快速甩动）松手即删除，否则弹回。
 */
@Composable
private fun SwipeToRemoveRow(
    enabled: Boolean,
    demo: Boolean,
    onDemoDone: () -> Unit,
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableIntStateOf(0) }
    val nudgePx = with(LocalDensity.current) { SWIPE_HINT_NUDGE.toPx() }

    LaunchedEffect(demo) {
        if (!demo) return@LaunchedEffect
        // sheet 展开动画走完再演示，否则用户看不到
        delay(SWIPE_HINT_DELAY_MS)
        offset.animateTo(nudgePx, tween(SWIPE_HINT_MOVE_MS))
        delay(SWIPE_HINT_HOLD_MS)
        offset.animateTo(0f, tween(SWIPE_HINT_MOVE_MS))
        onDemoDone()
    }

    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width }
            .clipToBounds(),
    ) {
        if (offset.value > 0f) {
            Row(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(start = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(BsSize.iconSm),
                )
                Text(
                    "删除",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                // 前景要不透明，才能把后面的删除底色盖住
                .background(BottomSheetDefaults.ContainerColor)
                .draggable(
                    enabled = enabled,
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) }
                    },
                    onDragStopped = { velocity ->
                        scope.launch {
                            val passed = widthPx > 0 && offset.value > widthPx * SWIPE_REMOVE_FRACTION
                            if (passed || velocity > SWIPE_FLING_VELOCITY) {
                                offset.animateTo(widthPx.toFloat(), tween(SWIPE_HINT_MOVE_MS))
                                onRemove()
                                // 删除后同一个 key 的行可能被复用（例如撤销），偏移要归零
                                offset.snapTo(0f)
                            } else {
                                offset.animateTo(0f)
                            }
                        }
                    },
                ),
        ) {
            content()
        }
    }
}

private val SWIPE_HINT_NUDGE = 72.dp
private const val SWIPE_HINT_DELAY_MS = 500L
private const val SWIPE_HINT_MOVE_MS = 250
private const val SWIPE_HINT_HOLD_MS = 400L
private const val SWIPE_REMOVE_FRACTION = 0.4f
private const val SWIPE_FLING_VELOCITY = 1500f

@Composable
private fun QueueRow(
    index: Int,
    request: PlaybackRequest,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val title = when (request) {
        is PlaybackRequest.Bili -> request.title.ifBlank { request.bvid }
        is PlaybackRequest.Direct -> request.uri
    }
    val durationSec = (request as? PlaybackRequest.Bili)?.durationSec ?: 0L
    val tint = if (isCurrent) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "%02d".format(index + 1),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
        if (isCurrent) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = "当前播放",
                tint = tint,
                modifier = Modifier.size(BsSize.iconSm),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (durationSec > 0) formatDuration(durationSec * 1000) else "",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
