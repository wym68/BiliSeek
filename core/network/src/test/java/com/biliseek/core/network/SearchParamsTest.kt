package com.biliseek.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索筛选参数拼装（M9 §9.8）。
 *
 * 参数表是 [WbiSigner.sign] 的输入——拼错不会崩，只会「筛了个寂寞」或
 * 把筛选参数漏出签名范围导致 -412。这里钉住的是最终参数表本身。
 */
class SearchParamsTest {

    @Test
    fun `基础参数齐全`() {
        val params = buildSearchParams("周杰伦", page = 2)
        assertEquals("video", params["search_type"])
        assertEquals("周杰伦", params["keyword"])
        assertEquals("2", params["page"])
        assertEquals("20", params["page_size"])
    }

    @Test
    fun `默认综合排序不写 order 参数`() {
        // 综合排序是接口默认值，显式传空串与不传等价——不写更干净
        val params = buildSearchParams("k", page = 1)
        assertFalse(params.containsKey("order"))
    }

    @Test
    fun `排序参数透传`() {
        assertEquals("click", buildSearchParams("k", 1, SearchOrder.CLICK)["order"])
        assertEquals("pubdate", buildSearchParams("k", 1, SearchOrder.PUBDATE)["order"])
        assertEquals("dm", buildSearchParams("k", 1, SearchOrder.DM)["order"])
    }

    @Test
    fun `不限时长不写 duration 参数`() {
        assertFalse(buildSearchParams("k", 1, duration = SearchDuration.ANY).containsKey("duration"))
    }

    @Test
    fun `时长区间透传`() {
        assertEquals("1", buildSearchParams("k", 1, duration = SearchDuration.UNDER_10)["duration"])
        assertEquals("2", buildSearchParams("k", 1, duration = SearchDuration._10_30)["duration"])
        assertEquals("3", buildSearchParams("k", 1, duration = SearchDuration._30_60)["duration"])
        assertEquals("4", buildSearchParams("k", 1, duration = SearchDuration.OVER_60)["duration"])
    }

    @Test
    fun `筛选与分页共存`() {
        val params = buildSearchParams("k", 7, SearchOrder.CLICK, SearchDuration.OVER_60)
        assertEquals("7", params["page"])
        assertEquals("click", params["order"])
        assertEquals("4", params["duration"])
        assertTrue(params["keyword"] == "k")
    }
}
