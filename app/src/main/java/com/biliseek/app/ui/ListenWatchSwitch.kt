package com.biliseek.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.core.player.PlaybackState

/**
 * 听 | 看开关：分段胶囊，选中的一段填主色。放在播放页顶栏正中（2026-10-27 起，取代「正在播放」几个字）。
 *
 * 一眼看得出现在是听还是看；点已选中的一段不做事。顶栏用紧凑尺寸：看得见的胶囊高 32dp，点击区 48dp 高。
 */
@Composable
internal fun ListenWatchSwitch(isAudioMode: Boolean, onSwitchMode: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    fun switchTo(audio: Boolean) {
        if (audio == isAudioMode) return
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        onSwitchMode()
    }
    val outline = MaterialTheme.colorScheme.outlineVariant
    // 点击区 48dp 高；看得见的胶囊仍是中间 32dp（上下各留 8dp，那里也能点到）。描边画在胶囊里面，同原来的 border
    Row(
        modifier
            .height(48.dp)
            .drawBehind {
                val stroke = 1.dp.toPx()
                val top = 8.dp.toPx() + stroke / 2
                val h = size.height - top * 2
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(stroke / 2, top),
                    size = Size(size.width - stroke, h),
                    cornerRadius = CornerRadius(h / 2),
                    style = Stroke(width = stroke),
                )
            }
            .padding(horizontal = 3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwitchSegment("听", Icons.Filled.Headphones, selected = isAudioMode) { switchTo(audio = true) }
        SwitchSegment("看", WatchTvIcon, selected = !isAudioMode) { switchTo(audio = false) }
    }
}

@Composable
private fun SwitchSegment(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val contentColor = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
    val interaction = remember { MutableInteractionSource() }
    // 整段 48dp 高都能点；看得见的一段是 26dp 高（32dp 胶囊内缩 3dp），水波纹也只画在这一段里
    Box(
        Modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(26.dp)
                .widthIn(min = 52.dp)
                .clip(CircleShape)
                .background(if (selected) scheme.primary else Color.Transparent)
                .indication(interaction, ripple())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = contentColor)
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/**
 * 倍速 / 定时胶囊：图标 + 文字。只在调过倍速、设了定时时才会画出来（见 [PlayerStatusChips]），所以现在 [active] 总是 true、主色，
 * 一眼看出现在不是默认状态；为 false 的灰色样式目前没有调用方。
 *
 * 胶囊高 32dp，点击区扩到 48dp。[description] 是给读屏的完整描述（只有「1.5×」「28:40」没有上下文）；
 * 不传时用文字本身。
 */
@Composable
private fun PlayerChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val contentColor = if (active) scheme.primary else scheme.onSurfaceVariant
    val borderColor = if (active) scheme.primary.copy(alpha = 0.5f) else scheme.outlineVariant
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .height(32.dp)
            .clip(CircleShape)
            .background(if (active) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .border(BorderStroke(1.dp, borderColor), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = contentColor)
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 进度条上方的倍速 / 定时小标签：只有调过倍速、设了定时才出现（都没设时什么都不画），点了打开对应的弹层。
 * 入口本身在三点菜单里（2026-10-27 起，设计稿 docs/superpowers/specs/2026-10-27-player-layout-and-collection-queue-design.md）。
 */
@Composable
internal fun PlayerStatusChips(
    state: PlaybackState,
    onOpenSpeed: () -> Unit,
    onOpenTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speedActive = !isSameSpeed(state.speed, 1.0f)
    val timerActive = state.sleepTimerActive
    if (!speedActive && !timerActive) return
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (speedActive) {
            val label = speedButtonLabel(state.speed)
            PlayerChip(
                icon = Icons.Filled.Speed,
                label = label,
                active = true,
                onClick = onOpenSpeed,
                description = "倍速 $label",
            )
        }
        if (timerActive) {
            val label = sleepTimerChipLabel(state.sleepTimerRemainingMs, state.sleepTimerEndOfTrack)
            PlayerChip(
                icon = Icons.Filled.Timer,
                label = label,
                active = true,
                onClick = onOpenTimer,
                description = "定时关闭 $label",
            )
        }
    }
}
