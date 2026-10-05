package com.biliseek.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing

/** 顶栏左侧导航按钮的样式。 */
enum class BsNavIcon { Back, Close, None }

/**
 * 统一的页面顶栏：返回（或关闭）+ 标题 + 右侧操作。
 *
 * 替代各页面自拼的「TextButton("返回") + Text 标题」行。不用 M3 TopAppBar：
 * 页面大多嵌在 Scaffold 内容区或底部弹层里，TopAppBar 的 insets / 滚动联动在这里都是多余的。
 *
 * 返回按钮的 contentDescription 固定为「返回」/「关闭」，UI 测试按描述查找。
 */
@Composable
fun BsTopBar(
    title: String,
    onNavigate: (() -> Unit)?,
    modifier: Modifier = Modifier,
    navIcon: BsNavIcon = BsNavIcon.Back,
    subtitle: String? = null,
    /** 标题前的小图标（灰黑，与列表行的图标同色），比如本地列表的自定义图标。 */
    titleIcon: ImageVector? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = BsSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onNavigate != null && navIcon != BsNavIcon.None) {
            IconButton(onClick = onNavigate) {
                when (navIcon) {
                    BsNavIcon.Close -> Icon(Icons.Filled.Close, contentDescription = "关闭")
                    else -> Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        } else {
            Spacer(Modifier.width(BsSpacing.md))
        }
        if (titleIcon != null) {
            Icon(
                titleIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = BsSpacing.xs).size(BsSize.icon),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = BsSpacing.xs),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}
