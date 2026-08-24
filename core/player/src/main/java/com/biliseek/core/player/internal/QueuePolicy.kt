package com.biliseek.core.player.internal

import com.biliseek.core.player.PlayMode

/**
 * 队列推进 —— 纯函数，可单测。
 *
 * 播完一首后该放哪一首，由播放模式唯一决定；返回 `null` 表示停止。
 */
internal fun resolveNextIndex(
    current: Int,
    size: Int,
    mode: PlayMode,
    random: (Int) -> Int = { (0 until it).random() },
): Int? {
    if (size <= 0) return null
    return when (mode) {
        PlayMode.REPEAT_ONE -> current
        PlayMode.SHUFFLE -> if (size == 1) current else generateSequence { random(size) }
            .first { it != current }
        PlayMode.REPEAT_ALL -> (current + 1) % size
        PlayMode.SEQUENTIAL -> (current + 1).takeIf { it < size }
    }
}

/** 手动「上一首」。与自动推进不同：单曲循环下手动上一首仍应换歌。 */
internal fun resolvePreviousIndex(current: Int, size: Int, mode: PlayMode): Int? {
    if (size <= 0) return null
    return when (mode) {
        PlayMode.SEQUENTIAL -> (current - 1).takeIf { it >= 0 }
        else -> (current - 1 + size) % size
    }
}

/** 手动「下一首」。单曲循环下手动下一首也应换歌，不能原地打转。 */
internal fun resolveManualNextIndex(current: Int, size: Int, mode: PlayMode): Int? {
    if (size <= 0) return null
    return when (mode) {
        PlayMode.SEQUENTIAL -> (current + 1).takeIf { it < size }
        else -> (current + 1) % size
    }
}
