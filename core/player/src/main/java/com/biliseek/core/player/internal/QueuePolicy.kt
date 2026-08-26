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

/**
 * 「下一首播放」的插入位置。
 *
 * 队列为空或还没起播（[current] < 0）时追加到末尾；否则插在当前项之后。
 */
internal fun resolveEnqueueNextIndex(current: Int, size: Int): Int =
    if (current < 0) size else (current + 1).coerceAtMost(size)

/**
 * 在 [insertAt] 处插入一项后，当前下标应变成什么。
 *
 * 插入点在当前项**之前或同位**时，当前项被顶下去了一格；否则不动。
 * 忘了修正这个下标，「下一首播放」就会表现为「插完之后正在播的歌变了」。
 */
internal fun resolveIndexAfterInsert(current: Int, insertAt: Int): Int =
    if (current >= 0 && insertAt <= current) current + 1 else current

/** 手动「下一首」。单曲循环下手动下一首也应换歌，不能原地打转。 */
internal fun resolveManualNextIndex(current: Int, size: Int, mode: PlayMode): Int? {
    if (size <= 0) return null
    return when (mode) {
        PlayMode.SEQUENTIAL -> (current + 1).takeIf { it < size }
        else -> (current + 1) % size
    }
}
