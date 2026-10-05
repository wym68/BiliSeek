package com.biliseek.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.biliseek.designsystem.theme.BsSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 可向右滑动删除的一行（播放队列、稍后再看、收藏夹内容页、本地列表、已下载、AI 总结列表共用）。
 *
 * 不用 Material3 的 SwipeToDismissBox：自己控制偏移量，首次演示和手势共用同一个 [Animatable]。
 * 滑过行宽 40%（或快速甩动）松手即删除，否则弹回。
 * [background] 是行的底色：前景要不透明才能盖住后面的红色删除底，所以要和所在页面的底色一致。
 * [demo] 为 true 时先自动往右挪一点再弹回，演示可以滑（队列首次打开时用）。
 */
@Composable
fun SwipeToRemoveRow(
    background: Color,
    onRemove: () -> Unit,
    enabled: Boolean = true,
    demo: Boolean = false,
    onDemoDone: () -> Unit = {},
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
                .background(background)
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
