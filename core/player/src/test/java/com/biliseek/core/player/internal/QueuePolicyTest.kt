package com.biliseek.core.player.internal

import com.biliseek.core.player.PlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueuePolicyTest {

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
}
