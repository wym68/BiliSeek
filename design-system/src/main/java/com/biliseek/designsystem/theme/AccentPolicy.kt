package com.biliseek.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 封面取色的素材（提取器产出，策略层消费）。
 *
 * [dominant] 为封面主色；null 表示无封面或取色失败。
 * 提取器在 app 层（要解码 Bitmap），策略层只消费这个值对象——纯函数可单测。
 */
data class CoverPalette(
    val dominant: Color?,
) {
    companion object {
        /** 无封面 / 取色失败的占位。 */
        val Empty = CoverPalette(dominant = null)
    }
}

/**
 * 播放器局部「封面取色」的最终决策。
 *
 * 纯函数产出的值对象，UI 只消费不改写。
 */
data class AccentDecision(
    /** 背景渐变上端（封面氛围更浓的一端）。 */
    val gradientTop: Color,
    /** 背景渐变下端（更接近表面色，文字区所在端）。 */
    val gradientBottom: Color,
    /** 叠在背景上的压暗 scrim，null = 无需。 */
    val scrim: Color? = null,
    /** 点缀色（进度条等），null = 用主题 primary。 */
    val accent: Color? = null,
    /** 封面取色是否生效。false 时 UI 不做事就是正确的默认外观。 */
    val fromCover: Boolean = false,
)

/**
 * 「莫奈取色」策略：封面主色 → 播放器背景与点缀。
 *
 * 与系统莫奈正交：**全局配色仍由系统动态取色**（Android 12+ 壁纸）决定，
 * 本策略只负责播放器**局部**的封面取色（对应《架构设计篇》§8.1 的
 * `AdaptiveAccentColorPolicy` 角色，选型篇 P1「封面取色可用
 * image-theme-colors」的落地）。封面不可用（无封面 / 取色失败）时
 * 回落到 M3 默认 surface——不闪白、不崩。
 *
 * 可读性设计（读码时踩过一遍坑，规则已定死）：
 * - 渐变两端都向表面色收敛（上端 65% 氛围色、下端 45%），文字区因此天然可读——
 *   亮色模式的深字、暗色模式的浅字在收敛后的底色上都成立；
 * - 唯一补强：**暗色模式 + 亮封面**（白字压亮底不可读）时叠一层向表面收敛的
 *   scrim 压暗；其余组合不需要。
 *
 * 全部为纯函数：输入只有颜色与模式开关，无任何环境依赖，配单测。
 */
object AdaptiveAccentColorPolicy {

    /** 渐变上端：封面色比例（氛围端）。 */
    const val GRADIENT_TOP_DOMINANT_RATIO = 0.65f
    /** 渐变下端：封面色比例（文字区端，向表面收敛保可读）。 */
    const val GRADIENT_BOTTOM_DOMINANT_RATIO = 0.45f

    /**
     * 决策播放器背景。
     *
     * @param dominant 封面主色，null = 无封面 / 取色失败
     * @param darkTheme 暗色模式（决定是否要压暗 scrim）
     * @param surface 当前主题的表面色（渐变与 scrim 都向它收敛）
     */
    fun resolve(dominant: Color?, darkTheme: Boolean, surface: Color): AccentDecision {
        val base = dominant ?: return AccentDecision(
            gradientTop = surface,
            gradientBottom = surface,
        )
        return AccentDecision(
            gradientTop = blend(surface, base, GRADIENT_TOP_DOMINANT_RATIO),
            gradientBottom = blend(surface, base, GRADIENT_BOTTOM_DOMINANT_RATIO),
            scrim = ThemeContrastPolicy.scrimFor(base, darkTheme, surface),
            accent = if (
                ThemeContrastPolicy.contrastRatio(base, surface) >= ThemeContrastPolicy.MIN_CONTRAST
            ) {
                base
            } else {
                null
            },
            fromCover = true,
        )
    }

    /** 按比例把 [base] 与 [overlay] 混合（ratio 越大越接近 overlay）。 */
    fun blend(base: Color, overlay: Color, ratio: Float): Color {
        val r = ratio.coerceIn(0f, 1f)
        return Color(
            red = base.red * (1 - r) + overlay.red * r,
            green = base.green * (1 - r) + overlay.green * r,
            blue = base.blue * (1 - r) + overlay.blue * r,
            alpha = 1f,
        )
    }
}

/**
 * 封面背景上的文字可读性判定。
 *
 * 背景已向表面色收敛（见 [AdaptiveAccentColorPolicy]），只有一种组合会漏：
 * **暗色模式 + 亮封面**——白字压亮底。此时叠一层向表面色收敛的半透明 scrim
 * 压暗，把背景推回可读区间。其余组合（亮色模式任意封面、暗色模式暗封面）
 * 由收敛本身保证，不压暗。
 */
object ThemeContrastPolicy {

    /** 对比度门槛：背景带封面氛围色，用 WCAG 一般文本 4.5 的放宽值。 */
    const val MIN_CONTRAST = 3.0

    /** 封面判「亮」的亮度阈值。 */
    const val LUMINANCE_THRESHOLD = 0.5f

    /** 压暗 scrim 的 alpha。 */
    const val SCRIM_ALPHA_DARK = 0.6f

    /**
     * 计算需要的 scrim：仅暗色模式 + 亮封面。
     */
    fun scrimFor(dominant: Color, darkTheme: Boolean, surface: Color): Color? {
        if (!darkTheme) return null
        if (dominant.luminance() <= LUMINANCE_THRESHOLD) return null
        return surface.copy(alpha = SCRIM_ALPHA_DARK)
    }

    /** WCAG 对比度：(L1+0.05)/(L2+0.05)。 */
    fun contrastRatio(a: Color, b: Color): Double {
        val la = a.luminance()
        val lb = b.luminance()
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
