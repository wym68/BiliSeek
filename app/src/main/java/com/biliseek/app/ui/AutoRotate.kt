package com.biliseek.app.ui

import android.content.Context
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * 读手机实际朝向（判断见 [deviceTilt] / [resolveAutoRotate]）。
 *
 * 只在 [enabled] 时开着传感器；朝向在竖、横之间变化时回调（变化前、变化后），临界角度和平放不回调。
 * 每次重新开启都从「上一次未知」开始，第一次读到的朝向只记下、不触发动作。
 */
@Composable
fun DeviceTiltEffect(enabled: Boolean, onTiltChange: (previous: DeviceTilt?, current: DeviceTilt) -> Unit) {
    val context = LocalContext.current
    val currentOnTiltChange by rememberUpdatedState(onTiltChange)
    DisposableEffect(enabled, context) {
        if (!enabled) return@DisposableEffect onDispose {}
        var last: DeviceTilt? = null
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                val tilt = deviceTilt(degrees) ?: return
                if (tilt == last) return
                val previous = last
                last = tilt
                currentOnTiltChange(previous, tilt)
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
}

/** 系统「自动旋转」开关是否打开。要动作的那一刻才读，不常驻监听。 */
fun isSystemAutoRotateOn(context: Context): Boolean =
    Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
