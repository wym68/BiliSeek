package com.biliseek.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 动态转发对话框（Ch4 §4.1.4 转发）。半屏 sheet：输入框 + 取消/提交。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepostDialog(
    content: String,
    isPosting: Boolean,
    onContentChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("↗ 转发动态", style = MaterialTheme.typography.titleMedium)
                Text(
                    "关闭",
                    Modifier.clickable(onClick = onDismiss).padding(8.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                label = { Text("说点什么吧…(可选)") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                minLines = 4,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = onSubmit, enabled = !isPosting) {
                    Text(if (isPosting) "提交中…" else "转发")
                }
            }
        }
    }
}