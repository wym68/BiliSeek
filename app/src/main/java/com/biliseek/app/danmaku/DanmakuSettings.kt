package com.biliseek.app.danmaku

/**
 * 弹幕显示设置。持久化在 SettingsStore，重启保留。
 *
 * 弹幕只在全屏显示（产品决定：听为主，内联不喧宾夺主）。
 */
data class DanmakuSettings(
    val enabled: Boolean = false,
    /** 不透明度 0.2–1。 */
    val opacity: Float = 0.8f,
    /** 字号倍率 0.5–2。 */
    val fontScale: Float = 1.0f,
    /** 显示区域占屏幕高度的比例：0.25 / 0.5 / 0.75 / 1。 */
    val area: Float = 0.5f,
    /** 滚动速度倍率：越大越快。1 = 一条滚动弹幕在屏上停留 [BASE_SCROLL_DURATION_MS]。 */
    val speed: Float = 1.0f,
    /** 屏蔽等级 0–10：权重低于它的弹幕不显示（同官方「屏蔽等级」），越高越清爽。 */
    val minWeight: Int = 3,
    val showScroll: Boolean = true,
    val showTop: Boolean = true,
    val showBottom: Boolean = true,
    /** 显示彩色弹幕。关掉后只留白色。 */
    val showColored: Boolean = true,
) {
    companion object {
        const val BASE_SCROLL_DURATION_MS = 8_000L
        const val FIXED_DURATION_MS = 4_000L
        val AREA_CHOICES = listOf(0.25f, 0.5f, 0.75f, 1.0f)
        val SPEED_CHOICES = listOf(0.75f, 1.0f, 1.5f)
    }
}
