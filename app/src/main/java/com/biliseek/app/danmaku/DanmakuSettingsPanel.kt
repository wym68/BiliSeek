package com.biliseek.app.danmaku

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 弹幕设置（不含容器），放在全屏侧边面板里。改动即时生效并持久化。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DanmakuSettingsOptions(
    settings: DanmakuSettings,
    onChange: ((DanmakuSettings) -> DanmakuSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SliderRow(
            label = "不透明度",
            valueText = "${(settings.opacity * 100).roundToInt()}%",
            value = settings.opacity,
            range = 0.2f..1f,
            steps = 7,
            onValueChange = { v -> onChange { it.copy(opacity = v) } },
        )
        SliderRow(
            label = "字号",
            valueText = "${(settings.fontScale * 100).roundToInt()}%",
            value = settings.fontScale,
            range = 0.5f..2f,
            steps = 5,
            onValueChange = { v -> onChange { it.copy(fontScale = v) } },
        )
        SliderRow(
            label = "屏蔽等级",
            valueText = settings.minWeight.toString(),
            value = settings.minWeight.toFloat(),
            range = 0f..10f,
            steps = 9,
            onValueChange = { v -> onChange { it.copy(minWeight = v.roundToInt()) } },
        )

        Text("显示区域", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DanmakuSettings.AREA_CHOICES.forEach { area ->
                FilterChip(
                    selected = settings.area == area,
                    onClick = { onChange { it.copy(area = area) } },
                    label = { Text(if (area >= 1f) "全屏" else "${(area * 100).roundToInt()}%") },
                )
            }
        }

        Text("滚动速度", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DanmakuSettings.SPEED_CHOICES.forEach { speed ->
                FilterChip(
                    selected = settings.speed == speed,
                    onClick = { onChange { it.copy(speed = speed) } },
                    label = {
                        Text(
                            when {
                                speed < 1f -> "慢"
                                speed > 1f -> "快"
                                else -> "适中"
                            },
                        )
                    },
                )
            }
        }

        Text("显示类型", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.showScroll,
                onClick = { onChange { it.copy(showScroll = !it.showScroll) } },
                label = { Text("滚动") },
            )
            FilterChip(
                selected = settings.showTop,
                onClick = { onChange { it.copy(showTop = !it.showTop) } },
                label = { Text("顶部") },
            )
            FilterChip(
                selected = settings.showBottom,
                onClick = { onChange { it.copy(showBottom = !it.showBottom) } },
                label = { Text("底部") },
            )
            FilterChip(
                selected = settings.showColored,
                onClick = { onChange { it.copy(showColored = !it.showColored) } },
                label = { Text("彩色") },
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps)
    }
}
