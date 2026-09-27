package com.biliseek.app.ui

import kotlin.math.abs

/**
 * 倍速档位。竖屏三点菜单与全屏面板共用同一份，免得两处档位对不上。
 *
 * 上限 3×：再往上 ExoPlayer 的时间伸缩虽然能跑，人声已经听不清，没有实际用途。
 */
val PLAYBACK_SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

/** 两个倍速是否算同一档（浮点比较留余量）。 */
fun isSameSpeed(a: Float, b: Float): Boolean = abs(a - b) < 0.01f

/** 档位文案：`0.75×`、`1.5×`、`2×`——整数不带多余的 `.0`。 */
fun formatSpeed(speed: Float): String {
    val rounded = (speed * 100).toInt() / 100f
    val text = if (rounded == rounded.toInt().toFloat()) {
        rounded.toInt().toString()
    } else {
        rounded.toString().trimEnd('0').trimEnd('.')
    }
    return "$text×"
}

/**
 * 全屏底栏倍速按钮上的字。1× 时显示「倍速」（入口提示），
 * 其余显示当前档位——一眼能看出现在不是正常速度。
 */
fun speedButtonLabel(speed: Float): String =
    if (isSameSpeed(speed, 1.0f)) "倍速" else formatSpeed(speed)
