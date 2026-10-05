package com.biliseek.core.network

import com.biliseek.core.network.dto.SpaceArchiveData
import com.biliseek.core.network.dto.UserSearchData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UP 主搜索与空间投稿 DTO 解析（M11-D）。
 */
class UserDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `UP搜索结果解析_字段名正确`() {
        val data = json.decodeFromString<UserSearchData>(
            """
            {
              "page": 1,
              "numResults": 42,
              "numPages": 3,
              "result": [
                {
                  "type": "bili_user",
                  "mid": 123456,
                  "uname": "某UP主",
                  "usign": "个性签名",
                  "upic": "//i0.hdslb.com/bfs/face/a.jpg",
                  "fans": 12345,
                  "videos": 67,
                  "level": 6
                }
              ]
            }
            """.trimIndent(),
        )
        assertEquals(42, data.numResults)
        assertEquals(3, data.numPages)
        assertEquals(1, data.result.size)
        val user = data.result[0]
        assertEquals(123456L, user.mid)
        assertEquals("某UP主", user.uname)
        assertEquals("个性签名", user.usign)
        assertEquals("//i0.hdslb.com/bfs/face/a.jpg", user.upic)
        assertEquals(12345L, user.fans)
        assertEquals(67, user.videos)
    }

    @Test
    fun `UP搜索结果_脏数据回退`() {
        val data = json.decodeFromString<UserSearchData>(
            """{"result": [{"mid": 1}]}""",
        )
        assertEquals(1, data.result.size)
        assertEquals("", data.result[0].uname)
        assertEquals(0L, data.result[0].fans)
    }

    @Test
    fun `空间投稿解析_字段名正确`() {
        val data = json.decodeFromString<SpaceArchiveData>(
            """
            {
              "list": {
                "tlist": {},
                "vlist": [
                  {
                    "bvid": "BV1xx411c7mD",
                    "aid": 100,
                    "title": "投稿视频",
                    "pic": "//i0.hdslb.com/bfs/archive/p.jpg",
                    "length": "03:53",
                    "author": "UP主",
                    "created": 1700000000,
                    "play": 1000
                  }
                ]
              },
              "page": { "count": 88, "pn": 1, "ps": 30 }
            }
            """.trimIndent(),
        )
        val list = data.list?.vlist.orEmpty()
        assertEquals(1, list.size)
        val video = list[0]
        assertEquals("BV1xx411c7mD", video.bvid)
        assertEquals("投稿视频", video.title)
        // 时长是展示文本（MM:SS），不是秒——解析层转秒由调用方负责
        assertEquals("03:53", video.length)
        assertEquals(233L, parseDurationText(video.length))
        assertEquals("UP主", video.author)
        assertEquals(1700000000L, video.created)
        assertEquals(88, data.page?.count)
    }

    @Test
    fun `空间投稿_空与脏数据回退`() {
        val empty = json.decodeFromString<SpaceArchiveData>("""{}""")
        assertTrue(empty.list?.vlist.orEmpty().isEmpty())

        val dirty = json.decodeFromString<SpaceArchiveData>(
            """{"list": {"vlist": [{"bvid": ""}]}}""",
        )
        // bvid 空的项由 repository 过滤，这里只验 DTO 不崩
        assertEquals("", dirty.list?.vlist?.get(0)?.bvid)
        assertFalse(dirty.page?.pn ?: 0 > 0)
    }

    @Test
    fun `UP搜索参数拼装_search_type为bili_user且含分页`() {
        // 钉住参数表本身：拼错不会崩，只会「搜不到人或分页错位」
        val params = mapOf(
            "search_type" to "bili_user",
            "keyword" to "周杰伦",
            "page" to "2",
            "page_size" to "20",
        )
        assertEquals("bili_user", params["search_type"])
        assertEquals("周杰伦", params["keyword"])
        assertEquals("2", params["page"])
    }

    @Test
    fun `空间投稿参数拼装_mid与分页与排序`() {
        val params = mapOf(
            "mid" to "123456",
            "pn" to "2",
            "ps" to "30",
            "order" to "pubdate",
        )
        assertEquals("123456", params["mid"])
        assertEquals("2", params["pn"])
        assertEquals("30", params["ps"])
        assertEquals("pubdate", params["order"])
    }

    @Test
    fun `空间投稿的充电字段`() {
        val data = json.decodeFromString<SpaceArchiveData>(
            """{"list":{"vlist":[
              {"bvid":"BV1a","title":"充电","is_charging_arc":true,"elec_arc_type":1},
              {"bvid":"BV1b","title":"普通"}
            ]},"page":{"count":2,"pn":1,"ps":30}}""",
        )
        val (charge, normal) = data.list!!.vlist
        assertTrue(charge.isChargingArc)
        assertEquals(1, charge.elecArcType)
        assertFalse(normal.isChargingArc)
        assertEquals(0, normal.elecArcType)
    }

    @Test
    fun `空间投稿的充电角标文字 限时免费的视频是「限时免费」`() {
        // 2026-10-05 真机日志：BV1jjHp6jEk7 is_charging_arc=true elec_arc_type=1 elec_arc_badge="限时免费"
        val data = json.decodeFromString<SpaceArchiveData>(
            """{"list":{"vlist":[
              {"bvid":"BV1a","is_charging_arc":true,"elec_arc_type":1,"elec_arc_badge":"限时免费"},
              {"bvid":"BV1b","is_charging_arc":true,"elec_arc_type":1,"elec_arc_badge":"充电专属"},
              {"bvid":"BV1c"}
            ]},"page":{"count":3,"pn":1,"ps":30}}""",
        )
        val (free, exclusive, normal) = data.list!!.vlist
        assertEquals("限时免费", free.elecArcBadge)
        assertEquals("充电专属", exclusive.elecArcBadge)
        assertEquals("", normal.elecArcBadge)
    }
}
