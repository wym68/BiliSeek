package com.biliseek.app.danmaku

import com.biliseek.core.network.dto.DanmakuItem

/**
 * 弹幕的加载与过滤决策。纯函数。
 */

/** 弹幕接口一段的时长：6 分钟。 */
const val DANMAKU_SEGMENT_MS = 6 * 60 * 1000L

/** 离下一段开始还剩这么久时预取下一段，免得跨段那一刻弹幕断档。 */
const val DANMAKU_PREFETCH_MS = 20_000L

/** 播放位置落在第几段（从 1 起，与接口的 `segment_index` 一致）。 */
fun danmakuSegmentIndex(positionMs: Long): Int =
    (positionMs.coerceAtLeast(0L) / DANMAKU_SEGMENT_MS).toInt() + 1

/** 视频一共几段。时长未知时按 1 段。 */
fun danmakuSegmentCount(durationMs: Long): Int =
    if (durationMs <= 0L) 1 else ((durationMs - 1) / DANMAKU_SEGMENT_MS).toInt() + 1

/**
 * 此刻需要请求的段。
 *
 * 只要当前段，快到段尾时再加下一段——**按需逐段拉，不一次拉满**：
 * 一小时的视频看两分钟就走，没必要替它请求十段。
 * [requested] 里是已加载、在途或已失败的段，一律不再请求（失败不重试，见 CLAUDE.md 风控规则）。
 */
fun resolveDanmakuSegmentsToLoad(
    positionMs: Long,
    durationMs: Long,
    requested: Set<Int>,
): List<Int> {
    val current = danmakuSegmentIndex(positionMs)
    val count = danmakuSegmentCount(durationMs)
    val wanted = buildList {
        add(current)
        val nextStart = current * DANMAKU_SEGMENT_MS
        if (current < count && nextStart - positionMs <= DANMAKU_PREFETCH_MS) add(current + 1)
    }
    return wanted.filter { it !in requested && it <= count }
}

/** 弹幕的显示类别。 */
enum class DanmakuKind { SCROLL, TOP, BOTTOM }

/** 按 mode 归类；高级 / 代码 / BAS 弹幕渲染不了，返回 null。 */
fun danmakuKind(mode: Int): DanmakuKind? = when (mode) {
    1, 2, 3, 6 -> DanmakuKind.SCROLL
    DanmakuItem.MODE_TOP -> DanmakuKind.TOP
    DanmakuItem.MODE_BOTTOM -> DanmakuKind.BOTTOM
    else -> null
}

/**
 * 按设置过滤，并按出现时间排序（渲染层靠二分查找定位，要求有序）。
 *
 * 丢掉：渲染不了的类型、被关掉的类型、彩色（若关）、权重低于屏蔽等级、
 * 特殊池（pool=2，高级弹幕用）、空内容、同 id 重复（跨段边界可能重复下发）。
 */
fun filterDanmaku(items: List<DanmakuItem>, settings: DanmakuSettings): List<DanmakuItem> =
    items.asSequence()
        .filter { it.content.isNotBlank() && it.pool != 2 }
        .filter { it.weight >= settings.minWeight }
        .filter { settings.showColored || it.color == DanmakuItem.DEFAULT_COLOR }
        .filter {
            when (danmakuKind(it.mode)) {
                DanmakuKind.SCROLL -> settings.showScroll
                DanmakuKind.TOP -> settings.showTop
                DanmakuKind.BOTTOM -> settings.showBottom
                null -> false
            }
        }
        .distinctBy { it.id }
        .sortedBy { it.timeMs }
        .toList()

/** 有序列表里第一条 `timeMs >= fromMs` 的下标（没有则为 size）。 */
fun firstDanmakuIndexAtOrAfter(sorted: List<DanmakuItem>, fromMs: Long): Int {
    var low = 0
    var high = sorted.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (sorted[mid].timeMs < fromMs) low = mid + 1 else high = mid
    }
    return low
}
