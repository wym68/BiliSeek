package com.biliseek.app.summary

import com.biliseek.core.ai.AiError
import com.biliseek.core.ai.ConfigErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工单 AI-5 / §3.7.7：失败出路与计量网络决策。 */
class SummaryPoliciesTest {

    private fun actions(
        kind: FailureKind,
        hasTranscript: Boolean = false,
        hasBackup: Boolean = false,
        usingBackup: Boolean = false,
        official: Boolean = false,
    ) = FailureActionPolicy.actions(kind, hasTranscript, hasBackup, usingBackup, official)

    @Test
    fun `配置错误只给去设置 不给切备用`() {
        assertEquals(listOf(FailureAction.OPEN_SETTINGS), actions(FailureKind.CONFIG, hasBackup = true))
        assertEquals(
            listOf(FailureAction.OPEN_SETTINGS, FailureAction.TRANSCRIPT_NOTE),
            actions(FailureKind.CONFIG, hasTranscript = true),
        )
    }

    @Test
    fun `额度用尽 备用优先 其次手动重试 再次原文笔记`() {
        assertEquals(
            listOf(FailureAction.SWITCH_TO_BACKUP, FailureAction.RETRY, FailureAction.TRANSCRIPT_NOTE),
            actions(FailureKind.QUOTA, hasTranscript = true, hasBackup = true),
        )
        // 已经在用备用：不再提供切换
        assertFalse(FailureAction.SWITCH_TO_BACKUP in actions(FailureKind.QUOTA, hasBackup = true, usingBackup = true))
    }

    @Test
    fun `内容审核不给重试`() {
        val a = actions(FailureKind.CONTENT_FILTERED, hasTranscript = true, hasBackup = true)
        assertFalse(FailureAction.RETRY in a)
        assertTrue(FailureAction.SWITCH_TO_BACKUP in a)
        assertTrue(FailureAction.TRANSCRIPT_NOTE in a)
    }

    @Test
    fun `网络与服务端故障 重试优先`() {
        assertEquals(
            listOf(FailureAction.RETRY, FailureAction.SWITCH_TO_BACKUP),
            actions(FailureKind.SERVER, hasBackup = true),
        )
        assertEquals(listOf(FailureAction.RETRY), actions(FailureKind.RATE_LIMITED, hasBackup = true))
    }

    @Test
    fun `官方总结可用时提供`() {
        assertTrue(FailureAction.USE_OFFICIAL in actions(FailureKind.QUOTA, official = true))
    }

    @Test
    fun `没有原文时不提供原文笔记`() {
        assertFalse(FailureAction.TRANSCRIPT_NOTE in actions(FailureKind.SERVER))
    }

    @Test
    fun `错误映射到失败类别`() {
        assertEquals(FailureKind.CONFIG, FailureKind.of(AiError.Config(ConfigErrorKind.INVALID_KEY)))
        assertEquals(FailureKind.QUOTA, FailureKind.of(AiError.QuotaExceeded(1028)))
        assertEquals(FailureKind.STREAM_INTERRUPTED, FailureKind.of(AiError.StreamInterrupted))
        assertEquals(FailureKind.QUOTA, FailureKind.parse("QUOTA"))
        assertEquals(null, FailureKind.parse("nope"))
    }

    @Test
    fun `计量网络只拦需要下载音频的本地识别`() {
        assertEquals(NetworkDecision.Proceed, SummaryNetworkPolicy.decide(needsAudioDownload = false, isMetered = true, durationMs = 3_600_000))
        assertEquals(NetworkDecision.Proceed, SummaryNetworkPolicy.decide(needsAudioDownload = true, isMetered = false, durationMs = 3_600_000))
        val confirm = SummaryNetworkPolicy.decide(needsAudioDownload = true, isMetered = true, durationMs = 3_600_000)
        // 1 小时 × 64 kbps ≈ 28.8 MB
        assertEquals(NetworkDecision.ConfirmMetered(28_800_000), confirm)
    }
}
