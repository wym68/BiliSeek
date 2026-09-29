package com.biliseek.core.asr.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ResamplePolicyTest {
    @Test
    fun stereoIsAveragedToMono() {
        val mono = ResamplePolicy.downmix(floatArrayOf(1f, 0f, 0.5f, 0.5f, -1f, 1f), 6, 2)
        assertEquals(listOf(0.5f, 0.5f, 0f), mono.toList())
    }

    @Test
    fun pcm16Scale() {
        val f = ResamplePolicy.pcm16ToFloat(shortArrayOf(0, 16384, -32768))
        assertEquals(listOf(0f, 0.5f, -1f), f.toList())
    }

    @Test
    fun resample48kTo16kLengthAcrossChunks() {
        val r = ResamplePolicy.resampler(48_000)
        var total = 0
        // 1 秒 48k，分成不规则的块
        val sizes = listOf(1000, 4410, 7, 20000, 22583)
        sizes.forEach { total += r.process(FloatArray(it) { 0.25f }).size }
        assertEquals(48_000, sizes.sum())
        assertTrue("got $total", abs(total - 16_000) <= 1)
    }

    @Test
    fun resample44100To16kLength() {
        val r = ResamplePolicy.resampler(44_100)
        var total = 0
        repeat(10) { total += r.process(FloatArray(4410)).size }
        assertTrue("got $total", abs(total - 16_000) <= 1)
    }

    @Test
    fun constantSignalKeepsAmplitudeAndSeamIsContinuous() {
        val r = ResamplePolicy.resampler(44_100)
        val a = r.process(FloatArray(1000) { 0.3f })
        val b = r.process(FloatArray(1000) { 0.3f })
        (a + b).forEach { assertEquals(0.3f, it, 1e-6f) }
    }

    @Test
    fun sineSurvivesDownsampling() {
        // 1 kHz 正弦，48k → 16k 后幅值与频率不变
        val input = FloatArray(48_000) { sin(2 * PI * 1000 * it / 48_000).toFloat() }
        val out = ResamplePolicy.resampler(48_000).process(input)
        for (i in 100 until 200) {
            val expected = sin(2 * PI * 1000 * i / 16_000).toFloat()
            assertEquals(expected, out[i], 0.02f)
        }
    }

    @Test
    fun sameRatePassesThrough() {
        val input = floatArrayOf(0.1f, 0.2f)
        assertEquals(input.toList(), ResamplePolicy.resampler(16_000).process(input).toList())
    }
}
