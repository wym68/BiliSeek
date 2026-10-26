package com.biliseek.core.network

import com.biliseek.core.network.dto.ArchiveRelation
import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.SeasonArchivesData
import com.biliseek.core.network.dto.SeasonsSeriesListData
import com.biliseek.core.network.dto.SeriesArchivesData
import com.biliseek.core.network.dto.VideoViewInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合集相关 DTO 解析（设计稿 docs/superpowers/specs/2026-10-26-collections-design.md）。
 *
 * 样本是照 BiliPai 的模型和接口文档手写的，**不是真实响应**——真机跑过后用日志里的真实响应替换。
 */
class CollectionDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `视频详情带合集 各小节各集都解析出来`() {
        val r = json.decodeFromString<BiliResponse<VideoViewInfo>>(
            """
            {
              "code": 0,
              "data": {
                "bvid": "BV1b", "aid": 2, "owner": { "mid": 9, "name": "UP甲" },
                "ugc_season": {
                  "id": 100, "title": "合集甲", "cover": "http://i0.hdslb.com/c.jpg", "mid": 9, "ep_count": 3,
                  "sections": [
                    { "id": 1, "title": "上篇", "episodes": [
                      { "aid": 1, "bvid": "BV1a", "cid": 11, "title": "第一集",
                        "arc": { "pic": "//i0.hdslb.com/a.jpg", "duration": 61 } },
                      { "aid": 2, "bvid": "BV1b", "cid": 12, "title": "第二集",
                        "arc": { "pic": "//i0.hdslb.com/b.jpg", "duration": 62 } }
                    ] },
                    { "id": 2, "title": "下篇", "episodes": [
                      { "aid": 3, "bvid": "BV1c", "cid": 13, "title": "第三集", "arc": null }
                    ] }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        val season = r.data!!.ugcSeason!!
        assertEquals(100L, season.id)
        assertEquals("合集甲", season.title)
        assertEquals(3, season.epCount)
        assertEquals(listOf("上篇", "下篇"), season.sections.map { it.title })
        assertEquals(listOf("BV1a", "BV1b"), season.sections[0].episodes.map { it.bvid })
        assertEquals("第二集", season.sections[0].episodes[1].title)
        assertEquals("//i0.hdslb.com/b.jpg", season.sections[0].episodes[1].arc!!.pic)
        assertEquals(62L, season.sections[0].episodes[1].arc!!.duration)
        assertNull(season.sections[1].episodes[0].arc)
    }

    @Test
    fun `视频详情不属于合集时 ugcSeason 为 null`() {
        val r = json.decodeFromString<BiliResponse<VideoViewInfo>>("""{"code":0,"data":{"bvid":"BV1x"}}""")
        assertNull(r.data!!.ugcSeason)
    }

    @Test
    fun `互动状态的 season_fav 兼容 1 和 true 缺省为 false`() {
        val a = json.decodeFromString<BiliResponse<ArchiveRelation>>("""{"code":0,"data":{"season_fav":1}}""")
        val b = json.decodeFromString<BiliResponse<ArchiveRelation>>("""{"code":0,"data":{"season_fav":true}}""")
        val c = json.decodeFromString<BiliResponse<ArchiveRelation>>("""{"code":0,"data":{"like":true}}""")
        assertTrue(a.data!!.seasonFav)
        assertTrue(b.data!!.seasonFav)
        assertFalse(c.data!!.seasonFav)
    }

    @Test
    fun `UP 空间合集与系列列表`() {
        val r = json.decodeFromString<BiliResponse<SeasonsSeriesListData>>(
            """
            {
              "code": 0,
              "data": {
                "items_lists": {
                  "page": { "page_num": 1, "page_size": 20, "total": 2 },
                  "seasons_list": [
                    { "meta": { "season_id": 100, "name": "合集甲", "cover": "//c1.jpg", "total": 24, "mid": 9 },
                      "recent_aids": [1, 2] }
                  ],
                  "series_list": [
                    { "meta": { "series_id": 200, "name": "系列乙", "cover": "//c2.jpg", "total": 3, "mid": 9 } }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        val lists = r.data!!.itemsLists
        assertEquals(2, lists.page.total)
        assertEquals(100L, lists.seasonsList.single().meta.seasonId)
        assertEquals("合集甲", lists.seasonsList.single().meta.name)
        assertEquals(24, lists.seasonsList.single().meta.total)
        assertEquals(200L, lists.seriesList.single().meta.seriesId)
        assertEquals("系列乙", lists.seriesList.single().meta.name)
    }

    @Test
    fun `UP 空间合集列表缺字段不崩`() {
        val r = json.decodeFromString<BiliResponse<SeasonsSeriesListData>>(
            """{"code":0,"data":{"items_lists":{"seasons_list":null}}}""",
        )
        assertTrue(r.data!!.itemsLists.seasonsList.isEmpty())
        assertTrue(r.data!!.itemsLists.seriesList.isEmpty())
        assertEquals(0, r.data!!.itemsLists.page.total)
    }

    @Test
    fun `合集内容一页`() {
        val r = json.decodeFromString<BiliResponse<SeasonArchivesData>>(
            """
            {
              "code": 0,
              "data": {
                "aids": [1, 2],
                "archives": [
                  { "aid": 1, "bvid": "BV1a", "title": "第一集", "pic": "http://a.jpg", "duration": 61, "pubdate": 1700000000 },
                  { "aid": 2, "bvid": "BV1b", "title": "第二集", "pic": "http://b.jpg", "duration": 62, "pubdate": 1700000100 }
                ],
                "meta": { "season_id": 100, "name": "合集甲", "total": 24 },
                "page": { "page_num": 1, "page_size": 30, "total": 24 }
              }
            }
            """.trimIndent(),
        )
        val d = r.data!!
        assertEquals(listOf("BV1a", "BV1b"), d.archives.map { it.bvid })
        assertEquals("http://a.jpg", d.archives[0].pic)
        assertEquals(61L, d.archives[0].duration)
        assertEquals(24, d.page.total)
    }

    @Test
    fun `系列内容一页`() {
        val r = json.decodeFromString<BiliResponse<SeriesArchivesData>>(
            """
            {
              "code": 0,
              "data": {
                "aids": [5],
                "archives": [
                  { "aid": 5, "bvid": "BV1e", "title": "系列第一个", "pic": "http://e.jpg", "duration": 300, "pubdate": 1600000000 }
                ],
                "page": { "num": 1, "size": 30, "total": 1 }
              }
            }
            """.trimIndent(),
        )
        val d = r.data!!
        assertEquals("BV1e", d.archives.single().bvid)
        assertEquals(300L, d.archives.single().duration)
        assertEquals(1, d.page.total)
    }
}
