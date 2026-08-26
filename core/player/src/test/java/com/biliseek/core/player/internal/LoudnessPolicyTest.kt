package com.biliseek.core.player.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 响度均衡的增益。
 *
 * 最要紧的两条不是算得准不准，而是：**没数据时不要乱动**、
 * **算出的增益不能超过 1.0**——`Player.setVolume` 夹不住的话，
 * 表现就是「开了开关毫无变化」。
 */
class LoudnessPolicyTest {

    @Test
    fun `没有响度数据时不做任何处理`() {
        assertEquals(1.0f, loudnessGain(null, null), 0.0001f)
        assertEquals(1.0f, loudnessGain(0.0, -18.0), 0.0001f)
    }

    @Test
    fun `比目标响的投稿被压下来`() {
        // 实测 -12，目标 -18：差 6 dB，线性约 0.501
        assertEquals(0.501f, loudnessGain(-12.0, -18.0), 0.005f)
    }

    @Test
    fun `比目标轻的投稿不被抬高`() {
        // setVolume 上限是 1.0，抬不了。语义是「压下过响的」而非「抬起过轻的」
        assertEquals(1.0f, loudnessGain(-24.0, -18.0), 0.0001f)
    }

    @Test
    fun `目标响度无效时回退默认值`() {
        // target_i 为 0 或正数都不是合法的 LUFS 目标
        assertEquals(loudnessGain(-12.0, DEFAULT_TARGET_LOUDNESS), loudnessGain(-12.0, 0.0), 0.0001f)
        assertEquals(loudnessGain(-12.0, DEFAULT_TARGET_LOUDNESS), loudnessGain(-12.0, null), 0.0001f)
    }

    @Test
    fun `极端数据也不会压到听不见`() {
        val gain = loudnessGain(measuredI = 40.0, targetI = -18.0)
        assertTrue("增益 $gain 应留在可听范围", gain >= 0.1f)
    }

    @Test
    fun `结果始终落在开区间零到一之间`() {
        val samples = listOf(-40.0, -23.0, -18.0, -14.0, -6.0, -1.0)
        for (measured in samples) {
            val gain = loudnessGain(measured, -18.0)
            assertTrue("$measured → $gain", gain > 0f && gain <= 1.0f)
        }
    }
}
