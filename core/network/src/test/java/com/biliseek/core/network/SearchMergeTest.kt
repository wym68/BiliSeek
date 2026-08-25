package com.biliseek.core.network

import com.biliseek.core.network.dto.SearchVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SearchMergeTest {

    private fun v(bvid: String) = SearchVideo(bvid = bvid, title = bvid)

    @Test
    fun `首页去掉页内重复`() {
        // 实测：第 1 页 20 条里只有 17 条是新的，同一页内部就会重复
        val page = listOf(v("A"), v("B"), v("A"), v("C"))
        assertEquals(listOf("A", "B", "C"), mergeSearchResults(emptyList(), page).map { it.bvid })
    }

    @Test
    fun `追加时过滤掉与已有项重复的`() {
        val existing = listOf(v("A"), v("B"))
        val next = listOf(v("B"), v("C"), v("D"))
        assertEquals(
            listOf("A", "B", "C", "D"),
            mergeSearchResults(existing, next).map { it.bvid },
        )
    }

    @Test
    fun `保持已有顺序不打乱`() {
        val existing = listOf(v("C"), v("A"), v("B"))
        val merged = mergeSearchResults(existing, listOf(v("D")))
        assertEquals(listOf("C", "A", "B", "D"), merged.map { it.bvid })
    }

    @Test
    fun `整页都是重复时返回原列表实例`() {
        val existing = listOf(v("A"), v("B"))
        val merged = mergeSearchResults(existing, listOf(v("A"), v("B")))
        assertSame("无新增时不应产生新列表", existing, merged)
    }

    @Test
    fun `剔除空 bvid`() {
        val merged = mergeSearchResults(listOf(v("A")), listOf(v(""), v("B"), v("")))
        assertEquals(listOf("A", "B"), merged.map { it.bvid })
    }

    @Test
    fun `合并结果的 bvid 恒唯一`() {
        // 崩溃的根因就是这条不成立：LazyColumn 的 key 要求全局唯一
        var acc = emptyList<SearchVideo>()
        repeat(8) { page ->
            val incoming = (0..19).map { v("BV${(page * 17 + it) % 100}") }
            acc = mergeSearchResults(acc, incoming)
        }
        assertEquals("合并后出现重复 key", acc.size, acc.map { it.bvid }.toSet().size)
    }
}
