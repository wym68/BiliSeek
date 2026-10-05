package com.biliseek.app.ui

import com.biliseek.core.player.PlayMode

/**
 * 点一下播放模式按钮后的下一个模式：顺序播放 → 列表循环 → 单曲循环 → 随机播放 → 顺序播放。
 * 顺序就是 [PlayMode] 的声明顺序。
 */
fun nextPlayMode(current: PlayMode): PlayMode {
    val modes = PlayMode.entries
    return modes[(modes.indexOf(current) + 1) % modes.size]
}

/** 模式名：切换时的浮框提示与设置页的选项共用，两处不会各写一份。 */
fun playModeLabel(mode: PlayMode): String = when (mode) {
    PlayMode.SEQUENTIAL -> "顺序播放"
    PlayMode.REPEAT_ALL -> "列表循环"
    PlayMode.REPEAT_ONE -> "单曲循环"
    PlayMode.SHUFFLE -> "随机播放"
}
