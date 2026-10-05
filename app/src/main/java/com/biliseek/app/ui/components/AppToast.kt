package com.biliseek.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.R
import com.biliseek.designsystem.theme.BsShapes
import com.biliseek.designsystem.theme.BsSpacing

/**
 * 应用内提示的统一样式：白色胶囊 + App 图标，可带一个操作按钮（如「撤销」）。
 *
 * 外观照着系统 Toast 画（Android 12 起系统 Toast 就是白底胶囊、左边一个 App 图标），
 * 让同一个 App 里的提示看起来是一家的。系统 Toast 放不了按钮、位置也挪不动（会压在迷你条上），
 * 所以 App 在前台时普通提示也用它，放在迷你条上方（见 [ToastTarget]、AppShell 的提示位）。
 * 不再用 M3 默认的深色 Snackbar，也不再各页自画提示条（docs/09 共用组件）。
 * 叠在视频画面上的 HUD（手势进度、全屏错误框）不在此列：那里要的是黑底白字。
 */
@Composable
fun AppToast(
    message: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    // 浅色下用最白的一档，和系统 Toast 一致；深色下最低一档会和背景糊在一起，换高一档
    val isDark = scheme.surface.luminance() < 0.5f
    Surface(
        modifier = modifier.widthIn(max = MAX_WIDTH),
        shape = BsShapes.pill,
        color = if (isDark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest,
        contentColor = scheme.onSurface,
        shadowElevation = 6.dp,
    ) {
        // 固定高度，与系统 Toast 等高；不靠上下内边距撑，免得按钮的触控区把整条撑高
        Row(
            Modifier
                .height(AppToastDefaults.Height)
                .padding(
                    start = BsSpacing.md,
                    end = if (actionLabel != null) BsSpacing.xs else BsSpacing.lg,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon()
            Spacer(Modifier.width(BsSpacing.md))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) scheme.error else scheme.onSurface,
                // 只占一行：长标题在按钮前截断，「撤销」始终完整
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (actionLabel != null) {
                // 不用 TextButton：它 48dp 的最小触控区会把整条提示撑高。点击区为文字加一圈内边距
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    modifier = Modifier
                        .clip(BsShapes.pill)
                        .clickable(onClick = onAction)
                        .padding(horizontal = BsSpacing.md, vertical = BsSpacing.sm),
                )
            }
        }
    }
}

/**
 * [AppToast] 的 SnackbarHost 版本：给 `SnackbarHost(state) { AppToastSnackbar(it) }` 用。
 * 摆放位置由调用方决定：主界面在底部栏（迷你条 + 导航栏）上方 [AppToastDefaults.GapAboveBottomBar]，
 * 队列面板里在 [AppToastDefaults.BottomOffset]。
 */
@Composable
fun AppToastSnackbar(data: SnackbarData) {
    AppToast(
        message = data.visuals.message,
        actionLabel = data.visuals.actionLabel,
        onAction = data::performAction,
        modifier = Modifier.padding(horizontal = BsSpacing.lg),
    )
}

/** 尺寸与位置。高度按真机截图对齐系统 Toast。 */
object AppToastDefaults {
    /** 胶囊高度，与系统 Toast 等高。 */
    val Height = 52.dp

    /**
     * 主界面：提示下沿到底部栏（迷你条 + 导航栏）上沿的距离。迷你条在时就在迷你条上方，不挡迷你条
     * （用户 2026-10-04 要求）；与下拉刷新提示同距。
     */
    val GapAboveBottomBar = 16.dp

    /** 全屏时提示下沿到屏幕底（系统导航条之上）的距离。 */
    val FullscreenBottomOffset = 24.dp

    /**
     * 队列面板里：提示下沿到面板底（系统导航条之上）的距离。面板盖住了迷你条，沿用原来对齐系统 Toast 的位置。
     * 用法：`Modifier.align(BottomCenter).navigationBarsPadding().padding(bottom = BottomOffset)`。
     */
    val BottomOffset = 86.dp
}

/**
 * 弹一条普通提示（App 内、不带按钮）。由 `MainActivity` 提供，走和 `transientMessage` 同一条管道；
 * 没提供时（预览、测试）为 null，调用方自己退回系统 Toast。
 */
val LocalShowMessage = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * 圆形 App 图标，仿系统 Toast 左边那个。
 *
 * 不能直接 `painterResource(R.mipmap.ic_launcher)`：那是 adaptive-icon XML，Compose 不支持。
 * 这里按自适应图标的规则自己拼：白底 + 前景矢量图。前景画布 108dp、可见区 72dp，
 * 放大 1.5 倍后裁成圆，得到的就是桌面上的那个图标。
 */
@Composable
private fun AppIcon() {
    Box(
        Modifier
            .size(ICON_SIZE)
            .clip(CircleShape)
            .background(colorResource(R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(ICON_SIZE * ADAPTIVE_ICON_SCALE),
        )
    }
}

private val ICON_SIZE = 20.dp
private const val ADAPTIVE_ICON_SCALE = 1.5f
private val MAX_WIDTH = 480.dp
