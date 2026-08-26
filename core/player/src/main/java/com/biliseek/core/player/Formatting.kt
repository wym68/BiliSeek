package com.biliseek.core.player

/**
 * 播放时长格式化：`mm:ss`，超过一小时用 `h:mm:ss`。
 *
 * 时长未知时 ExoPlayer 返回 [androidx.media3.common.C.TIME_UNSET]（负数），
 * 直接除以 1000 会在界面上显示 `-1:-1`，因此负值统一夹成 0。
 */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
