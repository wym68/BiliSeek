package com.biliseek.app.danmaku

import com.biliseek.core.network.dto.DanmakuItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuPolicyTest {

    private val min = 60_000L

    private fun dm(
        id: Long,
        timeMs: Long = 0,
        mode: Int = 1,
        color: Int = DanmakuItem.DEFAULT_COLOR,
        weight: Int = 5,
        pool: Int = 0,
        content: String = "弹幕$id",
    ) = DanmakuItem(id, timeMs, mode, 25, color, content, weight, pool)

    // ── 分段 ──

    @Test
    fun `段号从 1 起每段 6 分钟`() {
        assertEquals(1, danmakuSegmentIndex(0))
        assertEquals(1, danmakuSegmentIndex(6 * min - 1))
        assertEquals(2, danmakuSegmentIndex(6 * min))
        assertEquals(1, danmakuSegmentIndex(-5))
    }

    @Test
    fun `段数按时长向上取整`() {
        assertEquals(1, danmakuSegmentCount(5 * min))
        assertEquals(1, danmakuSegmentCount(6 * min))
        assertEquals(2, danmakuSegmentCount(6 * min + 1))
        assertEquals(1, danmakuSegmentCount(0))
    }

    @Test
    fun `只要当前段`() {
        assertEquals(listOf(1), resolveDanmakuSegmentsToLoad(1 * min, 60 * min, emptySet()))
    }

    @Test
    fun `临近段尾预取下一段`() {
        assertEquals(listOf(1, 2), resolveDanmakuSegmentsToLoad(6 * min - 10_000, 60 * min, emptySet()))
        assertEquals(listOf(2), resolveDanmakuSegmentsToLoad(6 * min - 10_000, 60 * min, setOf(1)))
    }

    @Test
    fun `最后一段不预取不存在的下一段`() {
        assertEquals(listOf(2), resolveDanmakuSegmentsToLoad(12 * min - 5_000, 12 * min, emptySet()))
    }

    @Test
    fun `已请求过或失败过的段不再请求`() {
        assertTrue(resolveDanmakuSegmentsToLoad(1 * min, 60 * min, setOf(1)).isEmpty())
    }

    // ── 过滤 ──

    @Test
    fun `按类型开关过滤 高级弹幕一律丢`() {
        val items = listOf(dm(1, mode = 1), dm(2, mode = 5), dm(3, mode = 4), dm(4, mode = 7), dm(5, mode = 8))
        assertEquals(listOf(1L, 2L, 3L), filterDanmaku(items, DanmakuSettings()).map { it.id })
        val noTop = DanmakuSettings(showTop = false, showBottom = false)
        assertEquals(listOf(1L), filterDanmaku(items, noTop).map { it.id })
    }

    @Test
    fun `屏蔽等级按权重过滤`() {
        val items = listOf(dm(1, weight = 1), dm(2, weight = 3), dm(3, weight = 9))
        assertEquals(listOf(2L, 3L), filterDanmaku(items, DanmakuSettings(minWeight = 3)).map { it.id })
    }

    @Test
    fun `关掉彩色只留白色`() {
        val items = listOf(dm(1), dm(2, color = 0xFE0302))
        assertEquals(listOf(1L), filterDanmaku(items, DanmakuSettings(showColored = false)).map { it.id })
    }

    @Test
    fun `特殊池 空内容 重复 id 丢弃`() {
        val items = listOf(dm(1, pool = 2), dm(2, content = " "), dm(3), dm(3))
        assertEquals(listOf(3L), filterDanmaku(items, DanmakuSettings()).map { it.id })
    }

    @Test
    fun `按时间排序`() {
        val items = listOf(dm(1, timeMs = 3000), dm(2, timeMs = 1000), dm(3, timeMs = 2000))
        assertEquals(listOf(2L, 3L, 1L), filterDanmaku(items, DanmakuSettings()).map { it.id })
    }

    @Test
    fun `二分查找定位起点`() {
        val sorted = listOf(dm(1, timeMs = 1000), dm(2, timeMs = 2000), dm(3, timeMs = 2000), dm(4, timeMs = 5000))
        assertEquals(0, firstDanmakuIndexAtOrAfter(sorted, 0))
        assertEquals(1, firstDanmakuIndexAtOrAfter(sorted, 2000))
        assertEquals(3, firstDanmakuIndexAtOrAfter(sorted, 2001))
        assertEquals(4, firstDanmakuIndexAtOrAfter(sorted, 9999))
        assertEquals(0, firstDanmakuIndexAtOrAfter(emptyList(), 0))
    }

    @Test
    fun `无法渲染的 mode 没有类别`() {
        assertNull(danmakuKind(7))
        assertEquals(DanmakuKind.SCROLL, danmakuKind(6))
    }
}
