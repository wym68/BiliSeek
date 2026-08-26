package com.biliseek.app.data

/**
 * 拖拽落点两侧的 `sortKey` —— 纯函数，可单测。
 *
 * [LibraryRepository.reorder] 用「取两侧中值」写入新键，只改一行。
 * 难点全在这两个邻居怎么找：**必须先把被拖动项从列表里摘掉再取邻居**。
 * 不摘的话，往下拖时被拖动项自己会成为「前一项」，算出来的中值仍落在原位，
 * 表现为「拖了没反应」。
 *
 * 返回 `(before, after)`，任一端为 `null` 表示落在首/尾。
 */
fun reorderNeighbors(sortKeys: List<Double>, from: Int, to: Int): Pair<Double?, Double?> {
    if (from !in sortKeys.indices) return null to null
    val without = sortKeys.filterIndexed { index, _ -> index != from }
    val target = to.coerceIn(0, without.size)
    return without.getOrNull(target - 1) to without.getOrNull(target)
}

/** 按拖动位移换算落点下标。[deltaSlots] 为位移除以行高后的四舍五入值。 */
fun reorderTargetIndex(from: Int, deltaSlots: Int, size: Int): Int =
    (from + deltaSlots).coerceIn(0, (size - 1).coerceAtLeast(0))
