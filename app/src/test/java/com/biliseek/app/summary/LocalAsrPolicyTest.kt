package com.biliseek.app.summary

import com.biliseek.core.ai.Chapter
import com.biliseek.core.ai.Transcript
import com.biliseek.core.asr.AsrError
import com.biliseek.core.asr.AsrSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAsrPolicyTest {
    @Test
    fun shortVideoNoPrompt() {
        assertEquals(AsrPrompt.NoPrompt, LocalAsrPolicy.decide(30 * 60_000L, null))
    }

    @Test
    fun longVideoUsesDefaultRtfPlusOverhead() {
        // 60 分钟 × 0.1 × 1.2 = 7.2 → 8 分钟
        assertEquals(AsrPrompt.ConfirmLong(8), LocalAsrPolicy.decide(60 * 60_000L, null))
    }

    @Test
    fun measuredRtfWins() {
        // 实测已含解码与 VAD：60 分钟 × 0.05 = 3 分钟
        assertEquals(AsrPrompt.ConfirmLong(3), LocalAsrPolicy.decide(60 * 60_000L, 0.05f))
        // 非法实测值回退默认
        assertEquals(8, LocalAsrPolicy.estimateMinutes(60 * 60_000L, 0f))
    }

    @Test
    fun estimateAtLeastOneMinute() {
        assertEquals(1, LocalAsrPolicy.estimateMinutes(10_000L, 0.01f))
    }

    @Test
    fun pickLocalAudioPrefersAudioFile() {
        assertEquals("file:///a.m4a", LocalAsrPolicy.pickLocalAudio("file:///a.m4a", "file:///v.mp4"))
        assertEquals("content://v", LocalAsrPolicy.pickLocalAudio("", "content://v"))
        assertNull(LocalAsrPolicy.pickLocalAudio("", ""))
    }

    @Test
    fun toTranscriptDropsBlankAndPicksMajorityLanguage() {
        val chapters = listOf(Chapter(0, 60_000, "开场"))
        val t = LocalAsrPolicy.toTranscript(
            listOf(
                AsrSegment(0, 1_000, "你好", "zh"),
                AsrSegment(1_000, 2_000, "", null),
                AsrSegment(2_000, 3_000, "hello", "en"),
                AsrSegment(3_000, 4_000, "再见", "zh"),
            ),
            chapters,
        )
        assertEquals(listOf("你好", "hello", "再见"), t.segments.map { it.text })
        assertEquals("zh", t.language)
        assertEquals(chapters, t.chapters)
        assertEquals(Transcript.Timing.SEGMENT, t.timing)
    }

    @Test
    fun errorsAreDescribed() {
        assertTrue(LocalAsrPolicy.describe(AsrError.ModelNotReady).contains("下载或导入"))
        assertTrue(LocalAsrPolicy.describe(AsrError.DecodeFailed("坏文件")).contains("坏文件"))
        assertTrue(LocalAsrPolicy.describe(AsrError.OutOfMemory).contains("内存"))
    }
}
