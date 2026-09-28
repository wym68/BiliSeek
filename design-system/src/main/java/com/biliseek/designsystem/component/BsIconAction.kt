package com.biliseek.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.biliseek.designsystem.theme.BsShapes
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing

/** 互动按钮的排布：横排（动态卡片、评论行）或竖排（视频互动栏）。 */
enum class BsIconActionLayout { Horizontal, Vertical }

/**
 * 统一的「图标 + 数字」互动按钮：点赞 / 投币 / 收藏 / 评论 / 转发 / 分享都用它。
 *
 * - [count] 为 null 或 0 时显示 [label]（与官方客户端一致：没有数字时显示动作名）；
 *   [showLabelWhenEmpty] = false 时只显示图标。
 * - [active]：已点赞 / 已收藏等，图标换成 [activeIcon]、染 [activeColor]，并有一次弹性缩放。
 * - [busy]：请求进行中，半透明且不可再点（防连点被风控）。
 * - 无障碍：整个按钮合并为一个节点，contentDescription = [label]，状态描述「已激活」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BsIconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Long? = null,
    activeIcon: ImageVector = icon,
    active: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    layout: BsIconActionLayout = BsIconActionLayout.Horizontal,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showLabelWhenEmpty: Boolean = true,
) {
    val tint = when {
        !enabled -> inactiveColor.copy(alpha = 0.38f)
        active -> activeColor
        else -> inactiveColor
    }
    val scale by animateFloatAsState(
        targetValue = if (active) 1f else 0.96f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "iconActionScale",
    )
    val text = when {
        count != null && count > 0 -> formatCompactCount(count)
        showLabelWhenEmpty -> label
        else -> null
    }
    val clickModifier = modifier
        .clip(BsShapes.sm)
        .combinedClickable(
            enabled = enabled && !busy,
            onClick = onClick,
            onLongClick = onLongClick,
        )
        .semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = label
            if (active) stateDescription = "已激活"
        }
    val contentAlpha = if (busy) 0.5f else 1f
    val iconVector = if (active) activeIcon else icon

    when (layout) {
        BsIconActionLayout.Horizontal -> Row(
            clickModifier
                .defaultMinSize(minHeight = 32.dp)
                .padding(horizontal = BsSpacing.sm, vertical = BsSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(BsSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                iconVector,
                contentDescription = null,
                tint = tint.copy(alpha = tint.alpha * contentAlpha),
                modifier = Modifier.size(BsSize.iconSm).scale(scale),
            )
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint.copy(alpha = tint.alpha * contentAlpha),
                    maxLines = 1,
                )
            }
        }

        BsIconActionLayout.Vertical -> Column(
            clickModifier
                .defaultMinSize(minWidth = BsSize.touchTarget, minHeight = BsSize.touchTarget)
                .padding(horizontal = BsSpacing.sm, vertical = BsSpacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BsSpacing.xxs),
        ) {
            Icon(
                iconVector,
                contentDescription = null,
                tint = tint.copy(alpha = tint.alpha * contentAlpha),
                modifier = Modifier.size(BsSize.iconLg).scale(scale),
            )
            Text(
                text ?: " ",
                style = MaterialTheme.typography.labelSmall,
                color = tint.copy(alpha = tint.alpha * contentAlpha),
                maxLines = 1,
            )
        }
    }
}
