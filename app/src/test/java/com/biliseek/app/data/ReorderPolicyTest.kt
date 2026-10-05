package com.biliseek.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 拖拽重排的邻居计算。
 *
 * 后端 `reorder()`（浮点 sortKey 取中值）早就就绪，真正会错的是这里的下标：
 * 算错了不会崩，只会「拖了没反应」。
 */
class ReorderPolicyTest {

    private val keys = listOf(1024.0, 2048.0, 3072.0, 4096.0)

    @Test
    fun `往下拖一格落在两项之间`() {
        // 摘掉 0 后是 [2048, 3072, 4096]，落到下标 1 → 夹在 2048 与 3072 之间
        assertEquals(2048.0 to 3072.0, reorderNeighbors(keys, from = 0, to = 1))
    }

    @Test
    fun `往上拖落在两项之间`() {
        // 摘掉 3 后是 [1024, 2048, 3072]，落到下标 1 → 夹在 1024 与 2048 之间
        assertEquals(1024.0 to 2048.0, reorderNeighbors(keys, from = 3, to = 1))
    }

    @Test
    fun `拖到最前面时前邻为空`() {
        val (before, after) = reorderNeighbors(keys, from = 2, to = 0)
        assertNull(before)
        assertEquals(1024.0, after)
    }

    @Test
    fun `拖到最后面时后邻为空`() {
        val (before, after) = reorderNeighbors(keys, from = 0, to = 3)
        assertEquals(4096.0, before)
        assertNull(after)
    }

    /**
     * 关键用例：邻居必须在**摘掉被拖动项之后**取。
     * 不摘的话往下拖时它会成为自己的前邻，中值仍落回原位——「拖了没反应」。
     */
    @Test
    fun `被拖动项不能成为自己的邻居`() {
        val (before, after) = reorderNeighbors(keys, from = 1, to = 2)
        assertEquals(3072.0, before)
        assertEquals(4096.0, after)
    }

    @Test
    fun `原地不动也不会取到自己`() {
        val (before, after) = reorderNeighbors(keys, from = 1, to = 1)
        assertEquals(1024.0, before)
        assertEquals(3072.0, after)
    }

    @Test
    fun `越界的起点安全返回`() {
        assertEquals(null to null, reorderNeighbors(keys, from = 9, to = 0))
        assertEquals(null to null, reorderNeighbors(emptyList(), from = 0, to = 0))
    }

    @Test
    fun `单元素列表两端都为空`() {
        assertEquals(null to null, reorderNeighbors(listOf(1024.0), from = 0, to = 0))
    }

    // ── 拖动中挪格 ──────────────────────────────────────

    @Test
    fun `列表里挪一项 往下往上 原处不变`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "c", "d", "b"), list.moved(from = 1, to = 3))
        assertEquals(listOf("d", "a", "b", "c"), list.moved(from = 3, to = 0))
        assertEquals(list, list.moved(from = 2, to = 2))
        // 越界不动
        assertEquals(list, list.moved(from = 9, to = 0))
        assertEquals(list, list.moved(from = 0, to = 9))
    }

    /** 行高 73（72 + 1dp 分割线），第 i 行从 73 * i 开始。 */
    private val spans = (0 until 5).map { ItemSpan(index = it, top = 73 * it, size = 73) }

    @Test
    fun `被拖那一行的中线落在哪一行上 那一行就是新落点`() {
        // 第 1 行往下拖了 40：中线 73 + 40 + 36.5 = 149.5，落在第 2 行（146..219）
        assertEquals(2, reorderSwapTarget(draggedIndex = 1, draggedTop = 113f, draggedSize = 73, visible = spans))
        // 往上拖到第 0 行里
        assertEquals(0, reorderSwapTarget(draggedIndex = 1, draggedTop = 30f, draggedSize = 73, visible = spans))
    }

    @Test
    fun `中线还在自己格子里不挪`() {
        // 往下拖了 30：中线 139.5 仍在自己的 73..146
        assertEquals(null, reorderSwapTarget(draggedIndex = 1, draggedTop = 103f, draggedSize = 73, visible = spans))
    }

    @Test
    fun `中线不在任何可见行上不挪`() {
        assertEquals(null, reorderSwapTarget(draggedIndex = 4, draggedTop = 400f, draggedSize = 73, visible = spans))
    }

    // ── 边缘自动滚动 ────────────────────────────────────

    @Test
    fun `离边缘远时不滚`() {
        assertEquals(0f, autoScrollSpeed(draggedTop = 200f, draggedBottom = 273f, viewportTop = 0f, viewportBottom = 600f, edgePx = 48f, maxSpeedPx = 800f), 0.001f)
    }

    @Test
    fun `进了上边缘往上滚 越靠边越快 最多到上限`() {
        assertEquals(-400f, autoScrollSpeed(draggedTop = 24f, draggedBottom = 97f, viewportTop = 0f, viewportBottom = 600f, edgePx = 48f, maxSpeedPx = 800f), 0.001f)
        assertEquals(-800f, autoScrollSpeed(draggedTop = -30f, draggedBottom = 43f, viewportTop = 0f, viewportBottom = 600f, edgePx = 48f, maxSpeedPx = 800f), 0.001f)
    }

    @Test
    fun `进了下边缘往下滚`() {
        assertEquals(200f, autoScrollSpeed(draggedTop = 492f, draggedBottom = 564f, viewportTop = 0f, viewportBottom = 600f, edgePx = 48f, maxSpeedPx = 800f), 0.001f)
        assertEquals(800f, autoScrollSpeed(draggedTop = 560f, draggedBottom = 633f, viewportTop = 0f, viewportBottom = 600f, edgePx = 48f, maxSpeedPx = 800f), 0.001f)
    }
}
