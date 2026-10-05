package com.biliseek.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.biliseek.designsystem.theme.BsSpacing

/**
 * 居中弹窗外壳：外观与 `BsConfirmDialog` 一致（同一个弹窗窗口宽度、`AlertDialogDefaults` 的圆角与底色、大标题、24dp 内边距）
 * （设计稿：docs/superpowers/specs/2026-10-04-add-to-list-centered-design.md）。
 *
 * 与 `AlertDialog` 不同的是 [content] 横向铺满：列表行可以整行可点，左右边距由内容自己留
 * （分区标题用 [SettingsSheetSection]，与底部弹层同一套边距）。内容过长时只滚中间，标题和底部按钮不动。
 * 键盘弹出时整个弹窗挪到键盘上方（弹窗窗口的默认行为）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CenterDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(vertical = BsSpacing.xl)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = AlertDialogDefaults.titleContentColor,
                    modifier = Modifier.padding(horizontal = BsSpacing.xl),
                )
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    content = content,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BsSpacing.xl),
                    horizontalArrangement = Arrangement.spacedBy(BsSpacing.md, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
    }
}
