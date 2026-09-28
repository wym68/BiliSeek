package com.biliseek.designsystem.component

/**
 * 互动数字的紧凑写法（点赞 / 投币 / 收藏 / 评论数）。
 *
 * - `< 1 万`：原样（`9999`）
 * - `< 1 亿`：`1.2万`，整数时去掉 `.0`（`3万`）
 * - 其余：`1.2亿`
 * - 负数按 0 处理（乐观更新回滚时可能短暂出现 -1）
 *
 * 纯函数，与 locale 无关（固定用 `.` 作小数点），配单测。
 */
fun formatCompactCount(count: Long): String {
    val n = count.coerceAtLeast(0)
    return when {
        n >= 100_000_000 -> oneDecimal(n, 100_000_000) + "亿"
        n >= 10_000 -> oneDecimal(n, 10_000) + "万"
        else -> n.toString()
    }
}

fun formatCompactCount(count: Int): String = formatCompactCount(count.toLong())

/** 保留一位小数（截断而非四舍五入：9.99 万显示 9.9万，不会进位成 10.0万）。 */
private fun oneDecimal(n: Long, unit: Long): String {
    val tenths = n * 10 / unit
    val whole = tenths / 10
    val frac = tenths % 10
    return if (frac == 0L) "$whole" else "$whole.$frac"
}
