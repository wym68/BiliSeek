package com.biliseek.app.ui

/** 相对时间显示（收藏夹同步时间 / 动态发布时间共用）。 */
fun formatRelativeTime(ts: Long): String {
    if (ts <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        else -> "${diff / 86_400_000} 天前"
    }
}
