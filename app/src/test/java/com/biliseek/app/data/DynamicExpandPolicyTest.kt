package com.biliseek.app.data

import com.biliseek.core.network.DynamicEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicExpandPolicyTest {

    private val fetchedEntry = DynamicEntry(
        idStr = "d1",
        authorName = "UP",
        authorFace = "",
        pubTs = 0,
        descText = "完整正文",
        rawType = "DYNAMIC_TYPE_OPUS",
        video = null,
        coverUrl = "",
    )

    @Test
    fun `首次点击 标记展开并需要拉取`() {
        val next = nextDynamicExpandState(null)
        assertTrue(next.expanded)
        assertNull(next.entry)
        assertTrue(shouldFetchDynamicDetail(next))
    }

    @Test
    fun `已展开且已有正文 再点击收起`() {
        val expanded = DynamicExpandState(expanded = true, entry = fetchedEntry)
        val next = nextDynamicExpandState(expanded)
        assertFalse(next.expanded)
        assertEquals(fetchedEntry, next.entry)
        assertFalse(shouldFetchDynamicDetail(next))
    }

    @Test
    fun `收起但有缓存 再点击直接展开不重新拉取`() {
        val collapsedCached = DynamicExpandState(expanded = false, entry = fetchedEntry)
        val next = nextDynamicExpandState(collapsedCached)
        assertTrue(next.expanded)
        assertEquals(fetchedEntry, next.entry)
        assertFalse(shouldFetchDynamicDetail(next))
    }

    @Test
    fun `加载失败后点击重试 重新标记展开并需要拉取`() {
        val errored = DynamicExpandState(expanded = true, error = "网络错误")
        val next = nextDynamicExpandState(errored)
        assertTrue(next.expanded)
        assertNull(next.entry)
        assertNull(next.error)
        assertTrue(shouldFetchDynamicDetail(next))
    }
}
