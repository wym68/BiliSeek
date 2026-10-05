package com.biliseek.core.player.internal

import com.biliseek.core.player.PlaybackMode
import com.biliseek.core.player.PresentationMode

/**
 * 播放态推导 —— 纯函数，可单测。
 *
 * 这里是《架构设计篇》§5.2 核心不变式的**唯一实现点**：
 * 播放态不是独立存储的状态，而是由呈现态推导出来的。
 * 因此「视频轨启用 ⟺ 存在可见 surface」在结构上无法被违反。
 */
internal fun resolvePlaybackMode(
    presentation: PresentationMode,
    hasMedia: Boolean,
): PlaybackMode = when {
    !hasMedia -> PlaybackMode.IDLE
    presentation == PresentationMode.NONE -> PlaybackMode.AUDIO
    else -> PlaybackMode.VIDEO
}

/** 视频轨是否应当启用。同样由呈现态唯一决定。 */
internal fun shouldEnableVideoTrack(presentation: PresentationMode): Boolean =
    presentation != PresentationMode.NONE

/**
 * 进后台时是否回落纯音频。
 *
 * PiP 属于可见呈现态，**不回落**——否则 PiP 窗口会黑屏。
 * 这不是一条特例判断，而是「NONE 才回落」的自然结果。
 */
internal fun resolvePresentationOnEnterBackground(
    current: PresentationMode,
): PresentationMode = when (current) {
    PresentationMode.PIP -> PresentationMode.PIP
    else -> PresentationMode.NONE
}

/**
 * 换一条内容起播时的呈现态。
 *
 * **在播放页内嵌看时切下一首回到听。** 起播本来就会禁掉视频轨（「默认只听」在时序上的落点），
 * 呈现态必须跟着一起复位——只禁轨不复位，界面会以为还在看模式：
 * 渲染一块没有画面的 SurfaceView（黑屏、不显示封面）。
 *
 * 两个例外：
 * - PiP，理由与 [resolvePresentationOnEnterBackground] 完全相同：
 *   那是个**系统窗口**，把轨关掉只会剩一个黑窗，而不是「回到听」。
 * - 全屏（用户要求连续看）：下一条有画面就接着全屏播；没有画面（[nextHasVideo] 为假，
 *   只下了音频的本地文件）就回到听，全屏层随之退出。
 */
internal fun resolvePresentationOnTrackChange(
    current: PresentationMode,
    sameContent: Boolean = false,
    nextHasVideo: Boolean = true,
): PresentationMode = when {
    // 同一条内容重新取流（换音质、倍速跨过无损阈值）不是「换内容」：
    // 用户正在看就接着看，退回听会把全屏也一并踢掉
    sameContent -> current
    current == PresentationMode.PIP -> PresentationMode.PIP
    current == PresentationMode.FULLSCREEN && nextHasVideo -> PresentationMode.FULLSCREEN
    else -> PresentationMode.NONE
}

/** 切换的可读标签，用于诊断输出。 */
internal fun switchLabel(from: PresentationMode, to: PresentationMode): String {
    val fromVideo = shouldEnableVideoTrack(from)
    val toVideo = shouldEnableVideoTrack(to)
    return when {
        !fromVideo && toVideo -> "听 → 看"
        fromVideo && !toVideo -> "看 → 听"
        else -> "$from → $to"
    }
}
