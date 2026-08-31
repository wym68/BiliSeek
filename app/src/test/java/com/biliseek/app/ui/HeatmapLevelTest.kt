package com.biliseek.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 首页热力图色阶（M12）。
 *
 * 与 BBPlayer `cellColor` 4 级色阶对齐：
 * 0 → 底（不涂色）、1 → 0.2、2–3 → 0.4、4–6 → 0.6、7+ → 1.0。
 */
class HeatmapLevelTest {

    @Test
    fun `零与负计数不涂色`() {
        assertEquals(0f, heatmapLevelAlpha(0))
        assertEquals(0f, heatmapLevelAlpha(-3))
    }

    @Test
    fun `一级色阶_单次播放`() {
        assertEquals(0.2f, heatmapLevelAlpha(1))
    }

    @Test
    fun `二级色阶_2到3次`() {
        assertEquals(0.4f, heatmapLevelAlpha(2))
        assertEquals(0.4f, heatmapLevelAlpha(3))
    }

    @Test
    fun `三级色阶_4到6次`() {
        assertEquals(0.6f, heatmapLevelAlpha(4))
        assertEquals(0.6f, heatmapLevelAlpha(6))
    }

    @Test
    fun `满级色阶_7次及以上`() {
        assertEquals(1.0f, heatmapLevelAlpha(7))
        assertEquals(1.0f, heatmapLevelAlpha(100))
    }
}
