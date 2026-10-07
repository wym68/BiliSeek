package com.biliseek.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 首次演示里画出来的手指（设计稿 2026-10-07-first-use-hints，效果图选 B）。
 * 演示脚本在协程里调 [show] / [tap] / [longPress] / [hide]；被打断时调 [reset]。
 */
@Stable
class DemoFingerState {
    var visible by mutableStateOf(false)
        private set

    /** 长按演示中：调用方给那一行盖上按下态。 */
    var pressed by mutableStateOf(false)
        private set

    internal val ring = Animatable(0f)
    internal val ripple = Animatable(0f)

    fun show() {
        visible = true
    }

    fun hide() {
        visible = false
    }

    /** 点一下：主色描边圆从 0.8 倍扩到 1.9 倍淡出。 */
    suspend fun tap() {
        visible = true
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(DemoFingerDefaults.RIPPLE_MS))
        ripple.snapTo(0f)
    }

    /** 按住：外圈进度环在 [pressMs] 内转满，转满时出一次波纹，再停一下松手消失。 */
    suspend fun longPress(pressMs: Long) {
        visible = true
        pressed = true
        ring.snapTo(0f)
        ring.animateTo(1f, tween(pressMs.toInt(), easing = LinearEasing))
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(DemoFingerDefaults.RIPPLE_MS))
        delay(DemoFingerDefaults.RELEASE_DELAY_MS)
        pressed = false
        ring.snapTo(0f)
        ripple.snapTo(0f)
        visible = false
    }

    suspend fun reset() {
        visible = false
        pressed = false
        ring.snapTo(0f)
        ripple.snapTo(0f)
    }
}

@Composable
fun rememberDemoFingerState(): DemoFingerState = remember { DemoFingerState() }

/** 手指：28dp 灰色半透明圆。调用方把它的中心对到手指位置（偏移 `-DemoFingerDefaults.Size / 2`）。 */
@Composable
fun DemoFinger(state: DemoFingerState, modifier: Modifier = Modifier) {
    if (!state.visible) return
    val primary = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    // 进度环、波纹画在 28dp 外面：Canvas 不裁剪
    Canvas(modifier.size(DemoFingerDefaults.Size)) {
        val radius = size.minDimension / 2
        drawCircle(ink.copy(alpha = 0.22f), radius)
        drawCircle(ink.copy(alpha = 0.35f), radius - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        val ring = state.ring.value
        if (ring > 0f) {
            val r = DemoFingerDefaults.RingRadius.toPx()
            drawArc(
                color = primary,
                startAngle = -90f,
                sweepAngle = 360f * ring,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(2 * r, 2 * r),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        val ripple = state.ripple.value
        if (ripple > 0f && ripple < 1f) {
            drawCircle(
                primary.copy(alpha = 0.55f * (1f - ripple)),
                radius * (0.8f + 1.1f * ripple),
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}

object DemoFingerDefaults {
    val Size = 28.dp
    val RingRadius = 17.dp
    const val RIPPLE_MS = 450
    const val RELEASE_DELAY_MS = 500L
}
