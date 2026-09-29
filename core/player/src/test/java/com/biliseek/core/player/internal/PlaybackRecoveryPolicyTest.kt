package com.biliseek.core.player.internal

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放中断后的自动恢复。
 *
 * 两条底线：能换新链接救回来的要自动救（否则就是「卡住了、按了也没反应」）；
 * 次数必须有上限——一条坏线路不能把 playurl 打成连发。
 */
class PlaybackRecoveryPolicyTest {

    @Test
    fun `直链失效、线路不通、卡死超时都重新取流`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT,
        ).forEach { code ->
            assertEquals(
                "errorCode=$code",
                PlaybackRecovery.REFRESH_STREAM,
                resolvePlaybackRecovery(code, canRefreshStream = true, recentAttempts = 0),
            )
        }
    }

    @Test
    fun `解码、解析、本地文件缺失换链接也没用，交给界面`() {
        listOf(
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        ).forEach { code ->
            assertEquals(
                "errorCode=$code",
                PlaybackRecovery.NONE,
                resolvePlaybackRecovery(code, canRefreshStream = true, recentAttempts = 0),
            )
        }
    }

    @Test
    fun `本地文件或播放器里不是当前这条时不自动重取`() {
        assertEquals(
            PlaybackRecovery.NONE,
            resolvePlaybackRecovery(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, canRefreshStream = false, recentAttempts = 0),
        )
    }

    @Test
    fun `窗口内次数用完就停`() {
        val code = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(PlaybackRecovery.REFRESH_STREAM, resolvePlaybackRecovery(code, true, recentAttempts = MAX_AUTO_RECOVERIES - 1))
        assertEquals(PlaybackRecovery.NONE, resolvePlaybackRecovery(code, true, recentAttempts = MAX_AUTO_RECOVERIES))
    }

    @Test
    fun `账本在窗口内累加，窗口过期后重新计数`() {
        var budget = RecoveryBudget()
        budget = budget.recordAt(1_000L)
        assertEquals(1, budget.attemptsAt(1_000L))
        budget = budget.recordAt(2_000L)
        assertEquals(2, budget.attemptsAt(2_000L))

        // 两小时后直链又过期了一次：长视频必须还能续上
        val later = 2_000L + AUTO_RECOVERY_WINDOW_MS + 1
        assertEquals(0, budget.attemptsAt(later))
        budget = budget.recordAt(later)
        assertEquals(1, budget.attemptsAt(later))
    }
}
