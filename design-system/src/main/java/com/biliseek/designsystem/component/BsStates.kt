package com.biliseek.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing

/*
 * 列表页的三种整页状态 + 分页底部，统一样式。
 *
 * 原先约 10 个页面各写一份「加载失败：xxx + 重试」，重试按钮一会儿 Button 一会儿 TextButton。
 * 规则：整页失败用 FilledTonalButton「重试」；分页底部失败用 TextButton「重试」。
 */

/** 整页加载中。 */
@Composable
fun BsLoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(BsSpacing.xl), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** 整页空状态。 */
@Composable
fun BsEmptyState(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(BsSpacing.xl), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BsSpacing.md),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action?.invoke()
        }
    }
}

/** 整页失败状态：错误文案 + 重试。 */
@Composable
fun BsErrorState(
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(BsSpacing.xl), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BsSpacing.md),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                FilledTonalButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

/**
 * 分页列表底部：加载中转圈 / 失败 + 重试 / 没有更多。
 * 三者都不满足时不占位。
 */
@Composable
fun BsLoadMoreFooter(
    isLoadingMore: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    endText: String? = null,
) {
    when {
        isLoadingMore -> Box(
            modifier.fillMaxWidth().padding(BsSpacing.md),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(BsSize.iconSm), strokeWidth = 2.dp)
        }

        errorMessage != null -> Column(
            modifier.fillMaxWidth().padding(BsSpacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }

        endText != null -> Box(
            modifier.fillMaxWidth().padding(BsSpacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                endText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
