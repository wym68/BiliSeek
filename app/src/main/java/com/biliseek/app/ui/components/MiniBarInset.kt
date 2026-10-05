package com.biliseek.app.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 迷你条当前的高度（含「浮在底部」的下边距；收起成小横杠时是小横杠的高度；没有迷你条时为 0）。
 *
 * 迷你条叠在页面最下面，页面一直排到底部导航栏上沿（延伸到迷你条后面，「贴底」的圆角外、浮动卡片四周能看到页面；
 * 设计稿 docs/superpowers/specs/2026-10-04-minibar-float-and-theme-design.md）。所以：
 * - 列表 / 可滚动内容的底部留白要加上它，滑到底时最后一条才停在迷你条上方；
 * - 固定在页面底部的东西（输入框、提示）要整体抬高它。
 */
val LocalMiniBarInset = compositionLocalOf { 0.dp }

/** 列表的 contentPadding：上 [top]、下 [bottom] 再加上迷你条的高度（滑到底时最后一条停在迷你条上方）。 */
@androidx.compose.runtime.Composable
fun miniBarContentPadding(top: Dp = 0.dp, bottom: Dp = 0.dp): androidx.compose.foundation.layout.PaddingValues =
    androidx.compose.foundation.layout.PaddingValues(top = top, bottom = bottom + LocalMiniBarInset.current)
