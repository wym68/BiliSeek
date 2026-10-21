package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.biliseek.app.data.QuickEntrySetting
import com.biliseek.app.data.moved
import com.biliseek.app.data.withEnabled
import com.biliseek.app.ui.components.SettingsIcon
import com.biliseek.app.ui.components.miniBarContentPadding
import com.biliseek.designsystem.component.BsTopBar

/**
 * 设置 → 首页快捷入口（设计稿：docs/superpowers/specs/2026-10-21-home-quick-entries-design.md）。
 * 每行：图标、名字、开关、拖动手柄。整行点 = 开关；长按拖动排序（与本地列表同一套：浮起、让位、每格轻震、边缘自动滚动）。
 * 关掉的入口留在原位，再打开回到原位。每次改动立刻存。
 */
@Composable
fun QuickEntrySettingsScreen(
    entries: List<QuickEntrySetting>,
    onChange: (List<QuickEntrySetting>) -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    val reorder = rememberDragReorderState(listState, entries, keyOf = { it.entry.id }) { _, from, to ->
        onChange(entries.moved(from, to))
    }
    Column(Modifier.fillMaxSize()) {
        BsTopBar(title = "首页快捷入口", onNavigate = onClose)
        Text(
            "长按拖动调整顺序；关掉的入口不在首页显示",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = miniBarContentPadding()) {
            items(reorder.order, key = { it.entry.id }) { item ->
                val id = item.entry.id
                val lifted = reorder.draggingId == id
                val floating = lifted || reorder.settlingId == id
                Box(
                    (
                        if (floating) {
                            Modifier.zIndex(1f).graphicsLayer {
                                translationY = reorder.offsetOf(id)
                                shadowElevation = if (lifted) LIFT_ELEVATION.toPx() else 0f
                            }
                        } else {
                            // 别的行在本地顺序变化时滑到新位置：这就是「让位」
                            Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                        }
                        )
                        .background(MaterialTheme.colorScheme.background)
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { reorder.start(id) },
                                onDrag = { change, amount ->
                                    change.consume()
                                    reorder.dragBy(amount.y)
                                },
                                onDragEnd = { reorder.end() },
                                onDragCancel = { reorder.cancel() },
                            )
                        },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = item.enabled, role = Role.Switch) {
                                // 长按拖动松手时子节点先收到抬起事件、会当作点按；此时 draggingId 还没清，据此忽略
                                if (reorder.draggingId == null) onChange(entries.withEnabled(item.entry, it))
                            }
                            .heightIn(min = 60.dp)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsIcon(quickEntryIcon(item.entry))
                        Text(item.entry.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = item.enabled, onCheckedChange = null)
                        Icon(
                            Icons.Filled.DragIndicator,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 拿起那一行的阴影。 */
private val LIFT_ELEVATION = 8.dp
