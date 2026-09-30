package com.biliseek.app.ui

/** 可拖动底部面板松手后停在哪（纯函数，[ExpandableBottomPanel] 用）。 */
enum class PanelSnap { DISMISS, COLLAPSED, EXPANDED }

object PanelSnapPolicy {
    /** 拖到低于这个高度比例松手就关闭面板。 */
    const val DISMISS_BELOW = 0.42f

    /** 抛动速度阈值（屏幕高度比例 / 秒）：约 1500px/s（2400px 高的屏幕）。 */
    const val FLING = 0.6f

    /**
     * @param fraction 松手时面板高度占屏幕高度的比例
     * @param velocity 松手时的速度，单位「屏幕高度比例 / 秒」，**向上为正**
     * @param collapsed 默认（收起）高度比例，例如 0.6
     */
    fun target(fraction: Float, velocity: Float, collapsed: Float): PanelSnap = when {
        // 明显向上一甩：撑满
        velocity > FLING -> PanelSnap.EXPANDED
        // 明显向下一甩：撑满的先回到收起高度；已经在收起高度或更低就关闭
        velocity < -FLING -> if (fraction > collapsed + 0.05f) PanelSnap.COLLAPSED else PanelSnap.DISMISS
        // 没甩：按位置就近吸附
        fraction < DISMISS_BELOW -> PanelSnap.DISMISS
        fraction < (collapsed + 1f) / 2f -> PanelSnap.COLLAPSED
        else -> PanelSnap.EXPANDED
    }
}
