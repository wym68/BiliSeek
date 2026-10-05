package com.biliseek.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiniBarPolicyTest {

    @Test
    fun `每挪 8dp 算一格 不满一格不算`() {
        assertEquals(0, scrubSteps(0f))
        assertEquals(0, scrubSteps(7.9f))
        assertEquals(1, scrubSteps(8f))
        assertEquals(2, scrubSteps(23.9f))
        assertEquals(12, scrubSteps(100f))
    }

    @Test
    fun `往左拖是负格数 同样向零取整`() {
        assertEquals(0, scrubSteps(-7.9f))
        assertEquals(-1, scrubSteps(-8f))
        assertEquals(-3, scrubSteps(-31f))
    }

    @Test
    fun `目标位置从按下时的位置起 每格 5 秒`() {
        assertEquals(30_000L, scrubTarget(startMs = 30_000L, steps = 0, durationMs = 90_000L))
        assertEquals(45_000L, scrubTarget(startMs = 30_000L, steps = 3, durationMs = 90_000L))
        // 不对齐到 5 秒整数倍：02:13 往后一格是 02:18
        assertEquals(138_000L, scrubTarget(startMs = 133_000L, steps = 1, durationMs = 902_000L))
        assertEquals(20_000L, scrubTarget(startMs = 30_000L, steps = -2, durationMs = 90_000L))
    }

    @Test
    fun `目标位置夹在 0 到总长之间`() {
        assertEquals(0L, scrubTarget(startMs = 3_000L, steps = -1, durationMs = 90_000L))
        assertEquals(90_000L, scrubTarget(startMs = 88_000L, steps = 5, durationMs = 90_000L))
        // 总长未知（0）时停在 0
        assertEquals(0L, scrubTarget(startMs = 0L, steps = 4, durationMs = 0L))
    }

    @Test
    fun `竖滑超过 16dp 往上打开播放页 往下收起`() {
        assertEquals(MiniBarSwipe.EXPAND, resolveMiniBarSwipe(-16f))
        assertEquals(MiniBarSwipe.EXPAND, resolveMiniBarSwipe(-40f))
        assertEquals(MiniBarSwipe.COLLAPSE, resolveMiniBarSwipe(16f))
        assertEquals(MiniBarSwipe.COLLAPSE, resolveMiniBarSwipe(40f))
    }

    @Test
    fun `竖滑不到 16dp 不算`() {
        assertNull(resolveMiniBarSwipe(0f))
        assertNull(resolveMiniBarSwipe(15.9f))
        assertNull(resolveMiniBarSwipe(-15.9f))
    }

    // ── 长按放大成刻度尺（设计稿：2026-10-04-minibar-zoom-scrub）──

    @Test
    fun `对齐到最近的 5 秒 夹在 0 到总长`() {
        assertEquals(135_000L, snapToStep(133_000L, durationMs = 902_000L))
        assertEquals(130_000L, snapToStep(132_400L, durationMs = 902_000L))
        assertEquals(135_000L, snapToStep(132_500L, durationMs = 902_000L))
        assertEquals(0L, snapToStep(-3_000L, durationMs = 902_000L))
        // 总长不是 5 秒整数倍：到头也只对齐到总长以内的格
        assertEquals(900_000L, snapToStep(990_000L, durationMs = 902_000L))
        assertEquals(0L, snapToStep(4_000L, durationMs = 0L))
    }

    @Test
    fun `刻度尺代表 3 分钟 视频更短就是整个视频`() {
        assertEquals(180_000L, zoomWindowMs(902_000L))
        assertEquals(120_000L, zoomWindowMs(120_000L))
        assertEquals(0L, zoomWindowMs(0L))
    }

    @Test
    fun `手指横坐标折成进度线上的比例 扣掉左右缩进`() {
        assertEquals(0f, barFraction(xPx = 16f, widthPx = 328f, insetPx = 16f), 0.0001f)
        assertEquals(0.5f, barFraction(xPx = 164f, widthPx = 328f, insetPx = 16f), 0.0001f)
        assertEquals(1f, barFraction(xPx = 312f, widthPx = 328f, insetPx = 16f), 0.0001f)
        assertEquals(0f, barFraction(xPx = 0f, widthPx = 328f, insetPx = 16f), 0.0001f)
        assertEquals(1f, barFraction(xPx = 400f, widthPx = 328f, insetPx = 16f), 0.0001f)
        assertEquals(0f, barFraction(xPx = 10f, widthPx = 20f, insetPx = 16f), 0.0001f)
    }

    @Test
    fun `按住处的时间对齐到 5 秒 刻度尺让它正好在手指下面`() {
        // 15:02 的视频在 62% 处按住：约 09:19 → 09:20
        val press = zoomPressMs(fraction = 0.62f, durationMs = 902_000L)
        assertEquals(560_000L, press)
        val start = zoomStartMs(pressMs = press, pressFraction = 0.62f, windowMs = 180_000L)
        assertEquals(448_400L, start)
        // 手指不动：还是按住处
        assertEquals(560_000L, zoomTargetMs(start, fraction = 0.62f, windowMs = 180_000L, durationMs = 902_000L))
    }

    @Test
    fun `拖到刻度尺某处就选那里 停在 5 秒的格上`() {
        val start = 448_400L
        // 往右挪 33dp（进度线 296dp 宽）：约 4 格 → 09:40
        assertEquals(580_000L, zoomTargetMs(start, fraction = 0.62f + 33f / 296f, windowMs = 180_000L, durationMs = 902_000L))
        // 超出两端就停在两端
        assertEquals(450_000L, zoomTargetMs(start, fraction = -0.3f, windowMs = 180_000L, durationMs = 902_000L))
        assertEquals(630_000L, zoomTargetMs(start, fraction = 1.4f, windowMs = 180_000L, durationMs = 902_000L))
    }

    @Test
    fun `短视频刻度尺就是整个视频 两端是开头和结尾`() {
        val press = zoomPressMs(fraction = 0.5f, durationMs = 120_000L)
        assertEquals(60_000L, press)
        val start = zoomStartMs(press, pressFraction = 0.5f, windowMs = 120_000L)
        assertEquals(0L, zoomTargetMs(start, fraction = 0f, windowMs = 120_000L, durationMs = 120_000L))
        assertEquals(120_000L, zoomTargetMs(start, fraction = 1f, windowMs = 120_000L, durationMs = 120_000L))
    }

    @Test
    fun `刻度每 5 秒一格 30 秒中格 整分钟大格`() {
        val ticks = zoomTicks(startMs = 448_400L, windowMs = 180_000L, durationMs = 902_000L)
        assertEquals(450_000L, ticks.first().ms)
        assertEquals(625_000L, ticks.last().ms)
        assertEquals(36, ticks.size)
        assertEquals(TickLevel.HALF_MINUTE, ticks.first { it.ms == 450_000L }.level)
        assertEquals(TickLevel.STEP, ticks.first { it.ms == 455_000L }.level)
        assertEquals(TickLevel.MINUTE, ticks.first { it.ms == 480_000L }.level)
    }

    @Test
    fun `超出 0 到总长的部分不画格`() {
        val head = zoomTicks(startMs = -20_000L, windowMs = 180_000L, durationMs = 902_000L)
        assertEquals(0L, head.first().ms)
        assertEquals(TickLevel.MINUTE, head.first().level)
        assertEquals(160_000L, head.last().ms)
        val tail = zoomTicks(startMs = 800_000L, windowMs = 180_000L, durationMs = 902_000L)
        assertEquals(900_000L, tail.last().ms)
    }
}
