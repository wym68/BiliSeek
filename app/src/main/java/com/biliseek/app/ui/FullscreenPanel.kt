package com.biliseek.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.biliseek.designsystem.theme.BiliSeekTheme

/**
 * 全屏里的设置面板：横屏从右侧滑出，竖屏从底部升起。
 *
 * **不用 `ModalBottomSheet`**：它是独立窗口，弹出时会把沉浸模式下藏起来的系统栏
 * 带出来，横屏下还会铺满整条底边、挡住画面。这里是全屏层内部的一层，
 * 画面继续在左侧 / 上方播放。
 *
 * 面板内容一律按暗色主题渲染——全屏背景是黑的，跟随系统亮色主题的白底面板
 * 会像闪光灯一样扎眼。
 */
@Composable
fun FullscreenPanel(
    visible: Boolean,
    landscape: Boolean,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 后注册的 BackHandler 优先：面板开着时返回键先关面板，不直接退出全屏
    BackHandler(enabled = visible, onBack = onDismiss)

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    // 面板开着时，遮罩上的拖动不能漏到下面去调进度 / 音量
                    .pointerInput(Unit) { detectDragGestures { change, _ -> change.consume() } }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = if (landscape) slideInHorizontally { it } else slideInVertically { it },
            exit = if (landscape) slideOutHorizontally { it } else slideOutVertically { it },
            modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter),
        ) {
            BiliSeekTheme(darkTheme = true) {
                Surface(
                    color = Color(0xF0181818),
                    shape = if (landscape) {
                        RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                    } else {
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    },
                    modifier = if (landscape) {
                        Modifier.width(PANEL_WIDTH).fillMaxHeight()
                    } else {
                        Modifier.fillMaxWidth().heightIn(max = PANEL_MAX_HEIGHT)
                    },
                ) {
                    Column(
                        Modifier
                            .windowInsetsPadding(WindowInsets.displayCutout)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        content()
                    }
                }
            }
        }
    }
}

private val PANEL_WIDTH = 320.dp
private val PANEL_MAX_HEIGHT = 480.dp
