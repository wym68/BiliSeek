package com.biliseek.app.ui

import androidx.compose.foundation.gestures.PressGestureScope
import androidx.compose.runtime.State
import com.biliseek.core.player.AudioQuality
import com.biliseek.core.player.PlaybackState

/**
 * 长按临时加速、松手恢复（全屏与播放页小框共用；目标速度见 [resolveLongPressSpeed]）。
 *
 * 在 `detectTapGestures` 里用：`onLongPress = { boost.onLongPress() }`、`onPress = { boost.awaitRelease(this) }`。
 * 松手、或手势被打断（页面销毁、被别的手势抢走）都恢复原来的速度，不会卡在加速上。
 */
internal class LongPressSpeedBoost(
    private val state: State<PlaybackState>,
    private val onSetSpeed: (Float) -> Unit,
    /** 加速中显示「2.0× 加速中」，恢复后为 null。 */
    private val onHud: (GestureHud?) -> Unit,
    /** 开始加速的那一下（震动）。 */
    private val onBoosted: () -> Unit = {},
) {
    private var speedBefore: Float? = null

    fun onLongPress() {
        val current = state.value
        resolveLongPressSpeed(
            currentSpeed = current.speed,
            losslessActive = current.audioQuality == AudioQuality.LOSSLESS,
            isPlaying = current.isPlaying,
        )?.let { boosted ->
            speedBefore = current.speed
            onBoosted()
            onSetSpeed(boosted)
            onHud(GestureHud.Speed(boosted))
        }
    }

    /** 每次按下都调：等到松手（或被打断）再恢复。 */
    suspend fun awaitRelease(scope: PressGestureScope) {
        try {
            scope.tryAwaitRelease()
        } finally {
            speedBefore?.let { original ->
                onSetSpeed(original)
                onHud(null)
            }
            speedBefore = null
        }
    }
}
