package com.biliseek.core.player.internal

/** 位置小于此值不视为「有进度」，避免刚起播就被判定为续播。 */
internal const val RESUME_MIN_MS = 5_000L

/** 距结尾不足此值时视为「已听完」，从头开始。 */
internal const val RESUME_TAIL_MARGIN_MS = 15_000L

/**
 * 决定起播位置。
 *
 * 两端都要裁掉：
 * - 开头几秒的进度没有续播价值
 * - **接近结尾的进度更没有**——从 61s 续播一首 62s 的歌，等于立刻播完，
 *   用户看到的是「点了没反应」。这个 case 在实测中真实发生过。
 *
 * @param durationMs 时长未知时传 0，此时只做开头裁剪
 */
internal fun resolveStartPosition(savedMs: Long, durationMs: Long): Long = when {
    savedMs <= RESUME_MIN_MS -> 0L
    durationMs > 0 && savedMs >= durationMs - RESUME_TAIL_MARGIN_MS -> 0L
    else -> savedMs
}
