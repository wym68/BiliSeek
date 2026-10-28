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
 * 样本按 2026-10-27 真机日志里的真实响应整理（列表截短）：字段名、类型、嵌套都是日志里的原样，
 * 只把长列表截到 2–3 项、去掉与解析无关的大段字段（`rights`、`argue_info`、各种 `is_*` 开关等）。
 * 每个样本上方写明来自哪个接口、是否完整；**手写的**样本（日志里没有对应响应，或要造出真实数据里没出现的形态）也逐个注明。
 */
class CollectionDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * 真实：`x/web-interface/wbi/view`（bvid=BV1nXz6B2EVq），UP 上邪aa 的合集「【Hi-Res无损】九宫格歌单」。
     * 日志把这条响应截在了第 2 集的标题中间，所以 `ugc_season` 之后只看得到第 1 集的完整内容；
     * 第 2 集按日志里可见的前缀（`season_id` / `section_id` / `id` / `aid` / `cid`）写出，被截掉的 `bvid`、标题、
     * 封面、时长取自同一合集同一 `aid` 在 `seasons_archives_list` 响应里的真实值。
     * 真实合集共 156 集（`ep_count`），这里只留了前 2 集。
     */
    @Test
    fun `视频详情带合集 各小节各集都解析出来`() {
        val r = json.decodeFromString<BiliResponse<VideoViewInfo>>(
            """
            {
              "code": 0,
              "message": "OK",
              "ttl": 1,
              "data": {
                "bvid": "BV1nXz6B2EVq", "aid": 115949034274981, "videos": 1, "tid": 130, "tid_v2": 2024,
                "tname": "", "tname_v2": "", "copyright": 1,
                "pic": "http://i2.hdslb.com/bfs/archive/626d2d1f7b36b79df0bfdfae801f7cf074ce90e2.jpg",
                "title": "【进步古风DJ 】小曲一响，主角登场！",
                "pubdate": 1769242607, "ctime": 1769242607, "desc": "", "desc_v2": null, "state": 0, "duration": 2077,
                "owner": {
                  "mid": 21572323, "name": "上邪aa",
                  "face": "https://i1.hdslb.com/bfs/face/4a6fb564a6dd69e8abe69dda91f543a792ad6532.jpg"
                },
                "stat": {
                  "aid": 115949034274981, "view": 222674, "danmaku": 4, "reply": 23, "favorite": 14323,
                  "coin": 351, "share": 27, "now_rank": 0, "his_rank": 0, "like": 9899, "dislike": 0,
                  "evaluation": "", "vt": 0
                },
                "dynamic": "", "cid": 35582312523,
                "dimension": { "width": 1920, "height": 1080, "rotate": 0 },
                "season_id": 7258426, "premiere": null, "teenage_mode": 1,
                "pages": [
                  { "cid": 35582312523, "page": 1, "from": "vupload", "part": "【进步古风DJ 】小曲一响，主角登场！",
                    "duration": 2077, "vid": "", "weblink": "",
                    "dimension": { "width": 1920, "height": 1080, "rotate": 0 },
                    "first_frame": "http://i1.hdslb.com/bfs/storyff/_000038aaahrnfmosb28rvk0na5lovab_firsti.jpg",
                    "ctime": 1769242607 }
                ],
                "subtitle": { "allow_submit": false, "list": [] },
                "ugc_season": {
                  "id": 7258426, "title": "【Hi-Res无损】九宫格歌单",
                  "cover": "https://archive.biliimg.com/bfs/archive/626d2d1f7b36b79df0bfdfae801f7cf074ce90e2.jpg",
                  "mid": 21572323, "intro": "", "sign_state": 0, "attribute": 140,
                  "stat": {
                    "season_id": 7258426, "view": 249174418, "danmaku": 4090, "reply": 10480, "fav": 1124933,
                    "coin": 44718, "share": 4532, "now_rank": 0, "his_rank": 0, "like": 1132232, "vt": 0, "vv": 0
                  },
                  "ep_count": 156, "season_type": 1, "is_pay_season": false, "enable_vt": 0,
                  "sections": [
                    { "season_id": 7258426, "id": 8011314, "title": "正片", "type": 1, "episodes": [
                      { "season_id": 7258426, "section_id": 8011314, "id": 172106263,
                        "aid": 115949034274981, "cid": 35582312523, "title": "【进步古风DJ 】小曲一响，主角登场！",
                        "attribute": 0,
                        "arc": {
                          "aid": 115949034274981, "videos": 0, "type_id": 0, "type_name": "", "copyright": 0,
                          "pic": "http://i2.hdslb.com/bfs/archive/626d2d1f7b36b79df0bfdfae801f7cf074ce90e2.jpg",
                          "title": "【进步古风DJ 】小曲一响，主角登场！", "pubdate": 1769242607, "ctime": 1769242607,
                          "desc": "", "state": 0, "duration": 2077,
                          "author": {
                            "mid": 21572323, "name": "上邪aa",
                            "face": "https://i1.hdslb.com/bfs/face/4a6fb564a6dd69e8abe69dda91f543a792ad6532.jpg"
                          },
                          "dynamic": "", "dimension": { "width": 0, "height": 0, "rotate": 0 }, "desc_v2": null,
                          "is_chargeable_season": false, "is_blooper": false, "enable_vt": 0, "vt_display": "",
                          "type_id_v2": 0, "type_name_v2": "", "is_lesson_video": 0
                        },
                        "page": { "cid": 35582312523, "page": 1, "from": "vupload",
                                  "part": "【进步古风DJ 】小曲一响，主角登场！", "duration": 2077, "vid": "", "weblink": "",
                                  "dimension": { "width": 1920, "height": 1080, "rotate": 0 } },
                        "bvid": "BV1nXz6B2EVq",
                        "pages": [ { "cid": 35582312523, "page": 1, "from": "vupload",
                                     "part": "【进步古风DJ 】小曲一响，主角登场！", "duration": 2077, "vid": "", "weblink": "",
                                     "dimension": { "width": 1920, "height": 1080, "rotate": 0 } } ] },
                      { "season_id": 7258426, "section_id": 8011314, "id": 173426509,
                        "aid": 115986531353841, "cid": 35719808392, "title": "【Hi-Res无损/超燃电音】满级人类没有撤退可言！",
                        "attribute": 0,
                        "arc": {
                          "aid": 115986531353841,
                          "pic": "http://i0.hdslb.com/bfs/archive/7311c6d4972e49b2966965410fdfc7bc24024988.jpg",
                          "title": "【Hi-Res无损/超燃电音】满级人类没有撤退可言！", "duration": 1637
                        },
                        "bvid": "BV1aj66BNEYz" }
                    ] }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        val d = r.data!!
        assertEquals("BV1nXz6B2EVq", d.bvid)
        assertEquals(21572323L, d.owner!!.mid)
        val season = d.ugcSeason!!
        assertEquals(7258426L, season.id)
        assertEquals("【Hi-Res无损】九宫格歌单", season.title)
        assertEquals(156, season.epCount)
        assertEquals(listOf("正片"), season.sections.map { it.title })
        assertEquals(listOf("BV1nXz6B2EVq", "BV1aj66BNEYz"), season.sections[0].episodes.map { it.bvid })
        assertEquals("【Hi-Res无损/超燃电音】满级人类没有撤退可言！", season.sections[0].episodes[1].title)
        assertEquals(
            "http://i0.hdslb.com/bfs/archive/7311c6d4972e49b2966965410fdfc7bc24024988.jpg",
            season.sections[0].episodes[1].arc!!.pic,
        )
        assertEquals(1637L, season.sections[0].episodes[1].arc!!.duration)
        assertEquals(2077L, season.sections[0].episodes[0].arc!!.duration)
    }

    /**
     * 手写：日志里看到的合集都只有一个小节「正片」，且每一集都带 `arc`。这里造出真实数据里没出现的形态——
     * 多个小节、某集 `arc` 为 null——确认也解析得出来、不崩。
     */
    @Test
    fun `合集有多个小节且某集没有 arc 也解析`() {
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
        assertEquals(3, season.epCount)
        assertEquals(listOf("上篇", "下篇"), season.sections.map { it.title })
        assertEquals(listOf("BV1a", "BV1b"), season.sections[0].episodes.map { it.bvid })
        assertEquals("//i0.hdslb.com/b.jpg", season.sections[0].episodes[1].arc!!.pic)
        assertEquals(62L, season.sections[0].episodes[1].arc!!.duration)
        assertEquals("BV1c", season.sections[1].episodes[0].bvid)
        assertNull(season.sections[1].episodes[0].arc)
    }

    /**
     * 真实：`x/web-interface/wbi/view`（bvid=BV1JHxg6FE2c），不属于任何合集的视频。响应完整（2164 字节），
     * 只去掉了 `rights`、`argue_info`、`is_*` 开关、`label`、`honor_reply` 等与解析无关的字段。
     * 真实响应里没有 `ugc_season` 这个键（不是 null）。
     */
    @Test
    fun `视频详情不属于合集时 ugcSeason 为 null`() {
        val r = json.decodeFromString<BiliResponse<VideoViewInfo>>(
            """
            {
              "code": 0,
              "message": "OK",
              "ttl": 1,
              "data": {
                "bvid": "BV1JHxg6FE2c", "aid": 117512486394612, "videos": 1, "tid": 124, "tid_v2": 2081,
                "tname": "", "tname_v2": "", "copyright": 3,
                "pic": "http://i0.hdslb.com/bfs/archive/9a1bbece09b1e3eda622875a17f0a33a8af60230.jpg",
                "title": "【睡前消息1123】能输出革命，才能卖LV包",
                "pubdate": 1793098962, "ctime": 1793098794, "desc": "-",
                "desc_v2": [ { "raw_text": "-", "type": 1, "biz_id": 0 } ],
                "state": 0, "duration": 1196,
                "owner": {
                  "mid": 316568752, "name": "马督工",
                  "face": "https://i1.hdslb.com/bfs/face/1c56737dfc0deffffc31c78e0cefb0c3ecf7e000.jpg"
                },
                "stat": {
                  "aid": 117512486394612, "view": 105432, "danmaku": 581, "reply": 1243, "favorite": 2767,
                  "coin": 7574, "share": 629, "now_rank": 0, "his_rank": 0, "like": 10442, "dislike": 0,
                  "evaluation": "", "vt": 0
                },
                "dynamic": "2026年的欧洲，巴黎市民带头造反，七八个国家的市民跟着抗议，似乎回到了19世纪的革命年代。",
                "cid": 43098836239, "dimension": { "width": 1920, "height": 1080, "rotate": 0 },
                "premiere": null, "teenage_mode": 1,
                "pages": [
                  { "cid": 43098836239, "page": 1, "from": "vupload", "part": "20261027睡前消息1123期妙界",
                    "duration": 1196, "vid": "", "weblink": "",
                    "dimension": { "width": 1920, "height": 1080, "rotate": 0 },
                    "first_frame": "http://i1.hdslb.com/bfs/storyff/_00003emkor9zn8k391zebhwuz8paz3b_firsti.jpg",
                    "ctime": 1793098794 }
                ],
                "subtitle": { "allow_submit": false, "list": [] },
                "is_season_display": false
              }
            }
            """.trimIndent(),
        )
        val d = r.data!!
        assertEquals("BV1JHxg6FE2c", d.bvid)
        assertNull(d.ugcSeason)
    }

    /**
     * 真实：`x/web-interface/archive/relation`（bvid=BV1YDHx6SEPA），响应完整、未删字段。日志里 12 条这个接口的响应，
     * `season_fav` 全是 JSON 布尔 `false`（没见过 `true` 或 `1/0`），所以 `1` 和 `true` 两种形态仍用手写的小对象兼容。
     */
    @Test
    fun `互动状态的 season_fav 兼容 1 和 true 缺省为 false`() {
        val real = json.decodeFromString<BiliResponse<ArchiveRelation>>(
            """
            {"code":0,"message":"OK","ttl":1,"data":{"attention":false,"favorite":false,"season_fav":false,"like":false,"dislike":false,"coin":0}}
            """.trimIndent(),
        )
        // 以下三条手写
        val a = json.decodeFromString<BiliResponse<ArchiveRelation>>("""{"code":0,"data":{"season_fav":1}}""")
        val b = json.decodeFromString<BiliResponse<ArchiveRelation>>("""{"code":0,"data":{"season_fav":true}}""")
        val c = json.decodeFromString<BiliResponse<ArchiveRelation>>("""{"code":0,"data":{"like":true}}""")
        val realData = real.data!!
        assertFalse(realData.seasonFav)
        assertEquals(0, realData.coin)
        assertTrue(a.data!!.seasonFav)
        assertTrue(b.data!!.seasonFav)
        assertFalse(c.data!!.seasonFav)
    }

    /**
     * 真实：`x/polymer/web-space/seasons_series_list`（mid=1301773957，page_num=1，page_size=20），UP 超级大大位。
     * 日志把这条响应截在了第 2 个合集中间，所以只留第 1 个合集（它的 `archives` 6 项截到 2 项、`recent_aids` 同样截短）；
     * `series_list` 在被截掉的部分里，这里不写（系列见下一条第 2 页）。
     * 注意真实的 `meta.name` 带「合集·」前缀（`meta.title` 才是干净的合集名）。
     */
    @Test
    fun `UP 空间合集列表`() {
        val r = json.decodeFromString<BiliResponse<SeasonsSeriesListData>>(
            """
            {
              "code": 0,
              "message": "OK",
              "ttl": 1,
              "data": {
                "items_lists": {
                  "page": { "page_num": 1, "page_size": 20, "total": 21 },
                  "seasons_list": [
                    {
                      "archives": [
                        { "aid": 117388234200192, "bvid": "BV1YDHx6SEPA", "ctime": 1791202462, "duration": 3489,
                          "enable_vt": false, "interactive_video": false,
                          "pic": "http://i2.hdslb.com/bfs/archive/7b55f3626aab72c1bc2ae65f34d23932c66259df.jpg",
                          "playback_position": 9, "pubdate": 1791202461,
                          "stat": { "view": 104191, "vt": 0, "danmaku": 283 }, "state": 0,
                          "title": "饥荒女工100天双线毕业开荒流程第一期", "ugc_pay": 0, "vt_display": "", "is_lesson_video": 0 },
                        { "aid": 117392948598284, "bvid": "BV1UnpF6MEx7", "ctime": 1791274794, "duration": 3026,
                          "enable_vt": false, "interactive_video": false,
                          "pic": "http://i1.hdslb.com/bfs/archive/45b37356a85f78c324a246b7f25b446e62039284.jpg",
                          "playback_position": 0, "pubdate": 1791280800,
                          "stat": { "view": 46044, "vt": 0, "danmaku": 136 }, "state": 0,
                          "title": "饥荒女工100天双线毕业开荒流程第二期", "ugc_pay": 0, "vt_display": "", "is_lesson_video": 0 }
                      ],
                      "meta": {
                        "category": 0,
                        "cover": "https://archive.biliimg.com/bfs/archive/87d0d2760b2609775d57bb7c5edb601d89147c12.jpg",
                        "description": "女工100天双线毕业开荒流程", "mid": 1301773957,
                        "name": "合集·女工100天双线毕业开荒流程", "ptime": 1792755715,
                        "season_id": 9258948, "total": 9, "title": "女工100天双线毕业开荒流程"
                      },
                      "recent_aids": [117388234200192, 117392948598284]
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        val lists = r.data!!.itemsLists
        assertEquals(1, lists.page.pageNum)
        assertEquals(21, lists.page.total)
        assertEquals(9258948L, lists.seasonsList.single().meta.seasonId)
        assertEquals("合集·女工100天双线毕业开荒流程", lists.seasonsList.single().meta.name)
        assertEquals("女工100天双线毕业开荒流程", lists.seasonsList.single().meta.title)
        assertEquals(9, lists.seasonsList.single().meta.total)
        assertEquals(1301773957L, lists.seasonsList.single().meta.mid)
        assertTrue(lists.seriesList.isEmpty())
    }

    /**
     * 真实：`x/polymer/web-space/seasons_series_list`（mid=1301773957，page_num=2，page_size=20），UP 超级大大位。
     * 响应完整（第 2 页只剩系列：`seasons_list` 是空数组）；`archives` 6 项截到 2 项、`recent_aids` 同样截短。
     */
    @Test
    fun `UP 空间系列列表`() {
        val r = json.decodeFromString<BiliResponse<SeasonsSeriesListData>>(
            """
            {
              "code": 0,
              "message": "OK",
              "ttl": 1,
              "data": {
                "items_lists": {
                  "page": { "page_num": 2, "page_size": 20, "total": 21 },
                  "seasons_list": [],
                  "series_list": [
                    {
                      "archives": [
                        { "aid": 937625605, "bvid": "BV1UT4y1i76q", "ctime": 1648540796, "duration": 2612,
                          "enable_vt": false, "interactive_video": false,
                          "pic": "http://i1.hdslb.com/bfs/archive/2f9ba69875094b50160878746a218adf04f63872.jpg",
                          "playback_position": 0, "pubdate": 1648540796,
                          "stat": { "view": 5656, "vt": 0, "danmaku": 9 }, "state": 0,
                          "title": "饥荒哈姆雷特新手宝典14废铁机器人和远古先驱", "ugc_pay": 0, "vt_display": "", "is_lesson_video": 0 },
                        { "aid": 297659073, "bvid": "BV1rF411W7xZ", "ctime": 1648469787, "duration": 2825,
                          "enable_vt": false, "interactive_video": false,
                          "pic": "http://i1.hdslb.com/bfs/archive/71639f0661bf77f5f676bc235b82e118464e819a.jpg",
                          "playback_position": 0, "pubdate": 1648469787,
                          "stat": { "view": 3127, "vt": 0, "danmaku": 13 }, "state": 0,
                          "title": "饥荒哈姆雷特新手宝典13一百只坎普斯", "ugc_pay": 0, "vt_display": "", "is_lesson_video": 0 }
                      ],
                      "meta": {
                        "category": 1,
                        "cover": "http://i1.hdslb.com/bfs/archive/2f9ba69875094b50160878746a218adf04f63872.jpg",
                        "creator": "auto", "ctime": 1647779066, "description": "",
                        "keywords": ["饥荒哈姆雷特新手宝典"], "last_update_ts": 1648540797, "mid": 1301773957,
                        "mtime": 1648540797, "name": "饥荒哈姆雷特新手宝典", "raw_keywords": "饥荒哈姆雷特新手宝典",
                        "series_id": 2122757, "state": 2, "total": 14
                      },
                      "recent_aids": [937625605, 297659073]
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        val lists = r.data!!.itemsLists
        assertEquals(2, lists.page.pageNum)
        assertEquals(21, lists.page.total)
        assertTrue(lists.seasonsList.isEmpty())
        assertEquals(2122757L, lists.seriesList.single().meta.seriesId)
        assertEquals("饥荒哈姆雷特新手宝典", lists.seriesList.single().meta.name)
        assertEquals(14, lists.seriesList.single().meta.total)
        assertEquals(1301773957L, lists.seriesList.single().meta.mid)
    }

    /** 手写：日志里没有缺字段的响应；造 `seasons_list` 为 null、其余整块缺失的形态，确认不崩。 */
    @Test
    fun `UP 空间合集列表缺字段不崩`() {
        val r = json.decodeFromString<BiliResponse<SeasonsSeriesListData>>(
            """{"code":0,"data":{"items_lists":{"seasons_list":null}}}""",
        )
        assertTrue(r.data!!.itemsLists.seasonsList.isEmpty())
        assertTrue(r.data!!.itemsLists.seriesList.isEmpty())
        assertEquals(0, r.data!!.itemsLists.page.total)
    }

    /**
     * 真实：`x/polymer/web-space/seasons_archives_list`（mid=316568752，season_id=5693751，page_num=1，page_size=30），
     * UP 马督工的合集「观众自己的故事」。响应完整（2671 字节），`aids` / `archives` 5 项截到 3 项；
     * `meta.total` 和 `page.total` 仍是真实的 5。
     */
    @Test
    fun `合集内容一页`() {
        val r = json.decodeFromString<BiliResponse<SeasonArchivesData>>(
            """
            {
              "code": 0,
              "message": "OK",
              "ttl": 1,
              "data": {
                "aids": [114647088895591, 114725673243466, 114952853527656],
                "archives": [
                  { "aid": 114647088895591, "bvid": "BV1iXTXzVEHd", "ctime": 1749376340, "duration": 2244,
                    "enable_vt": false, "interactive_video": false,
                    "pic": "http://i1.hdslb.com/bfs/archive/24ef4f4ba958652d9befe0a110b91b5a14fa0f67.jpg",
                    "playback_position": 0, "pubdate": 1749376340,
                    "stat": { "view": 773693, "vt": 0, "danmaku": 5051 }, "state": 0,
                    "title": "【睡前消息910】定居上海 想生五个——观众自己的故事 之一", "ugc_pay": 0, "vt_display": "",
                    "is_lesson_video": 0 },
                  { "aid": 114725673243466, "bvid": "BV1YcNXzcEnD", "ctime": 1750575288, "duration": 2150,
                    "enable_vt": false, "interactive_video": false,
                    "pic": "http://i1.hdslb.com/bfs/archive/a0f69cbb94ef44250e34563363ea59e93aced961.jpg",
                    "playback_position": 0, "pubdate": 1750575288,
                    "stat": { "view": 376244, "vt": 0, "danmaku": 1980 }, "state": 0,
                    "title": "【睡前消息916】00后教师：三个月巡考40多场，只为给10后上课", "ugc_pay": 0, "vt_display": "",
                    "is_lesson_video": 0 },
                  { "aid": 114952853527656, "bvid": "BV1gfhHz3Ega", "ctime": 1754041958, "duration": 2770,
                    "enable_vt": false, "interactive_video": false,
                    "pic": "http://i2.hdslb.com/bfs/archive/b733a70e0b800bb11c8e3c4b8dfffa20365ee0e1.jpg",
                    "playback_position": 0, "pubdate": 1754041958,
                    "stat": { "view": 424665, "vt": 0, "danmaku": 2299 }, "state": 0,
                    "title": "【睡前消息933】学中医 给马督工诊脉，当律师 被患者“打黑”", "ugc_pay": 0, "vt_display": "",
                    "is_lesson_video": 0 }
                ],
                "meta": {
                  "category": 0,
                  "cover": "https://archive.biliimg.com/bfs/archive/a0f69cbb94ef44250e34563363ea59e93aced961.jpg",
                  "description": "", "mid": 316568752, "name": "合集·观众自己的故事", "ptime": 1773138187,
                  "season_id": 5693751, "total": 5, "title": "观众自己的故事"
                },
                "page": { "page_num": 1, "page_size": 30, "total": 5 }
              }
            }
            """.trimIndent(),
        )
        val d = r.data!!
        assertEquals(listOf("BV1iXTXzVEHd", "BV1YcNXzcEnD", "BV1gfhHz3Ega"), d.archives.map { it.bvid })
        assertEquals("http://i1.hdslb.com/bfs/archive/24ef4f4ba958652d9befe0a110b91b5a14fa0f67.jpg", d.archives[0].pic)
        assertEquals(2244L, d.archives[0].duration)
        assertEquals(1, d.page.pageNum)
        assertEquals(30, d.page.pageSize)
        assertEquals(5, d.page.total)
    }

    /**
     * 手写：日志里没有 `x/series/archives` 的响应（这次真机没点进旧版系列），样本照接口文档写，**不是真实响应**——
     * 下次真机日志里出现后换成真实的。
     */
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
