package com.biliseek.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.biliseek.app.ui.components.CompactDragHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 面板高度状态：[fraction] 是面板高度占屏幕高度的比例，收起时 [collapsed]，撑满时 1。 */
@Stable
class ExpandablePanelState(val collapsed: Float) {
    var fraction by mutableFloatStateOf(collapsed)
        private set
    private var job: Job? = null

    /** 撑开程度 0（收起）..1（撑满），拖到收起以下时为 0。 */
    val expandProgress: Float get() = ((fraction - collapsed) / (1f - collapsed)).coerceIn(0f, 1f)

    val isExpanded: Boolean get() = fraction > (collapsed + 1f) / 2f

    internal fun dragBy(deltaFraction: Float) {
        job?.cancel()
        fraction = (fraction + deltaFraction).coerceIn(MIN_FRACTION, 1f)
    }

    fun expand(scope: CoroutineScope) = animateTo(scope, 1f)

    fun collapse(scope: CoroutineScope) = animateTo(scope, collapsed)

    fun toggle(scope: CoroutineScope) = if (isExpanded) collapse(scope) else expand(scope)

    internal fun settle(scope: CoroutineScope, velocity: Float, onDismiss: () -> Unit) {
        when (PanelSnapPolicy.target(fraction, velocity, collapsed)) {
            PanelSnap.DISMISS -> onDismiss()
            PanelSnap.COLLAPSED -> collapse(scope)
            PanelSnap.EXPANDED -> expand(scope)
        }
    }

    private fun animateTo(scope: CoroutineScope, target: Float) {
        job?.cancel()
        job = scope.launch { animate(fraction, target) { value, _ -> fraction = value } }
    }

    private companion object {
        const val MIN_FRACTION = 0.3f
    }
}

@Composable
fun rememberExpandablePanelState(collapsed: Float = 0.6f): ExpandablePanelState =
    remember(collapsed) { ExpandablePanelState(collapsed) }

/**
 * 底部弹出的面板：默认占屏幕高度的 [ExpandablePanelState.collapsed]（60%），
 * 拖动顶部把手、或在内容里向上滑，可以撑满整个屏幕；撑满后向下拉回收起高度，再往下拉 / 点空白处 / 返回键关闭。
 *
 * M3 的 ModalBottomSheet 半展开高度固定为屏幕的一半、不能调，所以这里自己做一个。
 * 撑满时面板延伸到状态栏下面，内容自动让开状态栏；始终让开底部手势导航条。
 */
@Composable
fun ExpandableBottomPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    state: ExpandablePanelState = rememberExpandablePanelState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val heightPx = with(density) { maxHeight.toPx() }

        // 遮罩：拖到收起高度以下时随之变淡，提示「再拖就关了」
        val fadeStart = state.collapsed
        val scrim = 0.32f * ((state.fraction - 0.3f) / (fadeStart - 0.3f)).coerceIn(0f, 1f)
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrim))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )

        val nested = remember(state, heightPx) {
            object : NestedScrollConnection {
                // 内容向上滑：面板还没撑满就先撑面板，内容不动
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < 0f && state.fraction < 1f) {
                        val delta = minOf(-available.y / heightPx, 1f - state.fraction)
                        state.dragBy(delta)
                        return Offset(0f, -delta * heightPx)
                    }
                    return Offset.Zero
                }

                // 内容已经到顶还在往下拉：面板跟着缩
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0f && source == NestedScrollSource.UserInput) {
                        state.dragBy(-available.y / heightPx)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                // 松手（含慢拖后不抛动的情况）：按位置与速度吸附
                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    state.settle(scope, velocity = -available.y / heightPx, onDismiss = onDismiss)
                    return available
                }
            }
        }

        val corner = 28.dp * (1f - state.expandProgress)
        Surface(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(maxHeight * state.fraction)
                .nestedScroll(nested),
            shape = RoundedCornerShape(topStart = corner, topEnd = corner),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = statusBarTop * state.expandProgress)
                    .navigationBarsPadding(),
            ) {
                // 拖动把手：只有这一条响应拖动手势，避免和内容的滚动抢事件
                val dragState = rememberDraggableState { delta -> state.dragBy(-delta / heightPx) }
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity ->
                                state.settle(scope, velocity = -velocity / heightPx, onDismiss = onDismiss)
                            },
                        ),
                ) { CompactDragHandle() }
                content()
            }
        }
    }
}
