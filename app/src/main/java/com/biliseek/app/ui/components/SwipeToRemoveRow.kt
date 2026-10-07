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
import com.biliseek.app.hint.HintKey
import com.biliseek.designsystem.theme.BsSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 可向右滑动移除的一行（播放队列、稍后再看、收藏夹内容页、本地列表、已下载、AI 总结列表共用）。
 *
 * 不用 Material3 的 SwipeToDismissBox：自己控制偏移量，首次演示和手势共用同一个 [Animatable]。
 * 滑过行宽 40%（或快速甩动）松手即移除，否则弹回。
 * [background] 是行的底色：前景要不透明才能盖住后面的红色删除底，所以要和所在页面的底色一致。
 *
 * [actionLabel] 是右滑露出的字与演示气泡里的叫法；已下载、AI 总结会真的删文件，传「删除」。
 *
 * 首次演示（设计稿 2026-10-07-first-use-hints）：[hintKey] 是这个列表的演示，[showHint] 为真的那一行
 * 轮到时自己往右挪 88dp（露出 [actionLabel]）再弹回，画一个手指、下方气泡说明；用户在任一行自己删过就记为看过。
 */
@Composable
fun SwipeToRemoveRow(
    background: Color,
    onRemove: () -> Unit,
    enabled: Boolean = true,
    actionLabel: String = "移除",
    hintKey: HintKey? = null,
    showHint: Boolean = false,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableIntStateOf(0) }
    val nudgePx = with(LocalDensity.current) { SWIPE_HINT_NUDGE.toPx() }
    val hints = LocalHints.current
    val demo = rememberHintDemo(hintKey, ready = showHint && enabled)
    val finger = rememberDemoFingerState()

    LaunchedEffect(demo.running) {
        if (!demo.running) {
            finger.reset()
            // 演示被碰掉时行可能还挪着：弹回（用户接着拖会接管偏移量）
            if (offset.value != 0f) offset.animateTo(0f)
            return@LaunchedEffect
        }
        // 面板展开 / 页面进场走完再演示，否则用户看不到
        delay(HintTiming.START_DELAY_MS)
        finger.show()
        offset.animateTo(nudgePx, tween(SWIPE_HINT_MOVE_MS))
        delay(SWIPE_HINT_HOLD_MS)
        offset.animateTo(0f, tween(SWIPE_HINT_MOVE_MS))
        finger.hide()
        delay(HintTiming.BUBBLE_HOLD_MS)
        demo.finish()
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
                    actionLabel,
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
                                if (hintKey != null) hints?.onUserAction(hintKey)
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
            // 手指跟着行一起挪
            DemoFinger(
                finger,
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = SWIPE_HINT_FINGER_X - DemoFingerDefaults.Size / 2),
            )
        }
        if (demo.running) HintBubble("右滑可以${actionLabel}，能撤销", pointX = SWIPE_HINT_FINGER_X)
    }
}

/** 演示挪多远：88dp 刚好露全两个字（「移除」/「删除」）（72dp 只露出一半）。 */
private val SWIPE_HINT_NUDGE = 88.dp

/** 演示手指在行里的横坐标（封面上）。 */
private val SWIPE_HINT_FINGER_X = 48.dp
private const val SWIPE_HINT_MOVE_MS = 250
private const val SWIPE_HINT_HOLD_MS = 400L
private const val SWIPE_REMOVE_FRACTION = 0.4f
private const val SWIPE_FLING_VELOCITY = 1500f
