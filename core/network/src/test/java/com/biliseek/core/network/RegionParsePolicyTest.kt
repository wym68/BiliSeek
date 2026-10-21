package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.NewlistRankData
import com.biliseek.core.network.internal.toRegionPage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `newlist_rank`（时光机式分区列表）解析。真实样例取自 10-21 用户授权的一次请求（cate_id=28），裁剪成 3 条。 */
class RegionParsePolicyTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun sample(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("newlist_rank_sample.json")) { "缺少测试资源" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `真实样例_字段映射与页数`() {
        val r = json.decodeFromString<BiliResponse<NewlistRankData>>(sample())
        val page = r.data.toRegionPage(requestedPage = 1)
        assertEquals(1, page.page)
        assertEquals(337, page.totalPages)
        assertEquals(listOf("BV18rn36iEBq", "BV1PaJB6eEeM", "BV15GJQ6REdA"), page.items.map { it.bvid })
        val first = page.items[0]
        assertEquals("重阳节，以此摇滚，缅怀逝去的姐妹。", first.title)
        assertEquals("桂香GuiXiang", first.author)
        assertEquals(1920927277L, first.authorMid)
        assertEquals(64L, first.durationSec)
        assertEquals(2134234L, first.playCount)
        assertNull(first.rank)
        assertEquals("https://i0.hdslb.com/bfs/archive/d4c65f30bac48928fd966eb75cd94ca712db77ed.jpg", first.coverUrl)
    }

    @Test
    fun `play不是数字_播放量为null_没有bvid的跳过`() {
        val r = json.decodeFromString<BiliResponse<NewlistRankData>>(
            """
            {"code":0,"data":{"numPages":3,"page":2,"result":[
              {"bvid":"","title":"失效"},
              {"bvid":"BV1xxxxxxxxx","title":"t","play":"--","duration":120}
            ]}}
            """.trimIndent(),
        )
        val page = r.data.toRegionPage(requestedPage = 2)
        assertEquals(listOf("BV1xxxxxxxxx"), page.items.map { it.bvid })
        assertNull(page.items[0].playCount)
        assertEquals(2, page.page)
        assertEquals(3, page.totalPages)
    }

    @Test
    fun `result为null或data为null_空页_页码用请求的`() {
        val r = json.decodeFromString<BiliResponse<NewlistRankData>>("""{"code":0,"data":{"result":null,"numPages":0}}""")
        val page = r.data.toRegionPage(requestedPage = 4)
        assertEquals(emptyList<ChartItem>(), page.items)
        assertEquals(4, page.page)
        assertEquals(0, page.totalPages)
        assertEquals(RegionPage(emptyList(), 1, 0), null.toRegionPage(requestedPage = 1))
    }
}
