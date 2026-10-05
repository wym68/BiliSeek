package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.WatchLaterData
import com.biliseek.core.network.dto.WatchLaterItem
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 稍后再看 DTO 解析（M12）。
 *
 * `x/v2/history/toview` 返回 video 列表，字段与搜索结果的 video 类似但更简。
 * 钉住字段名映射与脏数据回退——解析错了不会崩，只会显示空/错数据。
 */
class WatchLaterDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `稍后再看列表解析`() {
        val data = json.decodeFromString<BiliResponse<WatchLaterData>>(
            """
            {
              "code": 0,
              "data": {
                "count": 2,
                "list": [
                  {
                    "aid": 1001,
                    "bvid": "BV1wl",
                    "cid": 2001,
                    "title": "稍后再看一",
                    "pic": "//i0.hdslb.com/bfs/archive/a.jpg",
                    "duration": 3723,
                    "progress": 100,
                    "owner": { "mid": 123, "name": "UP甲" }
                  },
                  {
                    "aid": 1002,
                    "bvid": "BV1wm",
                    "cid": 2002,
                    "title": "稍后再看二",
                    "pic": "//i0.hdslb.com/bfs/archive/b.jpg",
                    "duration": 91,
                    "progress": -1,
                    "owner": null
                  }
                ]
              }
            }
            """.trimIndent(),
        )
        assertTrue(data.code == 0)
        val items = data.data?.list.orEmpty()
        assertEquals(2, items.size)
        val first = items[0]
        assertEquals("BV1wl", first.bvid)
        assertEquals(1001L, first.aid)
        assertEquals(2001L, first.cid)
        assertEquals("稍后再看一", first.title)
        assertEquals(3723L, first.duration)
        assertEquals(100L, first.progress)
        assertNotNull(first.owner)
        assertEquals("UP甲", first.owner?.name)
        // 第二个 owner 缺失不崩
        assertEquals(null, items[1].owner)
    }

    @Test
    fun `列表为空与字段缺失不崩`() {
        val data = json.decodeFromString<BiliResponse<WatchLaterData>>("""{"code": 0, "data": {"count": 0}}""")
        assertEquals(0, data.data?.list.orEmpty().size)
        assertTrue(WatchLaterItem().bvid.isEmpty())
        assertEquals(-1L, WatchLaterItem().progress)
    }

    @Test
    fun `充电视频带 charging_pay 普通视频没有`() {
        val data = json.decodeFromString<BiliResponse<WatchLaterData>>(
            """{"code":0,"data":{"count":2,"list":[
              {"aid":1,"bvid":"BV1a","title":"充电","charging_pay":{"level":1}},
              {"aid":2,"bvid":"BV1b","title":"普通"}
            ]}}""",
        ).data!!
        assertEquals(1, data.list[0].chargingPay?.level)
        assertEquals(null, data.list[1].chargingPay)
    }
}
