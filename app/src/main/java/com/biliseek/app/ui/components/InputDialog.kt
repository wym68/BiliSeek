package com.biliseek.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.biliseek.designsystem.theme.BsSpacing

/**
 * 输入类弹窗（新建 / 重命名）：从屏幕中间弹出，标题 + 输入框 + 底部「取消 / 确认」。
 * 外壳是 [CenterDialog]（与确认框同一套外观）；底部按钮与下载弹层同一种排法（取消在左、确认在右，各占一半）。
 *
 * 输入框写在 [fields] 里，并把收到的 [FocusRequester] 挂到第一个输入框上：弹窗一出来就聚焦、弹出键盘。
 */
@Composable
fun InputDialog(
    title: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    fields: @Composable ColumnScope.(FocusRequester) -> Unit,
) {
    val focus = remember { FocusRequester() }
    // 聚焦失败（输入框还没挂上、弹窗还在动画）不崩，用户点一下输入框就行
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    CenterDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = onConfirm, enabled = confirmEnabled, modifier = Modifier.weight(1f)) { Text(confirmLabel) }
        },
    ) {
        Column(
            Modifier.padding(start = BsSpacing.xl, end = BsSpacing.xl, top = BsSpacing.lg, bottom = BsSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(BsSpacing.md),
        ) {
            fields(focus)
        }
    }
}
