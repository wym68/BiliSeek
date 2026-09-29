package com.biliseek.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 刷新状态提示组件
 *
 * @param message 提示信息
 * @param isError 是否为错误提示
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
            Text(
                text = message ?: "",
                // 所在内容区已由 Scaffold 让出迷你播放条与底部导航栏，这里只留常规边距；
                // 早先额外垫的 80dp 与之叠加，提示飘在播放条上方老远
                modifier = Modifier
                    .padding(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}
