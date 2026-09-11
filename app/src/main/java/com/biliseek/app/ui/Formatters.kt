package com.biliseek.app.ui

import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * 格式化播放数量。
 *
 * - 超过 10000：以万为单位，四舍五入保留一位小数
 * - 超过 100000000：以亿为单位，四舍五入保留一位小数
 * - 其他：直接显示数字
 */
fun formatPlayCount(count: Long): String = when {
    count >= 100_000_000 -> "%.1f 亿".format(count / 100_000_000.0)
    count >= 10_000 -> "%.1f 万".format(count / 10_000.0)
    else -> count.toString()
}

/**
 * 格式化视频发布时间。
 *
 * - 当天发布：显示时间（如 "14:30"）
 * - 一周以内：显示 "X天前"
 * - 当年发布：显示月日（如 "3月15日"）
 * - 更早：显示年月日（如 "2024年3月15日"）
 */
fun formatPubDate(timestamp: Long): String {
    if (timestamp <= 0) return ""

    val now = System.currentTimeMillis()
    val pubDate = Date(timestamp * 1000)
    val nowCal = Calendar.getInstance()
    val pubCal = Calendar.getInstance().apply { time = pubDate }

    // 判断是否是同一天
    val isSameDay = nowCal.get(Calendar.YEAR) == pubCal.get(Calendar.YEAR) &&
        nowCal.get(Calendar.DAY_OF_YEAR) == pubCal.get(Calendar.DAY_OF_YEAR)

    if (isSameDay) {
        // 当天发布：显示时间
        return "%02d:%02d".format(
            pubCal.get(Calendar.HOUR_OF_DAY),
            pubCal.get(Calendar.MINUTE),
        )
    }

    // 计算天数差
    val diffMillis = now - timestamp * 1000
    val diffDays = TimeUnit.MILLISECONDS.toDays(diffMillis)

    return when {
        diffDays < 7 -> "${diffDays}天前"
        nowCal.get(Calendar.YEAR) == pubCal.get(Calendar.YEAR) -> {
            // 当年发布
            "${pubCal.get(Calendar.MONTH) + 1}月${pubCal.get(Calendar.DAY_OF_MONTH)}日"
        }
        else -> {
            // 更早
            "${pubCal.get(Calendar.YEAR)}年${pubCal.get(Calendar.MONTH) + 1}月${pubCal.get(Calendar.DAY_OF_MONTH)}日"
        }
    }
}
