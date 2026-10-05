package com.biliseek.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing

/**
 * 统一的确认弹窗：标题 + 正文 + 「取消 / 确认」。
 * [destructive] = true 时确认按钮用 error 色（删除、清空、退出登录）。
 * [busy] = true 表示确认后的动作还在进行（如删收藏夹要先清空视频）：确认键显示转圈和「处理中…」，
 * 两个键都不可点，点空白 / 返回也关不掉，等动作结束由调用方收起。
 */
@Composable
fun BsConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "取消",
    busy: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(BsSize.iconSm), strokeWidth = 2.dp)
                        Spacer(Modifier.width(BsSpacing.sm))
                        Text("处理中…")
                    }
                } else {
                    Text(
                        confirmLabel,
                        color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(dismissLabel) } },
    )
}
