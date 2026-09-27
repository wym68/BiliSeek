package com.biliseek.app.ui

import android.media.AudioManager
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.formatDuration

/** 手势进行中屏幕中央的提示。 */
sealed interface GestureHud {
    data class Seek(val targetMs: Long, val durationMs: Long, val deltaMs: Long) : GestureHud
    data class Level(val label: String, val level: Float) : GestureHud
    data class Speed(val speed: Float) : GestureHud
}

/** 手势层要回调出去的动作。 */
class FullscreenGestureCallbacks(
    val onTap: () -> Unit,
    val onDoubleTap: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onSetSpeed: (Float) -> Unit,
    val onHud: (GestureHud?) -> Unit,
)

/**
 * 全屏手势。
 *
 * - 单击：显示 / 隐藏控制层（锁定时只显示解锁按钮）
 * - 双击：播放 / 暂停
 * - 长按：临时加速，松手恢复（见 [resolveLongPressSpeed]）
 * - 横向拖：调进度，松手才 seek——拖动过程中只预览，不然每一帧都在 seek
 * - 左半屏纵向拖：亮度（只改本窗口，退出全屏恢复）
 * - 右半屏纵向拖：媒体音量
 *
 * 锁定时除单击外全部失效。判定逻辑在 FullscreenGesturePolicy.kt。
 */
fun Modifier.fullscreenGestures(
    locked: Boolean,
    state: State<PlaybackState>,
    window: Window?,
    audioManager: AudioManager?,
    touchSlop: Float,
    edgeExclusion: Float,
    callbacks: FullscreenGestureCallbacks,
): Modifier = this
    .pointerInput(locked) {
        var speedBeforeLongPress: Float? = null
        detectTapGestures(
            onTap = { callbacks.onTap() },
            onDoubleTap = if (locked) null else { _ -> callbacks.onDoubleTap() },
            onLongPress = if (locked) {
                null
            } else {
                { _ ->
                    val current = state.value
                    resolveLongPressSpeed(
                        currentSpeed = current.speed,
                        losslessActive = current.audioQuality ==
                            com.biliseek.core.player.AudioQuality.LOSSLESS,
                    )?.let { boosted ->
                        speedBeforeLongPress = current.speed
                        callbacks.onSetSpeed(boosted)
                        callbacks.onHud(GestureHud.Speed(boosted))
                    }
                }
            },
            onPress = {
                tryAwaitRelease()
                speedBeforeLongPress?.let { original ->
                    callbacks.onSetSpeed(original)
                    callbacks.onHud(null)
                }
                speedBeforeLongPress = null
            },
        )
    }
    .pointerInput(locked) {
        if (locked) return@pointerInput
        var start = Offset.Zero
        var total = Offset.Zero
        var gesture: DragGesture? = null
        var startPosition = 0L
        var startLevel = 0f
        var seekTarget = 0L

        fun finish() {
            callbacks.onHud(null)
            gesture = null
        }

        detectDragGestures(
            onDragStart = {
                start = it
                total = Offset.Zero
                gesture = null
            },
            onDragEnd = {
                if (gesture == DragGesture.SEEK) callbacks.onSeek(seekTarget)
                finish()
            },
            onDragCancel = { finish() },
            onDrag = { change, amount ->
                total += amount
                val width = size.width.toFloat()
                val height = size.height.toFloat()
                if (gesture == null) {
                    gesture = resolveDragGesture(
                        start.x, start.y, width, height, total.x, total.y, touchSlop, edgeExclusion,
                    )
                    when (gesture) {
                        DragGesture.SEEK -> startPosition = state.value.positionMs
                        DragGesture.BRIGHTNESS -> startLevel = currentBrightness(window)
                        DragGesture.VOLUME -> startLevel = currentVolume(audioManager)
                        DragGesture.NONE, null -> Unit
                    }
                }
                when (gesture) {
                    DragGesture.SEEK -> {
                        val duration = state.value.durationMs
                        seekTarget = resolveSeekTarget(startPosition, total.x, width, duration)
                        callbacks.onHud(GestureHud.Seek(seekTarget, duration, seekTarget - startPosition))
                    }
                    DragGesture.BRIGHTNESS -> {
                        val level = resolveLevel(startLevel, total.y, height)
                        setBrightness(window, level)
                        callbacks.onHud(GestureHud.Level("亮度", level))
                    }
                    DragGesture.VOLUME -> {
                        val level = resolveLevel(startLevel, total.y, height)
                        setVolume(audioManager, level)
                        callbacks.onHud(GestureHud.Level("音量", level))
                    }
                    DragGesture.NONE, null -> Unit
                }
                if (gesture != null && gesture != DragGesture.NONE) change.consume()
            },
        )
    }

/** 手势提示气泡。 */
@Composable
fun GestureHudBubble(hud: GestureHud, modifier: Modifier = Modifier) {
    val text = when (hud) {
        is GestureHud.Seek -> {
            val sign = if (hud.deltaMs >= 0) "+" else "-"
            "${formatDuration(hud.targetMs)} / ${formatDuration(hud.durationMs)}\n" +
                "$sign${formatDuration(kotlin.math.abs(hud.deltaMs))}"
        }
        is GestureHud.Level -> "${hud.label} ${(hud.level * 100).toInt()}%"
        is GestureHud.Speed -> "${formatSpeed(hud.speed)} 加速中"
    }
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** 当前窗口亮度（0..1）。窗口没覆盖过亮度时读系统亮度。 */
private fun currentBrightness(window: Window?): Float {
    val override = window?.attributes?.screenBrightness ?: -1f
    if (override >= 0f) return override
    val resolver = window?.context?.contentResolver ?: return 0.5f
    val system = runCatching {
        android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(128)
    return (system / 255f).coerceIn(0f, 1f)
}

/** 只改本窗口亮度，不动系统设置——退出全屏由 [resetBrightness] 还原。 */
private fun setBrightness(window: Window?, level: Float) {
    window ?: return
    window.attributes = window.attributes.apply {
        // 0 在部分机型上会直接黑屏，留一点底
        screenBrightness = level.coerceAtLeast(MIN_BRIGHTNESS)
    }
}

/** 撤销本窗口的亮度覆盖，回到跟随系统。 */
internal fun resetBrightness(window: Window?) {
    window ?: return
    window.attributes = window.attributes.apply {
        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
}

private fun currentVolume(audioManager: AudioManager?): Float {
    audioManager ?: return 0f
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
}

private fun setVolume(audioManager: AudioManager?, level: Float) {
    audioManager ?: return
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    // 勿扰模式等情况下系统可能拒绝改音量，拒绝就算了，不能崩
    runCatching {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt(), 0)
    }
}

private const val MIN_BRIGHTNESS = 0.01f
