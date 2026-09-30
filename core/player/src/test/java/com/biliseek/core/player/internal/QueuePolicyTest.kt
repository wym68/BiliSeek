package com.biliseek.core.player.internal

import com.biliseek.core.player.PlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuePolicyTest {

    // ── 「下一首播放」的插入 ────────────────────────────

    @Test
    fun `插在当前项之后`() {
        assertEquals(1, resolveEnqueueNextIndex(current = 0, size = 3))
        assertEquals(3, resolveEnqueueNextIndex(current = 2, size = 5))
    }

    @Test
    fun `当前是最后一项时追加到末尾`() {
        assertEquals(3, resolveEnqueueNextIndex(current = 2, size = 3))
    }

    @Test
    fun `还没起播时追加到末尾`() {
        assertEquals(0, resolveEnqueueNextIndex(current = -1, size = 0))
        assertEquals(4, resolveEnqueueNextIndex(current = -1, size = 4))
    }

    /**
     * 忘了修正下标，「下一首播放」就会表现为「插完之后正在播的歌变了」。
     * 插在当前项之后时下标不动；插在之前或同位时要顺延一格。
     */
    @Test
    fun `插入后修正当前下标`() {
        assertEquals(2, resolveIndexAfterInsert(current = 2, insertAt = 3))
        assertEquals(3, resolveIndexAfterInsert(current = 2, insertAt = 2))
        assertEquals(3, resolveIndexAfterInsert(current = 2, insertAt = 0))
        assertEquals(-1, resolveIndexAfterInsert(current = -1, insertAt = 0))
    }

    @Test
    fun `下一首播放不会顶掉正在播的那一首`() {
        val current = 1
        val at = resolveEnqueueNextIndex(current, size = 4)
        assertEquals(current, resolveIndexAfterInsert(current, at))
    }


    @Test
    fun `顺序播放到末尾即停止`() {
        assertEquals(1, resolveNextIndex(0, 3, PlayMode.SEQUENTIAL))
        assertEquals(2, resolveNextIndex(1, 3, PlayMode.SEQUENTIAL))
        assertNull("末尾应停止而非回头", resolveNextIndex(2, 3, PlayMode.SEQUENTIAL))
    }

    @Test
    fun `列表循环回到开头`() {
        assertEquals(0, resolveNextIndex(2, 3, PlayMode.REPEAT_ALL))
    }

    @Test
    fun `单曲循环原地重播`() {
        assertEquals(1, resolveNextIndex(1, 3, PlayMode.REPEAT_ONE))
    }

    @Test
    fun `随机不会选中当前这首`() {
        repeat(20) {
            val next = resolveNextIndex(1, 5, PlayMode.SHUFFLE)
            assertNotEquals("随机推进不应原地重复", 1, next)
        }
    }

    @Test
    fun `随机在单元素队列里退化为原地`() {
        assertEquals(0, resolveNextIndex(0, 1, PlayMode.SHUFFLE))
    }

    @Test
    fun `手动下一首在单曲循环下仍换歌`() {
        // 自动推进原地重播，但用户手动点「下一首」必须换歌，否则像卡住了
        assertEquals(1, resolveNextIndex(1, 3, PlayMode.REPEAT_ONE))
        assertEquals(2, resolveManualNextIndex(1, 3, PlayMode.REPEAT_ONE))
    }

    @Test
    fun `手动上一首在顺序模式下到头即无`() {
        assertNull(resolvePreviousIndex(0, 3, PlayMode.SEQUENTIAL))
        assertEquals(0, resolvePreviousIndex(1, 3, PlayMode.SEQUENTIAL))
    }

    @Test
    fun `手动上一首在循环模式下绕回末尾`() {
        assertEquals(2, resolvePreviousIndex(0, 3, PlayMode.REPEAT_ALL))
    }

    @Test
    fun `空队列一律返回 null`() {
        assertNull(resolveNextIndex(0, 0, PlayMode.REPEAT_ALL))
        assertNull(resolvePreviousIndex(0, 0, PlayMode.REPEAT_ALL))
        assertNull(resolveManualNextIndex(0, 0, PlayMode.REPEAT_ALL))
    }

    // ── 清空队列（Ch5 §5.1.3 / §5.8）：保留当前播放，清其余；queueIndex 归 0 ──

    @Test
    fun `清空队列保留当前播放并 queueIndex 归 0`() {
        val (newSize, newIndex) = resolveClearedQueue(currentIndex = 2, size = 5)
        assertEquals(1, newSize)
        assertEquals(0, newIndex)
    }

    @Test
    fun `单首队列清空退化为只保留自身`() {
        val (newSize, newIndex) = resolveClearedQueue(currentIndex = 0, size = 1)
        assertEquals(1, newSize)
        assertEquals(0, newIndex)
    }

    @Test
    fun `空队列或无效当前下标时全部清空`() {
        // size=0：无可保留项，全清
        assertEquals(0 to -1, resolveClearedQueue(currentIndex = -1, size = 0))
        // 越界（currentIndex >= size）：视为未在播，全部清空
        assertEquals(0 to -1, resolveClearedQueue(currentIndex = 5, size = 3))
    }

    // ── 两层队列：点击播放（docs/10-播放队列工单.md）──────────
    // 记法：P = 固定（用户显式加入），T = 临时（点击带进来的）

    private val P = true
    private val T = false

    /** 按方案拼出新队列，方便直接比对结果顺序。新块条目记为 "N0"、"N1"…… */
    private fun apply(names: List<String>, pinned: List<Boolean>, current: Int, newCount: Int, start: Int = 0): Pair<List<String>, Int> {
        val plan = resolveQueueOnTap(pinned, current, newCount, start)
        val kept = plan.kept.map { names[it] }
        val block = (0 until newCount).map { "N$it" }
        return (kept.take(plan.insertAt) + block + kept.drop(plan.insertAt)) to plan.newIndex
    }

    @Test
    fun `空队列点击，新块就是整个队列`() {
        assertEquals(listOf("N0") to 0, apply(emptyList(), emptyList(), current = -1, newCount = 1))
    }

    @Test
    fun `当前是临时条目：被替换，后面的固定条目保留`() {
        // [A 临时，正在播][B 固定][C 固定] + 点 X → [X][B][C]
        val result = apply(listOf("A", "B", "C"), listOf(T, P, P), current = 0, newCount = 1)
        assertEquals(listOf("N0", "B", "C") to 0, result)
    }

    @Test
    fun `当前是固定条目：保留在新块之前`() {
        // [A 固定，正在播][B 固定] + 点 X → [A][X][B]
        val result = apply(listOf("A", "B"), listOf(P, P), current = 0, newCount = 1)
        assertEquals(listOf("A", "N0", "B") to 1, result)
    }

    @Test
    fun `当前项前后都有固定条目，旧临时块整块被换掉`() {
        // [P1][t1][t2 正在播][t3][P2] + 点一张 3 项的列表、从第 2 项开始
        val result = apply(
            listOf("P1", "t1", "t2", "t3", "P2"),
            listOf(P, T, T, T, P),
            current = 2,
            newCount = 3,
            start = 1,
        )
        assertEquals(listOf("P1", "N0", "N1", "N2", "P2") to 2, result)
    }

    @Test
    fun `固定条目排在整个新列表之后`() {
        val result = apply(listOf("A", "B", "C"), listOf(T, P, P), current = 0, newCount = 4, start = 1)
        assertEquals(listOf("N0", "N1", "N2", "N3", "B", "C") to 1, result)
    }

    @Test
    fun `还没起播时新块插在最前，已排着的固定条目等它播完`() {
        val result = apply(listOf("B", "C"), listOf(P, P), current = -1, newCount = 1)
        assertEquals(listOf("N0", "B", "C") to 0, result)
    }

    @Test
    fun `全部是临时条目时整队替换`() {
        val result = apply(listOf("a", "b", "c"), listOf(T, T, T), current = 1, newCount = 2, start = 1)
        assertEquals(listOf("N0", "N1") to 1, result)
    }

    @Test
    fun `起播偏移越界时收进新块范围`() {
        assertEquals(1, resolveQueueOnTap(emptyList(), -1, newCount = 2, startOffset = 9).newIndex)
        assertEquals(0, resolveQueueOnTap(emptyList(), -1, newCount = 2, startOffset = -3).newIndex)
    }

    @Test
    fun `替换掉的临时条目数`() {
        assertEquals(3, resolveQueueOnTap(listOf(P, T, T, T, P), 2, 1, 0).removedTemporary)
        assertEquals(0, resolveQueueOnTap(listOf(P, P), 0, 1, 0).removedTemporary)
    }

    @Test
    fun `同一列表里换一条播放什么也没丢`() {
        val list = listOf("BV1", "BV2", "BV3", "BV4")
        assertEquals(0, countLostOnTap(removed = list, incoming = list))
    }

    @Test
    fun `旧块分 P 已展开也算同一列表`() {
        // BV2 展开成了三个分 P，同一视频的三项都能在新块里找到
        val removed = listOf("BV1", "BV2", "BV2", "BV2", "BV3")
        assertEquals(0, countLostOnTap(removed, incoming = listOf("BV1", "BV2", "BV3")))
    }

    @Test
    fun `列表换成单个视频，丢掉的是列表里其余条目`() {
        assertEquals(3, countLostOnTap(listOf("BV1", "BV2", "BV3"), incoming = listOf("BVx")))
        // 点的正好是列表里的一条：只丢其余两条
        assertEquals(2, countLostOnTap(listOf("BV1", "BV2", "BV3"), incoming = listOf("BV2")))
    }

    @Test
    fun `续过页的条目在新块里没有时算丢失`() {
        val removed = listOf("BV1", "BV2", "BV3", "BV4")   // 后两条是续页拿回来的
        assertEquals(2, countLostOnTap(removed, incoming = listOf("BV1", "BV2")))
    }

    @Test
    fun `没有临时条目时什么也不丢`() {
        assertEquals(0, countLostOnTap(emptyList(), incoming = listOf("BV1")))
    }

    @Test
    fun `只替换掉一个临时条目不弹撤销，整张列表才弹`() {
        assertFalse(shouldOfferUndo(0))
        assertFalse(shouldOfferUndo(1))
        assertTrue(shouldOfferUndo(2))
    }

    // ── 续页 ──

    @Test
    fun `临时块末尾`() {
        assertEquals(3, resolveTemporaryBlockEnd(listOf(P, T, T, P)))
        assertEquals(2, resolveTemporaryBlockEnd(listOf(T, T)))
        assertNull(resolveTemporaryBlockEnd(listOf(P, P)))
        assertNull(resolveTemporaryBlockEnd(emptyList()))
    }

    @Test
    fun `离块尾不到两项才续页`() {
        val pinned = listOf(T, T, T, T, T, P)
        assertFalse(shouldLoadMoreContext(pinned, current = 2))
        assertTrue(shouldLoadMoreContext(pinned, current = 3))
        assertTrue(shouldLoadMoreContext(pinned, current = 4))
    }

    @Test
    fun `正在播固定条目或下标越界时不续页`() {
        assertFalse(shouldLoadMoreContext(listOf(T, P), current = 1))
        assertFalse(shouldLoadMoreContext(listOf(T, T), current = -1))
        assertFalse(shouldLoadMoreContext(listOf(T, T), current = 5))
    }

    // ── 右滑删除单条 ──

    @Test
    fun `删当前项之后的条目，当前下标不动`() {
        assertEquals(1, resolveRemoveAt(current = 1, size = 4, index = 3))
    }

    @Test
    fun `删当前项之前的条目，当前下标前移一格`() {
        assertEquals(1, resolveRemoveAt(current = 2, size = 4, index = 0))
    }

    @Test
    fun `正在播的那一项和越界下标拒绝删除`() {
        assertNull(resolveRemoveAt(current = 1, size = 3, index = 1))
        assertNull(resolveRemoveAt(current = 1, size = 3, index = 3))
        assertNull(resolveRemoveAt(current = 1, size = 3, index = -1))
    }

    @Test
    fun `还没起播时删除，下标保持 -1`() {
        assertEquals(-1, resolveRemoveAt(current = -1, size = 2, index = 0))
    }

    // ── 播到时展开分 P ──

    @Test
    fun `展开的就是当前项时停在 P1`() {
        assertEquals(2, resolveIndexAfterExpand(current = 2, index = 2, partCount = 5))
    }

    @Test
    fun `展开点在当前项之前时当前项顺延`() {
        assertEquals(6, resolveIndexAfterExpand(current = 2, index = 0, partCount = 5))
        assertEquals(1, resolveIndexAfterExpand(current = 1, index = 3, partCount = 5))
    }
}
