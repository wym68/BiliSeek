package com.biliseek.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.biliseek.app.ui.components.AppToast
import com.biliseek.designsystem.theme.BsSpacing
import kotlinx.coroutines.delay

/**
 * 刷新状态提示组件。样式统一用 [AppToast]（白色胶囊 + App 图标，见 docs/09 共用组件）。
 *
 * @param message 提示信息
 * @param isError 是否为错误提示（文字用 error 色）
 * @param onDismiss 消失回调
 */
@Composable
fun RefreshStateSnackbar(
    message: String?,
    isError: Boolean = false,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var visible by remember(message) { mutableStateOf(message != null) }
    // 退场动画期间 message 已被清空：留住最后一条，胶囊不会在滑出时变成空的
    val last = remember { mutableStateOf("") }
    SideEffect { if (message != null) last.value = message }
    val shown = message ?: last.value

    LaunchedEffect(message) {
        if (message != null) {
            delay(2000)
            visible = false
            onDismiss()
        }
    }

    Box(modifier.fillMaxSize(), Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible && message != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            AppToast(
                message = shown,
                isError = isError,
                // 所在内容区已由 Scaffold 让出迷你播放条与底部导航栏，这里只留常规边距；
                // 早先额外垫的 80dp 与之叠加，提示飘在播放条上方老远
                modifier = Modifier.padding(BsSpacing.lg),
            )
        }
    }
}
