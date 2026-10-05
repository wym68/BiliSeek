package com.biliseek.app.ui

import com.biliseek.core.player.formatDuration

/**
 * 播放页定时胶囊上的字：没设时写「定时」（入口提示），设了写剩余时间，选的是播完当前就写「播完当前」——
 * 一眼能看出定时在走。
 */
fun sleepTimerChipLabel(remainingMs: Long?, endOfTrack: Boolean): String = when {
    endOfTrack -> "播完当前"
    remainingMs != null -> formatDuration(remainingMs)
    else -> "定时"
}
