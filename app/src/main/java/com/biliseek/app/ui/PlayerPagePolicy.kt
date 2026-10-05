package com.biliseek.app.ui

import com.biliseek.app.data.DefaultPresentation
import com.biliseek.core.player.PresentationMode

// 播放页与迷你播放条之间的听 / 看规则（设计稿：docs/superpowers/specs/2026-10-04-leave-player-to-listen-design.md）。
//
// 内嵌画面只在播放页上：回到迷你播放条（收起、返回、点底部页面、跳去别的页面）时，在看就自动切成听。
// 这次自动切换不算用户「上次」的选择——「起播模式：记住上次」记的是用户在播放页上亲手点的听 / 看，
// 并在打开播放页时生效（起播时不再自动进看：那时画面没有地方显示）。

/** 离开播放页时要不要先切成听：在看（内嵌或全屏）就要；画中画是独立窗口，不经过这里。 */
fun mustLeaveToListen(presentation: PresentationMode): Boolean =
    presentation == PresentationMode.INLINE || presentation == PresentationMode.FULLSCREEN

/** 迷你播放条在显示（不在播放页、不在全屏、不在画中画）却还在内嵌看：兜底切回听。 */
fun shouldForceListen(
    onPlayerPage: Boolean,
    presentation: PresentationMode,
    fullscreen: Boolean,
    inPip: Boolean,
): Boolean = !onPlayerPage && !fullscreen && !inPip && presentation == PresentationMode.INLINE

/** 打开播放页时要不要自动切回看：「记住上次」开着、上次离开播放页时在看、有内容、现在在听。 */
fun shouldAutoWatchOnPlayerPage(
    defaultPresentation: DefaultPresentation,
    lastWasVideo: Boolean,
    presentation: PresentationMode,
    hasMedia: Boolean,
): Boolean = defaultPresentation == DefaultPresentation.REMEMBER_LAST &&
    lastWasVideo && hasMedia && presentation == PresentationMode.NONE
