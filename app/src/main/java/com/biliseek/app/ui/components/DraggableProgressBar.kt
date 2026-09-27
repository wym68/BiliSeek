package com.biliseek.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 可拖动进度条（Ch5 §5.1.1）。
 *
 * 与 PlayerControls.kt 的 ProgressBar 同款"本地态 + onValueChangeFinished"模式：
 * Slider 默认触摸区比视觉条大；minibar 上用 height=2dp 时触摸区被外层 Box 撑到 24dp。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraggableProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    height: Dp = 2.dp,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = durationMs.coerceAtLeast(1L).toFloat()
    val value = (if (dragging) dragValue else positionMs.toFloat()).coerceIn(0f, duration)

    // 触摸区扩到至少 24dp，确保细条也能点中（工单 5.7.4 行为约束 #6）
    val touchHeight = height.coerceAtLeast(24.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(touchHeight),
        contentAlignment = Alignment.Center,
    ) {
        Slider(
            value = value,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                onSeek(dragValue.toLong())
                dragging = false
            },
            valueRange = 0f..duration,
            enabled = durationMs > 0,
            modifier = Modifier.fillMaxWidth().height(touchHeight),
        )
    }
}