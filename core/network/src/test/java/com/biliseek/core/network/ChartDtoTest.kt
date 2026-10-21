package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.MusicListData
import com.biliseek.core.network.dto.MusicPeriodsData
import com.biliseek.core.network.dto.PopularVideo
import com.biliseek.core.network.dto.PreciousData
import com.biliseek.core.network.dto.WeeklySeriesListData
import com.biliseek.core.network.dto.WeeklySeriesOneData
import com.biliseek.core.network.dto.RankingData
import com.biliseek.core.network.internal.toChartPage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 榜单接口（每周必看 / 入站必刷 / 热歌榜）的 DTO 解析。
 *
 * 样例 JSON 除排行榜（`ranking_v2_sample.json`，10-21 的真实抓取）外，按社区文档（bilibili-API-collect）的示例手写，**没有对照过真实响应**：
 * 这里钉住字段名映射和脏数据回退——解析错了不会崩，只会少显示。
 */
class ChartDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `每周必看期数列表解析`() {
        val r = json.decodeFromString<BiliResponse<WeeklySeriesListData>>(
            """
            {"code":0,"message":"0","ttl":1,"data":{"list":[
              {"number":279,"subject":"身残志坚钻石大盗","status":2,"name":"2024第279期 07.19 - 07.25"},
              {"number":278,"subject":"中国诸神最强简史","status":2,"name":"2024第278期 07.12 - 07.18"}
            ]}}
            """.trimIndent(),
        )
        val list = r.data!!.list
        assertEquals(listOf(279, 278), list.map { it.number })
        assertEquals("身残志坚钻石大盗", list[0].subject)
    }

    @Test
    fun `每周必看某一期解析`() {
        val r = json.decodeFromString<BiliResponse<WeeklySeriesOneData>>(
            """
            {"code":0,"message":"0","ttl":1,"data":{
              "config":{"id":3,"type":"weekly_selected","number":3,"subject":"人类首张黑洞照片",
                        "name":"2019第3期 04.05 - 04.11","label":"第3期(0412更新)"},
              "reminder":"每周五晚18:00更新",
              "list":[
                {"aid":48918074,"bvid":"BV1Nb411M7r8","title":"人类第一张黑洞照片，来了！",
                 "pic":"http://i0.hdslb.com/bfs/archive/a2cb.jpg","duration":89,
                 "owner":{"mid":407045223,"name":"二次元的中科院物理所","face":"https://i0.hdslb.com/bfs/face/978e.jpg"},
                 "stat":{"aid":48918074,"view":1594796,"danmaku":23255},
                 "rcmd_reason":"和B站小伙伴一起见证历史！"}
              ]}}
            """.trimIndent(),
        )
        val data = r.data!!
        assertEquals(3, data.config.number)
        assertEquals("人类首张黑洞照片", data.config.subject)
        assertEquals("2019第3期 04.05 - 04.11", data.config.name)
        val v = data.list.single()
        assertEquals("BV1Nb411M7r8", v.bvid)
        assertEquals("http://i0.hdslb.com/bfs/archive/a2cb.jpg", v.pic)
        assertEquals(89L, v.duration)
        assertEquals("二次元的中科院物理所", v.owner?.name)
        assertEquals(407045223L, v.owner?.mid)
        assertEquals(1594796L, v.stat?.view)
    }

    @Test
    fun `入站必刷解析`() {
        val r = json.decodeFromString<BiliResponse<PreciousData>>(
            """
            {"code":0,"message":"0","ttl":1,"data":{
              "title":"入站必刷","media_id":496307088,"explain":"我不允许还有人没看过这98个宝藏视频！",
              "list":[
                {"aid":706,"bvid":"BV1xx411c79H","title":"【東方】Bad Apple!! ＰＶ【影絵】",
                 "pic":"http://i1.hdslb.com/bfs/archive/7534.jpg","duration":219,
                 "owner":{"mid":37,"name":"折射"},"stat":{"view":11329388},
                 "achievement":"东方project标志性视频"}
              ]}}
            """.trimIndent(),
        )
        val data = r.data!!
        assertEquals("我不允许还有人没看过这98个宝藏视频！", data.explain)
        assertEquals("BV1xx411c79H", data.list.single().bvid)
        assertEquals("折射", data.list.single().owner?.name)
    }

    @Test
    fun `热歌榜期数列表按年份对象解析`() {
        val r = json.decodeFromString<BiliResponse<MusicPeriodsData>>(
            """
            {"code":0,"message":"0","ttl":1,"data":{"list":{
              "2022":[{"ID":38,"priod":29,"publish_time":1672394399},{"ID":36,"priod":28,"publish_time":1671789599}],
              "2023":[{"ID":76,"priod":48,"publish_time":1700000000}]
            }}}
            """.trimIndent(),
        )
        val byYear = r.data!!.list
        assertEquals(setOf("2022", "2023"), byYear.keys)
        val first = byYear.getValue("2022")[0]
        assertEquals(38L, first.id)
        assertEquals(29, first.period)
        assertEquals(1672394399L, first.publishTime)
    }

    @Test
    fun `热歌榜歌曲解析`() {
        val r = json.decodeFromString<BiliResponse<MusicListData>>(
            """
            {"code":0,"message":"0","ttl":1,"data":{"list":[
              {"music_id":"MA409252256362326366","music_title":"極楽浄土","singer":"GARNiDELiA",
               "album":"約束 -Promise code-","mv_aid":28361833,"mv_bvid":"BV1us41137Fd",
               "mv_cover":"https://i0.hdslb.com/bfs/station_src/music_metadata/07e0.jpg",
               "heat":5328979,"rank":1,"can_listen":true,"recommendation":"",
               "creation_aid":910787823,"creation_bvid":"BV1HM4y1b79Z",
               "creation_cover":"http://i1.hdslb.com/bfs/archive/5780.jpg",
               "creation_title":"【MARiA】乘风2023初舞台！","creation_up":110352985,
               "creation_nickname":"GARNiDELiA","creation_duration":100,"creation_play":13487670,
               "creation_reason":"官方现场","achievements":["重回榜单","最高排名1"]}
            ]}}
            """.trimIndent(),
        )
        val song = r.data!!.list.single()
        assertEquals("極楽浄土", song.title)
        assertEquals("GARNiDELiA", song.singer)
        assertEquals("BV1us41137Fd", song.mvBvid)
        assertEquals("https://i0.hdslb.com/bfs/station_src/music_metadata/07e0.jpg", song.mvCover)
        assertEquals(1, song.rank)
        assertEquals("BV1HM4y1b79Z", song.creationBvid)
        assertEquals("http://i1.hdslb.com/bfs/archive/5780.jpg", song.creationCover)
        assertEquals(100L, song.creationDuration)
    }

    @Test
    fun `缺字段和 null 都回退到默认值，不抛错`() {
        val one = json.decodeFromString<BiliResponse<WeeklySeriesOneData>>(
            """
            {"code":0,"data":{"list":[
              {"bvid":"BV1a","title":"没有 owner 和 stat","pic":"","duration":null,"owner":null,"stat":null}
            ]}}
            """.trimIndent(),
        ).data!!
        assertEquals("", one.config.name)
        val v = one.list.single()
        assertNull(v.owner)
        assertNull(v.stat)
        assertEquals(0L, v.duration)

        val songs = json.decodeFromString<BiliResponse<MusicListData>>(
            """{"code":0,"data":{"list":[{"music_title":"无 MV","mv_bvid":null,"rank":null,"creation_bvid":"BV1b"}]}}""",
        ).data!!
        val s = songs.list.single()
        assertEquals("", s.mvBvid)
        assertEquals(0, s.rank)
        assertEquals("BV1b", s.creationBvid)
    }

    @Test
    fun `批量视频卡片按 id 为键解析，null 值与未知字段都不抛错`() {
        val r = json.decodeFromString<BiliResponse<Map<String, PopularVideo?>>>(
            """
            {"code":0,"message":"0","ttl":1,"data":{
              "BV1xx411c79H":{"aid":706,"bvid":"BV1xx411c79H","cid":3724723,"title":"【東方】Bad Apple!!",
                "pic":"http://i1.hdslb.com/bfs/archive/7534.jpg","duration":219,
                "owner":{"mid":37,"name":"折射","face":"https://i0.hdslb.com/bfs/face/x.gif"},
                "stat":{"view":11329388,"danmaku":82247},"vt_switch":true},
              "BV1gone000000":null
            }}
            """.trimIndent(),
        )
        val data = r.data!!
        assertEquals(setOf("BV1xx411c79H", "BV1gone000000"), data.keys)
        val v = data.getValue("BV1xx411c79H")!!
        assertEquals("BV1xx411c79H", v.bvid)
        assertEquals("折射", v.owner?.name)
        assertEquals(37L, v.owner?.mid)
        assertEquals(11329388L, v.stat?.view)
        assertNull(data.getValue("BV1gone000000"))
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "缺少测试资源 $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** 10-21 真实返回（不签名、不带 Cookie），裁剪成 3 条。 */
    @Test
    fun `全站排行榜真实样例_按位置给名次_页头是官方说明`() {
        val r = json.decodeFromString<BiliResponse<RankingData>>(resource("ranking_v2_sample.json"))
        val page = r.data!!.toChartPage()
        assertEquals("根据稿件内容质量、近期的数据综合展示，动态更新", page.subtitle)
        assertEquals(listOf("BV1pGnn67EbB", "BV1SknF6KE5g", "BV1Jonu6mEwN"), page.items.map { it.bvid })
        assertEquals(listOf(1, 2, 3), page.items.map { it.rank })
        val first = page.items[0]
        assertEquals("影视飓风", first.author)
        assertEquals(946974L, first.authorMid)
        assertEquals(2682L, first.durationSec)
        assertEquals(4542869L, first.playCount)
        assertEquals("https://i2.hdslb.com/bfs/archive/1bf5beb95e275c81e31f84f4798fdc1eaba4295f.jpg", first.coverUrl)
    }

    @Test
    fun `全站排行榜_没有bvid的条目跳过但名次按原位置`() {
        val r = json.decodeFromString<BiliResponse<RankingData>>(
            """
            {"code":0,"data":{"note":"n","list":[
              {"bvid":"","title":"失效"},
              {"bvid":"BV1xxxxxxxxx","title":"第二名","duration":10}
            ]}}
            """.trimIndent(),
        )
        val page = r.data!!.toChartPage()
        assertEquals(listOf("BV1xxxxxxxxx"), page.items.map { it.bvid })
        assertEquals(listOf(2), page.items.map { it.rank })
    }
}
