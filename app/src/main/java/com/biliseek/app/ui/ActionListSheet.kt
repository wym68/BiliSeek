package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.ui.components.CompactDragHandle

/**
 * 通用底部动作菜单：标题（单行，放不下用省略号）+ 带图标的动作列表。
 *
 * 搜索结果 / 多 P 面板 / UP 空间投稿 / 动态长按的菜单共用骨架，
 * 「加入队列」= 追加到播放队列末尾；「下一首播放」= 插到当前项之后（语义由 VM 保证）。
 * 图标按动作名称对应（[iconFor]），没有对应图标的动作只显示文字；以「删除」开头的动作标红。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionListSheet(
    title: String,
    items: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { CompactDragHandle() },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                title,
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxWidth()) {
                items(items) { (label, action) ->
                    val destructive = label.startsWith("删除")
                    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { action() }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val icon = iconFor(label)
                        if (icon != null) {
                            Icon(
                                icon,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = if (destructive) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(16.dp))
                        }
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
                    }
                }
            }
        }
    }
}

/** 动作名称 → 图标。名称是各处菜单里写死的中文字面量。 */
private fun iconFor(label: String): ImageVector? = when (label) {
    "加入列表" -> Icons.AutoMirrored.Outlined.PlaylistAdd
    "加入队列" -> Icons.AutoMirrored.Outlined.QueueMusic
    "下一首播放" -> Icons.Outlined.SkipNext
    "查看UP主" -> Icons.Outlined.Person
    "复制链接" -> Icons.Outlined.ContentCopy
    "下载" -> Icons.Outlined.Download
    "AI 总结" -> Icons.Outlined.AutoAwesome
    "编辑", "重命名" -> Icons.Outlined.Edit
    "删除" -> Icons.Outlined.Delete
    else -> if (label.startsWith("删除")) Icons.Outlined.Delete else null // 删除此版本、删除全部版本…
}
