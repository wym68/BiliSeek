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

    // ── 位移换算 ──────────────────────────────────────────

    @Test
    fun `位移换算成落点下标`() {
        assertEquals(3, reorderTargetIndex(from = 1, deltaSlots = 2, size = 4))
        assertEquals(0, reorderTargetIndex(from = 2, deltaSlots = -2, size = 4))
    }

    /** 拖出列表范围要夹住，不能算出负下标或越界。 */
    @Test
    fun `落点夹在列表范围内`() {
        assertEquals(0, reorderTargetIndex(from = 1, deltaSlots = -99, size = 4))
        assertEquals(3, reorderTargetIndex(from = 1, deltaSlots = 99, size = 4))
        assertEquals(0, reorderTargetIndex(from = 0, deltaSlots = 5, size = 0))
    }
}
