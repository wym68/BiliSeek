package com.biliseek.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.designsystem.theme.BsShapes
import com.biliseek.designsystem.theme.BsSpacing

/*
 * 设置类页面的统一外观（设置主页、AI 总结设置页共用）：
 * - 一组设置 = 组名（主色小字）+ 一张圆角卡片 [SettingsGroup]；
 * - 卡片里每一行 = 左侧带底色的小图标 + 标题 / 一句说明 + 右侧当前值或开关；
 * - 多选一的设置行上只显示当前值，点开 [SettingsChoiceSheet] 从底部弹出单选，每项带说明，点选即生效；
 * - 开关型设置整行可点（[SettingsSwitchRow]）。
 */

/** 一组设置：组名 + 圆角卡片。行与行之间用 [SettingsDivider] 隔开。 */
@Composable
fun SettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = BsSpacing.sm, bottom = BsSpacing.sm),
            )
        }
        Surface(
            shape = BsShapes.lg,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

/** 卡片内两行之间的分隔线（左侧让出图标位）。 */
@Composable
fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** 设置行左侧的图标：圆角底色 + 主色图标。 */
@Composable
fun SettingsIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(36.dp)
            .clip(BsShapes.md)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 普通设置行。[value] 显示在右侧（多选一设置的当前值）；有 [onClick] 时末尾带右箭头。
 * [trailing] 用于自定义右侧内容（如外链图标），给了就不再画箭头。
 */
@Composable
fun SettingsRow(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsIcon(icon)
        SettingsTexts(title, subtitle, Modifier.weight(1f))
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 开关型设置行：整行可点。 */
@Composable
fun SettingsSwitchRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsIcon(icon)
        SettingsTexts(title, subtitle, Modifier.weight(1f))
        // 点击交给整行（toggleable），开关本身只负责显示
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingsTexts(title: String, subtitle: String?, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 多选一里的一项。 */
data class SettingsChoice<T>(val value: T, val label: String, val description: String? = null)

/**
 * 多选一的底部弹层：标题 + 一句说明 + 单选列表（每项可带说明）。点选即回调并关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingsChoiceSheet(
    title: String,
    choices: List<SettingsChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    note: String? = null,
) {
    SettingsSheet(title = title, note = note, onDismiss = onDismiss) {
        choices.forEach { choice ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = choice.value == selected,
                        role = Role.RadioButton,
                        onClick = {
                            onSelect(choice.value)
                            onDismiss()
                        },
                    )
                    .padding(horizontal = BsSpacing.xl, vertical = BsSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BsSpacing.lg),
            ) {
                RadioButton(selected = choice.value == selected, onClick = null)
                SettingsTexts(choice.label, choice.description, Modifier.weight(1f))
            }
        }
    }
}

/** 设置用的底部弹层外壳：标题 + 可选说明 + 可滚动内容。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    title: String,
    onDismiss: () -> Unit,
    note: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = BsSpacing.lg),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = BsSpacing.xl),
            )
            if (!note.isNullOrBlank()) {
                Spacer(Modifier.size(BsSpacing.xs))
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = BsSpacing.xl),
                )
            }
            Spacer(Modifier.size(BsSpacing.sm))
            content()
        }
    }
}
