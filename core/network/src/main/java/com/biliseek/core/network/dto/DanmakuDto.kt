package com.biliseek.core.network.dto

/**
 * 一条弹幕。来自 `x/v2/dm/wbi/web/seg.so` 的 protobuf（`DanmakuElem`），
 * 只保留渲染与过滤要用的字段。
 */
data class DanmakuItem(
    val id: Long,
    /** 出现时间，视频内毫秒。 */
    val timeMs: Long,
    /** 1/2/3 滚动，4 底部，5 顶部，6 逆向，7 高级，8 代码，9 BAS。 */
    val mode: Int,
    /** 字号，25 为标准，18 小号，36 大号。 */
    val fontSize: Int,
    /** RGB 颜色（不含 alpha），白色为 0xFFFFFF。 */
    val color: Int,
    val content: String,
    /** 弹幕权重 0–10。官方「屏蔽等级」按它过滤，越低越可能是刷屏。 */
    val weight: Int,
    /** 弹幕池：0 普通，1 字幕，2 特殊。 */
    val pool: Int,
) {
    companion object {
        const val MODE_BOTTOM = 4
        const val MODE_TOP = 5
        const val DEFAULT_COLOR = 0xFFFFFF
    }
}
