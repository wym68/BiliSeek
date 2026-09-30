package com.biliseek.core.asr.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadSegmentPolicyTest {
    private val rate = 16_000

    @Test
    fun sampleToMsAddsResumeOffset() {
        assertEquals(1_500L, VadSegmentPolicy.toMs(24_000, rate, 0))
        assertEquals(61_500L, VadSegmentPolicy.toMs(24_000, rate, 60_000))
    }

    @Test
    fun dropsTooShort() {
        assertFalse(VadSegmentPolicy.shouldKeep(3_999, rate))
        assertTrue(VadSegmentPolicy.shouldKeep(4_000, rate))
    }

    @Test
    fun normalSegmentIsNotSplit() {
        assertEquals(listOf(0 until 16_000 * 20), VadSegmentPolicy.split(16_000 * 20, rate))
        assertEquals(listOf(0 until 16_000 * 30), VadSegmentPolicy.split(16_000 * 30, rate))
    }

    @Test
    fun overlongSegmentIsSplitEvenlyAndCoversAll() {
        val n = 16_000 * 31
        val parts = VadSegmentPolicy.split(n, rate)
        assertEquals(2, parts.size)
        assertEquals(0, parts.first().first)
        assertEquals(n - 1, parts.last().last)
        assertEquals(parts[0].last + 1, parts[1].first)
        assertTrue(parts.all { it.count() <= 16_000 * 30 })
        // 等长切，不留一小截
        assertTrue(parts.all { it.count() >= 16_000 * 15 })
    }

    @Test
    fun emptyGivesNothing() {
        assertEquals(emptyList<IntRange>(), VadSegmentPolicy.split(0, rate))
    }
}
