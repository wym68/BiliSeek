package com.biliseek.core.network

import com.biliseek.core.network.dto.DynamicFeedData
import com.biliseek.core.network.dto.DynamicItem
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态 DTO 与解析（M11-C）。
 *
 * 动态的 modules/major 深度嵌套、类型枚举多，是 M11 解析最易翻车的一块——
 * 解析错了不会崩，只会「动态全消失 / 图文当视频播」，极难排查。
 */
class DynamicDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `AV动态解析为可播条目`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "12345",
                "type": "DYNAMIC_TYPE_AV",
                "modules": {
                  "module_author": { "mid": 1, "name": "UP主", "face": "//i0.hdslb.com/f.jpg", "pub_ts": 1700000000 },
                  "module_dynamic": {
                    "desc": { "text": "新视频来了" },
                    "major": {
                      "type": "MAJOR_TYPE_ARCHIVE",
                      "archive": {
                        "bvid": "BV1xx411c7mD",
                        "title": "测试视频",
                        "cover": "//i0.hdslb.com/bfs/archive/c.jpg",
                        "duration_text": "03:27"
                      }
                    }
                  }
                }
              }],
              "offset": "abc",
              "has_more": true
            }
            """.trimIndent(),
        )
        assertTrue(data.hasMore)
        assertEquals("abc", data.offset)
        val entries = parseDynamicItems(data.items)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("12345", entry.idStr)
        assertEquals("UP主", entry.authorName)
        assertEquals("https://i0.hdslb.com/f.jpg", entry.authorFace)
        assertEquals("新视频来了", entry.descText)
        assertNotNull(entry.video)
        assertEquals("BV1xx411c7mD", entry.video?.bvid)
        assertEquals("测试视频", entry.video?.title)
        assertEquals("03:27", entry.video?.durationText)
        assertEquals("https://i0.hdslb.com/bfs/archive/c.jpg", entry.coverUrl)
    }

    @Test
    fun `转发动态解析出内嵌视频`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "200",
                "type": "DYNAMIC_TYPE_FORWARD",
                "modules": {
                  "module_author": { "mid": 2, "name": "转发者", "pub_ts": 1700000001 },
                  "module_dynamic": { "desc": { "text": "这个视频值得转" }, "major": null }
                },
                "orig": {
                  "id_str": "100",
                  "type": "DYNAMIC_TYPE_AV",
                  "modules": {
                    "module_author": { "mid": 1, "name": "原作者", "pub_ts": 1700000000 },
                    "module_dynamic": {
                      "major": {
                        "archive": {
                          "bvid": "BV1orig",
                          "title": "原视频",
                          "cover": "//i0.hdslb.com/o.jpg",
                          "duration_text": "10:00"
                        }
                      }
                    }
                  }
                }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items)[0]
        assertEquals("转发者", entry.authorName)
        assertEquals("这个视频值得转", entry.descText)
        assertNotNull("转发应取出内嵌视频", entry.video)
        assertEquals("BV1orig", entry.video?.bvid)
        assertEquals("原视频", entry.video?.title)
    }

    @Test
    fun `转发图文动态不可播但可展示`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "300",
                "type": "DYNAMIC_TYPE_FORWARD",
                "modules": {
                  "module_author": { "mid": 2, "name": "转发者", "pub_ts": 1 },
                  "module_dynamic": { "desc": { "text": "转了张图" } }
                },
                "orig": {
                  "id_str": "101",
                  "type": "DYNAMIC_TYPE_DRAW",
                  "modules": {
                    "module_author": { "mid": 1, "name": "图作者", "pub_ts": 0 },
                    "module_dynamic": {
                      "major": {
                        "draw": { "items": [{ "src": "//i0.hdslb.com/d.png" }] }
                      }
                    }
                  }
                }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items)[0]
        assertNull(entry.video)
        assertEquals("https://i0.hdslb.com/d.png", entry.coverUrl)
    }

    @Test
    fun `图文动态仅展示不可播`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [
                { "id_str": "400", "type": "DYNAMIC_TYPE_DRAW",
                  "modules": { "module_author": { "mid": 1, "name": "图主" },
                    "module_dynamic": { "major": { "draw": { "items": [{ "src": "//i0.hdslb.com/x.png" }] } } } } },
                { "id_str": "401", "type": "DYNAMIC_TYPE_OPUS",
                  "modules": { "module_author": { "mid": 1, "name": "图主" },
                    "module_dynamic": { "major": { "opus": { "summary": "长图文", "pics": ["//i0.hdslb.com/y.png"] } } } } }
              ]
            }
            """.trimIndent(),
        )
        val entries = parseDynamicItems(data.items)
        assertEquals(2, entries.size)
        assertNull(entries[0].video)
        assertEquals("https://i0.hdslb.com/x.png", entries[0].coverUrl)
        assertNull(entries[1].video)
        assertEquals("长图文", entries[1].descText)
        assertEquals("https://i0.hdslb.com/y.png", entries[1].coverUrl)
    }

    @Test
    fun `无法识别的类型与脏数据被丢弃`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [
                { "id_str": "500", "type": "DYNAMIC_TYPE_LIVE",
                  "modules": { "module_author": { "mid": 1, "name": "直播" } } },
                { "id_str": "501", "type": "DYNAMIC_TYPE_AV",
                  "modules": { "module_author": { "mid": 1, "name": "无archive" },
                    "module_dynamic": { "major": { "type": "MAJOR_TYPE_NONE" } } } },
                { "id_str": "", "type": "DYNAMIC_TYPE_AV",
                  "modules": { "module_author": { "mid": 1, "name": "无id" } } },
                { "id_str": "502", "type": "UNKNOWN_TYPE",
                  "modules": { "module_author": { "mid": 1, "name": "未知" } } }
              ]
            }
            """.trimIndent(),
        )
        assertTrue(parseDynamicItems(data.items).isEmpty())
    }

    @Test
    fun `items为null与空不崩`() {
        val data = json.decodeFromString<DynamicFeedData>("""{"has_more": false}""")
        assertTrue(parseDynamicItems(data.items).isEmpty())
        assertTrue(data.offset.isEmpty())
    }

    @Test
    fun `关注列表解析`() {
        val data = json.decodeFromString<com.biliseek.core.network.dto.FollowingData>(
            """
            {
              "list": [
                { "mid": 100, "uname": "UP甲", "face": "//i0.hdslb.com/a.jpg" },
                { "mid": 101, "uname": "UP乙" }
              ],
              "re_version": 0
            }
            """.trimIndent(),
        )
        assertEquals(2, data.list.size)
        assertEquals(100L, data.list[0].mid)
        assertEquals("UP甲", data.list[0].uname)
        assertEquals("", data.list[1].face)
    }

    @Test
    fun `DynamicItem缺字段不崩`() {
        val item = DynamicItem()
        assertNull(parseDynamicItem(item))
    }

    @Test
    fun `AV动态解析module_stat与archive_stat`() {
        // B 站接口真实形状：module_stat 三项 + archive.stat.play/danmaku
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "600",
                "type": "DYNAMIC_TYPE_AV",
                "modules": {
                  "module_author": { "mid": 1, "name": "UP主", "pub_ts": 1700000000 },
                  "module_dynamic": {
                    "major": {
                      "type": "MAJOR_TYPE_ARCHIVE",
                      "archive": {
                        "bvid": "BV1xxx",
                        "title": "视频",
                        "cover": "//i0.hdslb.com/c.jpg",
                        "duration_text": "05:20",
                        "stat": { "play": "12.3万", "danmaku": "2456" }
                      }
                    }
                  },
                  "module_stat": {
                    "comment": { "count": 88 },
                    "forward": { "count": 12, "forbidden": false },
                    "like": { "count": 999, "status": 1 }
                  }
                }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items)[0]
        assertNotNull(entry.stat)
        assertEquals(88, entry.stat?.comment?.count)
        assertEquals(12, entry.stat?.forward?.count)
        assertEquals(false, entry.stat?.forward?.forbidden)
        assertEquals(999, entry.stat?.like?.count)
        assertEquals(true, entry.stat?.like?.status)
        assertEquals("12.3万", entry.video?.playText)
        assertEquals("2456", entry.video?.danmakuText)
    }

    @Test
    fun `转发动态取转发者的module_stat不混orig`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "700",
                "type": "DYNAMIC_TYPE_FORWARD",
                "modules": {
                  "module_author": { "mid": 2, "name": "转发者", "pub_ts": 1700000010 },
                  "module_dynamic": { "desc": { "text": "转一下" } },
                  "module_stat": {
                    "comment": { "count": 1 },
                    "forward": { "count": 2 },
                    "like":    { "count": 3 }
                  }
                },
                "orig": {
                  "id_str": "100",
                  "type": "DYNAMIC_TYPE_AV",
                  "modules": {
                    "module_author": { "mid": 1, "name": "原作者", "pub_ts": 1700000000 },
                    "module_dynamic": {
                      "major": {
                        "archive": {
                          "bvid": "BV1orig",
                          "title": "原视频",
                          "cover": "//i0.hdslb.com/o.jpg",
                          "duration_text": "10:00",
                          "stat": { "play": "999.9万", "danmaku": "1.2万" }
                        }
                      }
                    },
                    "module_stat": {
                      "comment": { "count": 9999 },
                      "forward": { "count": 8888 },
                      "like":    { "count": 7777 }
                    }
                  }
                }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items)[0]
        // 转发者自己的 stat（3/2/1）——不是原作者的（9999/8888/7777）
        assertEquals(3, entry.stat?.like?.count)
        assertEquals(2, entry.stat?.forward?.count)
        assertEquals(1, entry.stat?.comment?.count)
        // 内嵌视频的 stat 取原视频
        assertEquals("999.9万", entry.video?.playText)
        assertEquals("1.2万", entry.video?.danmakuText)
    }

    @Test
    fun `module_stat的status字段同时容忍bool与int`() {
        // B 站实测返回两种形状：
        // 转发 / like 的 status 字段有时是 int (0/1)，有时是 bool (false/true)
        // FlexibleBooleanSerializer 同时吃下两种，都不应该崩
        val intForm = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "900",
                "type": "DYNAMIC_TYPE_AV",
                "modules": {
                  "module_author": { "mid": 1, "name": "UP" },
                  "module_dynamic": { "major": { "archive": { "bvid": "BV1int", "cover": "//x", "duration_text": "01:00" } } },
                  "module_stat": {
                    "comment": { "count": 1 },
                    "forward": { "count": 0, "status": 1, "forbidden": true },
                    "like":    { "count": 2, "status": 0 }
                  }
                }
              }]
            }
            """.trimIndent(),
        )
        val boolForm = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "901",
                "type": "DYNAMIC_TYPE_AV",
                "modules": {
                  "module_author": { "mid": 1, "name": "UP" },
                  "module_dynamic": { "major": { "archive": { "bvid": "BV1bool", "cover": "//x", "duration_text": "01:00" } } },
                  "module_stat": {
                    "forward": { "count": 0, "status": true, "forbidden": true },
                    "like":    { "count": 2, "status": false }
                  }
                }
              }]
            }
            """.trimIndent(),
        )
        // int 1 / bool true → 都是 true；int 0 / bool false → 都是 false
        assertEquals(true, parseDynamicItems(intForm.items)[0].stat?.forward?.status)
        assertEquals(false, parseDynamicItems(intForm.items)[0].stat?.like?.status)
        assertEquals(true, parseDynamicItems(boolForm.items)[0].stat?.forward?.status)
        assertEquals(false, parseDynamicItems(boolForm.items)[0].stat?.like?.status)
    }

    @Test
    fun `module_stat缺失时stat为null`() {
        // B 站历史版本 / 部分 UP 关闭统计 / 接口异常都会漏字段
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "800",
                "type": "DYNAMIC_TYPE_AV",
                "modules": {
                  "module_author": { "mid": 1, "name": "UP主", "pub_ts": 1 },
                  "module_dynamic": {
                    "major": {
                      "archive": {
                        "bvid": "BV1noStat",
                        "title": "无统计",
                        "cover": "//i0.hdslb.com/n.jpg",
                        "duration_text": "01:00"
                      }
                    }
                  }
                }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items)[0]
        assertNull(entry.stat)
        // archive.stat 也没有 → playText/danmakuText 都空，UI 据此不渲染 stat 行
        assertEquals("", entry.video?.playText)
        assertEquals("", entry.video?.danmakuText)
    }
}
