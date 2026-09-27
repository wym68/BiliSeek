package com.biliseek.app.ui

import com.biliseek.core.player.PresentationMode

/**
 * 全屏 / PiP 呈现层的决策函数。
 *
 * 全屏层本身是个带副作用的 Composable（改窗口方向、系统栏、常亮标记），
 * 判断逻辑抽到这里，保持无副作用、可单测。
 */

/** 全屏时要锁定的屏幕方向。 */
enum class FullscreenOrientation { PORTRAIT, LANDSCAPE }

/**
 * 竖屏视频保持竖屏，其余转横屏。
 *
 * 比例未知（还没拿到首帧）按 16:9 兜底——绝大多数投稿是横屏，
 * 先转横屏、拿到竖屏尺寸再转回来，比反过来少转一次。
 */
fun resolveFullscreenOrientation(videoAspectRatio: Float?): FullscreenOrientation =
    if ((videoAspectRatio ?: DEFAULT_ASPECT) < 1f) {
        FullscreenOrientation.PORTRAIT
    } else {
        FullscreenOrientation.LANDSCAPE
    }

/**
 * 控制层要不要开始自动隐藏倒计时。
 *
 * - 暂停时不藏：用户多半正要操作。
 * - 拖进度条时不藏：否则进度条会在手指下淡出。
 * - 面板（倍速 / 画质等）开着时不藏：面板是控制层的一部分。
 */
fun shouldAutoHideControls(
    controlsVisible: Boolean,
    isPlaying: Boolean,
    interacting: Boolean,
): Boolean = controlsVisible && isPlaying && !interacting

/**
 * 全屏层是否应当自行退出。
 *
 * 全屏只在「看」时有意义。呈现态被外部降回 [PresentationMode.NONE]
 * （息屏 / 进后台的回落）后，留着全屏层只剩一个有声音的黑框，
 * 而且全屏里没有「看」按钮能把画面找回来。
 */
fun shouldLeaveFullscreen(presentation: PresentationMode): Boolean =
    presentation == PresentationMode.NONE

/**
 * 退出 PiP 后恢复到哪个呈现态。
 *
 * 回到进 PiP 之前那个可见态（全屏或内联），不一律回内联——否则界面还挂着全屏层，
 * 播放层却以为在内联，两边对不上。
 */
fun resolvePresentationAfterPip(before: PresentationMode?): PresentationMode = when (before) {
    PresentationMode.INLINE, PresentationMode.FULLSCREEN -> before
    else -> PresentationMode.INLINE
}

/** 看模式且正在播放时保持屏幕常亮；内联与全屏一视同仁。 */
fun shouldKeepScreenOn(presentation: PresentationMode, isPlaying: Boolean): Boolean =
    presentation != PresentationMode.NONE && isPlaying

/**
 * PiP 窗口宽高比，返回 (分子, 分母)。
 *
 * 系统只接受 [1:2.39, 2.39:1] 之间的比例，超出直接抛异常，所以要夹紧。
 * 比例未知时按 16:9。
 */
fun resolvePipAspectRatio(videoAspectRatio: Float?): Pair<Int, Int> {
    val ratio = (videoAspectRatio?.takeIf { it > 0f } ?: DEFAULT_ASPECT)
        .coerceIn(PIP_MIN_ASPECT, PIP_MAX_ASPECT)
    return (ratio * PIP_RATIO_DENOMINATOR).toInt() to PIP_RATIO_DENOMINATOR
}

private const val DEFAULT_ASPECT = 16f / 9f

// 系统上限是 2.39:1，Rational 比较有精度误差，往里收一点
private const val PIP_MAX_ASPECT = 2.38f
private const val PIP_MIN_ASPECT = 1f / 2.38f
private const val PIP_RATIO_DENOMINATOR = 1000
