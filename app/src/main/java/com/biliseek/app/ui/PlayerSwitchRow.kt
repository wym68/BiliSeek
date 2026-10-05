package com.biliseek.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * 播放页控制行下面的一排：倍速胶囊 · 听 | 看开关 · 定时胶囊。
 *
 * 开关始终在正中（与上面的播放键对齐）；两边的胶囊各占一半剩余宽度、紧贴开关，所以左右对称。
 * 点倍速 / 定时胶囊弹出现有的倍速 / 定时弹层（三点菜单里也有同样的入口）。
 */
@Composable
internal fun PlayerSwitchRow(
    state: PlaybackState,
    isAudioMode: Boolean,
    onSwitchMode: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speedActive = !isSameSpeed(state.speed, 1.0f)
    val speedLabel = speedButtonLabel(state.speed)
    val timerActive = state.sleepTimerActive
    val timerLabel = sleepTimerChipLabel(state.sleepTimerRemainingMs, state.sleepTimerEndOfTrack)

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            PlayerChip(
                icon = Icons.Filled.Speed,
                label = speedLabel,
                active = speedActive,
                onClick = onOpenSpeed,
                modifier = Modifier.padding(end = 8.dp),
                description = if (speedActive) "倍速 $speedLabel" else null,
            )
        }
        ListenWatchSwitch(isAudioMode, onSwitchMode)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            PlayerChip(
                icon = Icons.Filled.Timer,
                label = timerLabel,
                active = timerActive,
                onClick = onOpenTimer,
                modifier = Modifier.padding(start = 8.dp),
                description = if (timerActive) "定时关闭 $timerLabel" else null,
            )
        }
    }
}

/**
 * 听 | 看开关：分段胶囊，选中的一段填主色。
 *
 * 一眼看得出现在是听还是看；切换后换到另一段，不再用「看 / 退回听」这种一个按状态、一个按动作的文字。
 * 点已选中的一段不做事。
 */
@Composable
private fun ListenWatchSwitch(isAudioMode: Boolean, onSwitchMode: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    fun switchTo(audio: Boolean) {
        if (audio == isAudioMode) return
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        onSwitchMode()
    }

    Row(
        Modifier
            .height(40.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SwitchSegment("听", Icons.Filled.Headphones, selected = isAudioMode) { switchTo(audio = true) }
        SwitchSegment("看", WatchTvIcon, selected = !isAudioMode) { switchTo(audio = false) }
    }
}

@Composable
private fun SwitchSegment(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val contentColor = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxHeight()
            .widthIn(min = 60.dp)
            .clip(CircleShape)
            .background(if (selected) scheme.primary else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = contentColor)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * 倍速 / 定时胶囊：图标 + 文字。[active]（调过倍速、设了定时）时变主色，一眼看出现在不是默认状态。
 *
 * 胶囊高 32dp，点击区扩到 48dp。[description] 是设了值之后给读屏的完整描述（只有「1.5×」「28:40」没有上下文）；
 * 没设时文字本身（「倍速」「定时」）就够清楚，不另加。
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
