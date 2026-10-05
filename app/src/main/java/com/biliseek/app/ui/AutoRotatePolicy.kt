package com.biliseek.app.ui

// 看视频时横过手机自动进全屏、竖回来自动退出（设计稿：docs/superpowers/specs/2026-10-04-fullscreen-tune-design.md）。
//
// 除全屏外的页面锁了竖屏，系统不会再因为转手机去改界面方向，只能自己读方向传感器（OrientationEventListener）。
// 只认「变化」：手动点全屏时手机还竖着，不会马上被「竖着」退出；横着手动退出全屏，也不会马上又被拉回全屏。

/** 手机实际是竖着还是横着拿（与界面方向无关）。 */
enum class DeviceTilt { PORTRAIT, LANDSCAPE }

/** 自动转向要做的事。 */
enum class AutoRotateAction { ENTER_FULLSCREEN, EXIT_FULLSCREEN }

/**
 * 方向传感器的角度（0 = 竖着正放，顺时针增大；-1 = 平放测不出）→ 竖或横。
 * 竖、横之间留出空档、倒着拿（180° 附近）也不算，返回 null 表示不改变判断——临界角度附近晃不会来回跳。
 */
fun deviceTilt(degrees: Int): DeviceTilt? = when {
    degrees < 0 -> null
    degrees <= TILT_TOLERANCE || degrees >= 360 - TILT_TOLERANCE -> DeviceTilt.PORTRAIT
    degrees in 90 - TILT_TOLERANCE..90 + TILT_TOLERANCE ||
        degrees in 270 - TILT_TOLERANCE..270 + TILT_TOLERANCE -> DeviceTilt.LANDSCAPE
    else -> null
}

/**
 * 手机朝向从 [previous] 变成 [current] 时要做什么。
 *
 * - 系统「自动旋转」关着：什么都不做。
 * - 刚开始监听（[previous] 为 null）或朝向没变：什么都不做。
 * - 竖 → 横、不在全屏、[canAutoEnter]：进全屏。
 * - 横 → 竖、在**横屏**全屏里（[fullscreen] 是全屏方向，null = 不在全屏）：退出全屏。竖屏视频的全屏本来就竖着，不退。
 */
fun resolveAutoRotate(
    previous: DeviceTilt?,
    current: DeviceTilt,
    fullscreen: FullscreenOrientation?,
    canAutoEnter: Boolean,
    systemAutoRotate: Boolean,
): AutoRotateAction? = when {
    !systemAutoRotate || previous == null || previous == current -> null
    current == DeviceTilt.LANDSCAPE && fullscreen == null && canAutoEnter -> AutoRotateAction.ENTER_FULLSCREEN
    current == DeviceTilt.PORTRAIT && fullscreen == FullscreenOrientation.LANDSCAPE -> AutoRotateAction.EXIT_FULLSCREEN
    else -> null
}

/** 横过手机能不能自动进全屏：在播放页、正在内嵌看、不在画中画、视频是横的（比例还不知道按横的算）。 */
fun canAutoEnterFullscreen(
    onPlayerPage: Boolean,
    watchingInline: Boolean,
    inPip: Boolean,
    videoAspectRatio: Float?,
): Boolean = onPlayerPage && watchingInline && !inPip &&
    resolveFullscreenOrientation(videoAspectRatio) == FullscreenOrientation.LANDSCAPE

/** 竖、横两个区间各自的半宽（度）。 */
private const val TILT_TOLERANCE = 30
