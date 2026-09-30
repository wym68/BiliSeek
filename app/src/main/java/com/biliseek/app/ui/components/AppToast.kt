package com.biliseek.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
 * 让同一个 App 里的提示看起来是一家的。系统 Toast 放不了按钮，要带「撤销」的提示只能自己画。
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
        Row(
            Modifier.padding(
                start = BsSpacing.md,
                end = if (actionLabel != null) BsSpacing.xs else BsSpacing.lg,
                top = BsSpacing.sm,
                bottom = BsSpacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon()
            Spacer(Modifier.width(BsSpacing.md))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) scheme.error else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = scheme.primary)
                }
            }
        }
    }
}

/** [AppToast] 的 SnackbarHost 版本：给 `SnackbarHost(state) { AppToastSnackbar(it) }` 用。 */
@Composable
fun AppToastSnackbar(data: SnackbarData) {
    AppToast(
        message = data.visuals.message,
        actionLabel = data.visuals.actionLabel,
        onAction = data::performAction,
        modifier = Modifier.padding(horizontal = BsSpacing.lg, vertical = BsSpacing.sm),
    )
}

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

private val ICON_SIZE = 24.dp
private const val ADAPTIVE_ICON_SCALE = 1.5f
private val MAX_WIDTH = 480.dp
