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

/** 把 [from] 处的一项挪到 [to]（先摘下再插入），越界时原样返回。拖动中本地顺序每越过一行挪一格。 */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    val out = toMutableList()
    out.add(to, out.removeAt(from))
    return out
}

/** 列表里一行实际排在哪：第 [index] 行，离可见区顶 [top] px，高 [size] px（含分割线）。 */
data class ItemSpan(val index: Int, val top: Int, val size: Int)

/**
 * 被拖那一行（第 [draggedIndex] 行，现在顶在 [draggedTop]、高 [draggedSize]）的中线落在哪一行上：
 * 那一行就是新的落点；还在自己格子里、或不在任何可见行上返回 null。几何来自实测，不按固定行高推。
 */
fun reorderSwapTarget(draggedIndex: Int, draggedTop: Float, draggedSize: Int, visible: List<ItemSpan>): Int? {
    val middle = draggedTop + draggedSize / 2f
    return visible.firstOrNull { it.index != draggedIndex && middle >= it.top && middle < it.top + it.size }?.index
}

/**
 * 拖到列表上下边缘时的自动滚动速度（px/秒，往上为负）：被拖那一行进了离边缘 [edgePx] 以内的区域才滚，
 * 越靠边越快，最快 [maxSpeedPx]。
 */
fun autoScrollSpeed(
    draggedTop: Float,
    draggedBottom: Float,
    viewportTop: Float,
    viewportBottom: Float,
    edgePx: Float,
    maxSpeedPx: Float,
): Float {
    val topDepth = viewportTop + edgePx - draggedTop
    val bottomDepth = draggedBottom - (viewportBottom - edgePx)
    return when {
        topDepth > 0f -> -maxSpeedPx * (topDepth / edgePx).coerceAtMost(1f)
        bottomDepth > 0f -> maxSpeedPx * (bottomDepth / edgePx).coerceAtMost(1f)
        else -> 0f
    }
}
