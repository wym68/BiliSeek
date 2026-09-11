package com.biliseek.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 动态评论对话框（Ch4 §4.1.4 评论）。半屏 sheet：最近评论预览 + 输入框 + 发送。
 *
 * 简化实现（工单 4.9 #6）：不拉评论历史（避免再增 WBI 评论接口与分页），只占位。
 * 评论提交后，乐观更新会反映到卡片 stat.comment.count。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicCommentDialog(
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
                Text("💬 评论", style = MaterialTheme.typography.titleMedium)
                Text(
                    "关闭",
                    Modifier.clickable(onClick = onDismiss).padding(8.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            HorizontalDivider()
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "评论列表（占位，未实装）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                label = { Text("写评论…") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                minLines = 2,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onSubmit, enabled = !isPosting && content.isNotBlank()) {
                    Text(if (isPosting) "发送中…" else "发送")
                }
            }
        }
    }
}