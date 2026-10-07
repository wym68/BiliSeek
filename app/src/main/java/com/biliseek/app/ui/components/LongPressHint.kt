package com.biliseek.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import com.biliseek.app.hint.HintKey
import kotlinx.coroutines.delay

/** 搜索结果、UP 空间投稿长按多选的气泡文字（⑫⑬）。 */
const val MULTI_SELECT_HINT_TEXT = "长按一条进入多选，可以一次加好几条"

/**
 * 长按类操作的首次演示（设计稿 2026-10-07-first-use-hints，效果图选 B）：包住一行，
 * [showHint] 为真且轮到 [key] 时，画一个手指按住、外圈进度环在系统长按时间内转满、这一行变深，下方气泡说明。
 * 只是画面：不触发长按回调。用户自己长按过，由调用方 `LocalHints.current?.onUserAction(key)`。
 *
 * @param fingerX 手指在行内的横坐标（从行左缘算起），一般是封面中心。
 */
@Composable
fun LongPressHint(
    key: HintKey,
    text: String,
    showHint: Boolean,
    fingerX: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val demo = rememberHintDemo(key, ready = showHint)
    val finger = rememberDemoFingerState()
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    LaunchedEffect(demo.running) {
        if (!demo.running) {
            finger.reset()
            return@LaunchedEffect
        }
        delay(HintTiming.START_DELAY_MS)
        finger.longPress(longPressMs)
        delay(HintTiming.BUBBLE_HOLD_MS)
        demo.finish()
    }
    Box(modifier) {
        content()
        if (finger.pressed) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = PRESSED_ALPHA)),
            )
        }
        DemoFinger(
            finger,
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = fingerX - DemoFingerDefaults.Size / 2),
        )
        if (demo.running) HintBubble(text, pointX = fingerX)
    }
}

/** 按下态：同 Material 的 pressed 层。 */
private const val PRESSED_ALPHA = 0.10f
