package com.biliseek.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 莫奈取色策略层单测。
 *
 * 覆盖《M12 计划》A-1 的全部分支：无封面 / 封面过亮 / 过暗 /
 * 非 12+ 无系统莫奈（封面取色照常生效）/ 暗色模式。
 */
class AccentPolicyTest {

    // ── 无封面 / 取色失败：回落 M3 默认表面 ─────────────

    @Test
    fun `无封面时回落默认表面色`() {
        val surface = Color(0xFFFEF7FF) // M3 亮色表面
        val decision = AdaptiveAccentColorPolicy.resolve(null, darkTheme = false, surface = surface)

        assertFalse(decision.fromCover)
        assertEquals(surface, decision.gradientTop)
        assertEquals(surface, decision.gradientBottom)
        assertNull(decision.scrim)
        assertNull(decision.accent)
    }

    // ── 封面可用：渐变端点与点缀 ────────────────────────────

    @Test
    fun `封面可用时两端向表面收敛且下端更接近表面`() {
        val cover = Color(0xFF3B82F6) // 蓝色
        val surface = Color(0xFFFEF7FF)
        val decision = AdaptiveAccentColorPolicy.resolve(cover, darkTheme = false, surface = surface)

        assertTrue(decision.fromCover)
        // 两端都不是纯封面色、也不是纯表面色
        assertTrue(decision.gradientTop != cover && decision.gradientTop != surface)
        assertTrue(decision.gradientBottom != cover && decision.gradientBottom != surface)
        // 下端更接近表面（收敛更多），上端氛围更浓
        val topToSurface = kotlin.math.abs(decision.gradientTop.luminance() - surface.luminance())
        val bottomToSurface = kotlin.math.abs(decision.gradientBottom.luminance() - surface.luminance())
        assertTrue("下端应更接近表面色", bottomToSurface < topToSurface)
    }

    @Test
    fun `blend 端点行为`() {
        val a = Color(0xFFFF0000)
        val b = Color(0xFF0000FF)
        assertEquals(a, AdaptiveAccentColorPolicy.blend(a, b, 0f))
        assertEquals(b, AdaptiveAccentColorPolicy.blend(a, b, 1f))
        // 比例越界要夹回
        assertEquals(a, AdaptiveAccentColorPolicy.blend(a, b, -1f))
        assertEquals(b, AdaptiveAccentColorPolicy.blend(a, b, 2f))
    }

    // ── scrim：仅「暗色模式 + 亮封面」压暗 ──────────────────

    @Test
    fun `亮封面配暗色模式时压暗`() {
        // 暗色模式表面近黑，亮黄封面 + 白字不可读 → 需要压暗 scrim
        val cover = Color(0xFFFFEB3B)
        val surface = Color(0xFF141218)
        val scrim = ThemeContrastPolicy.scrimFor(cover, darkTheme = true, surface = surface)
        assertNotNull(scrim)
        assertEquals(ThemeContrastPolicy.SCRIM_ALPHA_DARK, scrim!!.alpha, 0.001f)
    }

    @Test
    fun `暗封面配暗色模式时不压暗`() {
        // 暗色模式 + 暗封面：白字天然可读
        val cover = Color(0xFF1A237E)
        val surface = Color(0xFF141218)
        assertNull(ThemeContrastPolicy.scrimFor(cover, darkTheme = true, surface = surface))
    }

    @Test
    fun `亮色模式任意封面都不压暗`() {
        // 亮色模式黑字在收敛后的底色上始终可读——不需要 scrim
        val bright = Color(0xFFFFEB3B)
        val dark = Color(0xFF1A237E)
        val surface = Color(0xFFFEF7FF)
        assertNull(ThemeContrastPolicy.scrimFor(bright, darkTheme = false, surface = surface))
        assertNull(ThemeContrastPolicy.scrimFor(dark, darkTheme = false, surface = surface))
    }

    @Test
    fun `临界亮度边界`() {
        val surface = Color(0xFF141218)
        // 亮度恰在阈值上 → 不压暗（放宽阈值，氛围优先）
        val gray = Color(0xFF808080)
        assertNull(ThemeContrastPolicy.scrimFor(gray, darkTheme = true, surface = surface))
    }

    // ── 对比度公式 ────────────────────────────────────────

    @Test
    fun `对比度公式对称且恒不小于 1`() {
        val a = Color(0xFF000000)
        val b = Color(0xFFFFFFFF)
        val ab = ThemeContrastPolicy.contrastRatio(a, b)
        val ba = ThemeContrastPolicy.contrastRatio(b, a)
        assertEquals(ab, ba, 0.0001)
        assertTrue(ab >= 1.0)
        assertTrue(ab > 10.0) // 黑白理论 21:1
    }

    // ── 点缀色（accent）── 只在可与表面区分时给出 ───────────

    @Test
    fun `点缀色在对比不足时回退为 null`() {
        // 封面与表面几乎同色 → accent 无意义
        val cover = Color(0xFFFEF7FE)
        val surface = Color(0xFFFEF7FF)
        val decision = AdaptiveAccentColorPolicy.resolve(cover, darkTheme = false, surface = surface)
        assertNull(decision.accent)
    }

    @Test
    fun `点缀色在对比足够时给出封面主色`() {
        val cover = Color(0xFF1A237E)
        val surface = Color(0xFFFEF7FF)
        val decision = AdaptiveAccentColorPolicy.resolve(cover, darkTheme = false, surface = surface)
        assertEquals(cover, decision.accent)
    }

    // ── 非 12+ 无系统莫奈：封面取色照常生效 ────────────────

    @Test
    fun `无系统莫奈时封面取色仍生效`() {
        // 策略层不读 SDK 版本：它只消费「封面可用性 + 模式」，
        // 系统莫奈缺失时（<12）封面取色照常出决策——这就是低版本的价值点
        val cover = Color(0xFF7C4DFF)
        val surface = Color(0xFFFEF7FF)
        val decision = AdaptiveAccentColorPolicy.resolve(cover, darkTheme = false, surface = surface)
        assertTrue(decision.fromCover)
    }

    // ── 暗色模式的决策输出 ─────────────────────────────────

    @Test
    fun `暗色模式亮封面时压暗 scrim 随决策输出`() {
        val cover = Color(0xFFFFC107) // 琥珀，亮
        val surface = Color(0xFF141218)
        val decision = AdaptiveAccentColorPolicy.resolve(cover, darkTheme = true, surface = surface)
        assertTrue(decision.fromCover)
        assertNotNull("暗色模式 + 亮封面必须给 scrim", decision.scrim)
        // 压暗后下端应比未压暗的封面主色暗
        val scrimmed = AdaptiveAccentColorPolicy.blend(decision.gradientBottom, decision.scrim!!, decision.scrim!!.alpha)
        assertTrue(scrimmed.luminance() < decision.gradientBottom.luminance())
    }
}
