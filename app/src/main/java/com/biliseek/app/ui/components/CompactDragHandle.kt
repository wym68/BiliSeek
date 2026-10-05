package com.biliseek.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 底部弹层的拖动条：M3 默认的上下各留 22dp，占了近 50dp 高；这里压到 8dp + 4dp 的条 + 8dp。
 *
 * 所有 `ModalBottomSheet` 都用它（`dragHandle = { CompactDragHandle() }`），内容从它下方 8dp 开始，
 * 标题离弹层顶边约 28dp；`ExpandableBottomPanel` 的顶边也用它。播放队列面板按设计不要拖动条。
 */
@Composable
internal fun CompactDragHandle() {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
        )
    }
}
