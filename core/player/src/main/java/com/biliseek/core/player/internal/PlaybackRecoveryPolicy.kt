package com.biliseek.core.player.internal

import androidx.media3.common.PlaybackException

/** 播放中断（播放器报错停在 IDLE）后怎么办。 */
internal enum class PlaybackRecovery {
    /** 作废取流缓存、重新取流，从中断处原位续上（呈现态、播放/暂停都保留）。 */
    REFRESH_STREAM,

    /** 不自动处理：把错误交给界面，由用户点「重试」。 */
    NONE,
}

/** 自动恢复窗口内最多几次。防止一条坏掉的线路把 playurl 打成连发。 */
internal const val MAX_AUTO_RECOVERIES = 2

/** 自动恢复的计数窗口。窗口过了就重新计数：长视频每隔两小时直链过期一次，得都能续上。 */
internal const val AUTO_RECOVERY_WINDOW_MS = 10 * 60_000L

/**
 * 播放中断后要不要自动重新取流。
 *
 * 走到这里时，Media3 已经在 manifest 里的各条备用线路上试过了（见 StreamLoadErrorPolicy）。
 * 剩下的「直链失效 / 线路不通 / 缓冲 10 分钟没进展」换一份新 playurl 多半能好：
 * 直链过期、切网后旧节点不认，新取的链接会按当前网络分配节点。
 * 解码、解析、本地文件缺失这类错误重新取流也没用，直接交给界面。
 *
 * 重新取流本身失败（网络、-352 风控……）就停下报错，**不会**自动再打接口——
 * 那不是播放器错误，不会再回到这里。
 *
 * @param canRefreshStream 当前播放器里的就是队列当前这条在线 B 站内容（本地文件、直链测试流不算）
 * @param recentAttempts 窗口内已经自动恢复过几次（见 [RecoveryBudget]）
 */
internal fun resolvePlaybackRecovery(
    errorCode: Int,
    canRefreshStream: Boolean,
    recentAttempts: Int,
): PlaybackRecovery = when {
    !canRefreshStream || recentAttempts >= MAX_AUTO_RECOVERIES -> PlaybackRecovery.NONE
    errorCode in REFRESHABLE_ERRORS -> PlaybackRecovery.REFRESH_STREAM
    else -> PlaybackRecovery.NONE
}

private val REFRESHABLE_ERRORS = setOf(
    // 403 / 404 / 410：直链过期，或换网后旧节点不认
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    // 设备有网却连不上这个节点
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    // Media3 卡死检测：缓冲 10 分钟没有任何进展
    PlaybackException.ERROR_CODE_TIMEOUT,
)

/** 自动恢复的次数账本：[AUTO_RECOVERY_WINDOW_MS] 内计数，窗口过期自动清零。 */
internal data class RecoveryBudget(
    val windowStartMs: Long = 0L,
    val attempts: Int = 0,
) {
    fun attemptsAt(nowMs: Long): Int = if (expiredAt(nowMs)) 0 else attempts

    fun recordAt(nowMs: Long): RecoveryBudget =
        if (expiredAt(nowMs)) RecoveryBudget(windowStartMs = nowMs, attempts = 1) else copy(attempts = attempts + 1)

    private fun expiredAt(nowMs: Long): Boolean = nowMs - windowStartMs > AUTO_RECOVERY_WINDOW_MS
}
