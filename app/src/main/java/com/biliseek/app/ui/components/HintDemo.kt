package com.biliseek.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import com.biliseek.app.hint.HintController
import com.biliseek.app.hint.HintKey
import com.biliseek.app.hint.HintState
import com.biliseek.app.hint.shouldForfeitHint
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 首次使用演示（设计稿：docs/superpowers/specs/2026-10-07-first-use-hints-design.md）的控制器。
 * 由 `MainActivity` 提供，同 [LocalShowMessage]；没提供时（预览、测试）为 null，不放任何演示。
 */
val LocalHints = staticCompositionLocalOf<HintController?> { null }

/** 演示的节奏。 */
object HintTiming {
    /** 出现后多久才开始动（页面进场 / 面板展开动画走完，用户才看得到）。 */
    const val START_DELAY_MS = 500L

    /** 动作放完后气泡再停多久，然后演示结束、记为看过。 */
    const val BUBBLE_HOLD_MS = 4_000L
}

/** 某个控件此刻的演示：[running] 时自己跑动作、显示气泡；放完调 [finish]。 */
class HintDemo(val running: Boolean, private val onFinish: () -> Unit) {
    fun finish() = onFinish()
}

/**
 * 这个控件要不要演示 [key]：[ready]（列表有内容、轮到这一行……）且本窗口有焦点时尝试开始。
 * 别的演示占着、而它本来能放 → 这次进入就放弃（控件重新进入组合才再试）。
 * 控件离开组合时没放完 → 取消，不记为看过。[key] 为 null 时永远不演示。
 */
@Composable
fun rememberHintDemo(key: HintKey?, ready: Boolean): HintDemo {
    val hints = LocalHints.current
    val fallback = remember { MutableStateFlow(HintState()) }
    val state by (hints?.state ?: fallback).collectAsState()
    // 后台、弹层 / 对话框盖着：本窗口没有焦点（队列面板自己是一个窗口，里面照放）
    val focused = LocalWindowInfo.current.isWindowFocused
    var started by remember(key) { mutableStateOf(false) }
    var forfeited by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key, ready, focused, state) {
        if (hints == null || key == null) return@LaunchedEffect
        if (started) {
            // 放完 / 被碰掉 / 被重置：交还
            if (state.active != key) started = false
            return@LaunchedEffect
        }
        if (!ready || !focused || forfeited) return@LaunchedEffect
        if (shouldForfeitHint(key, state)) {
            forfeited = true
        } else if (hints.tryStart(key)) {
            started = true
        }
    }
    val currentStarted by rememberUpdatedState(started)
    DisposableEffect(hints, key) {
        onDispose { if (currentStarted && key != null) hints?.cancel(key) }
    }
    val running = key != null && started && state.active == key
    return HintDemo(running) { if (key != null) hints?.finish(key) }
}

/**
 * 演示中手指碰到屏幕任何地方：当前演示结束、记为看过。只观察（Initial 阶段、不消费），这次触摸照常生效。
 * 挂在 `AppShell` 根和队列面板根（面板是另一个窗口）。
 */
fun Modifier.hintTouchObserver(hints: HintController?): Modifier =
    if (hints == null) {
        this
    } else {
        pointerInput(hints) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                hints.onTouch()
            }
        }
    }
