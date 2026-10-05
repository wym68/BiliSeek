package com.biliseek.core.network

import com.biliseek.core.network.dto.DynamicFeedData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DRAW / OPUS / ARTICLE 多图解析（Ch4 §4.1.2 / §4.3.3）。
 *
 * 现状（dynamic/feed/dyn + OPUS 列表）只解出首图，导致 9 宫格相关 UI 退化为单图占位。
 * 期望：[DynamicEntry.pics] 收集 DRAW.items / OPUS.pics / ARTICLE.covers 全集，
 *      coverUrl = pics.firstOrNull() ?: coverUrl 兜底。
 */
class DynamicFeedParseTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `DRAW 多图 pics 收集全部图片`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "draw1", "type": "DYNAMIC_TYPE_DRAW",
                "modules": {
                  "module_author": { "mid": 1, "name": "UP" },
                  "module_dynamic": {
                    "desc": { "text": "今天拍了九张" },
                    "major": {
                      "draw": { "items": [
                        { "src": "//i0/a1.jpg" }, { "src": "//i0/a2.jpg" }, { "src": "//i0/a3.jpg" },
                        { "src": "//i0/a4.jpg" }, { "src": "//i0/a5.jpg" }, { "src": "//i0/a6.jpg" },
                        { "src": "//i0/a7.jpg" }, { "src": "//i0/a8.jpg" }, { "src": "//i0/a9.jpg" }
                      ]}
                    }
                  }
                }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items)[0]
        assertEquals(9, entry.pics.size)
        assertEquals("https://i0/a1.jpg", entry.pics.first())
        assertEquals("https://i0/a9.jpg", entry.pics.last())
        assertEquals("https://i0/a1.jpg", entry.coverUrl)
    }

    @Test
    fun `OPUS 多图 pics 收集 opus pics`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "opus1", "type": "DYNAMIC_TYPE_OPUS",
                "modules": {
                  "module_author": { "mid": 1, "name": "UP" },
                  "module_dynamic": {
                    "desc": { "text": "" },
                    "major": {
                      "opus": {
                        "summary": "长图文摘要",
                        "pics": ["//i0/p1.jpg", "//i0/p2.jpg"]
                      }
                    }
                  }
                }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items)[0]
        assertEquals(2, entry.pics.size)
        // 摘要填补空 desc
        assertEquals("长图文摘要", entry.descText)
        assertEquals("https://i0/p1.jpg", entry.coverUrl)
    }

    @Test
    fun `desc 来源优先级为 desc ∪ opus summary ∪ article summary`() {
        // OPUS 情况：desc 非空 → 优先 desc；desc 空 → 退到 opus.summary
        val withDesc = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "a", "type": "DYNAMIC_TYPE_OPUS",
                "modules": {
                  "module_author": { "mid": 1, "name": "UP" },
                  "module_dynamic": {
                    "desc": { "text": "用户写的正文" },
                    "major": { "opus": { "summary": "应被忽略" } }
                  }
                }
              }]
            }
            """.trimIndent(),
        )
        assertEquals("用户写的正文", parseDynamicItems(withDesc.items)[0].descText)
    }

    @Test
    fun `basic 字段映射为 commentOid 与 commentType`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "av1", "type": "DYNAMIC_TYPE_AV",
                "modules": {
                  "module_author": { "mid": 1, "name": "UP" },
                  "module_dynamic": { "major": { "archive": { "bvid": "BV1", "title": "t" } } }
                },
                "basic": { "comment_id_str": "112981396619958", "comment_type": 1 }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items)[0]
        assertEquals(112981396619958L, entry.commentOid)
        assertEquals(1, entry.commentType)
    }

    @Test
    fun `basic 字段缺失时 commentOid 与 commentType 为 0`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "opus2", "type": "DYNAMIC_TYPE_OPUS",
                "modules": {
                  "module_author": { "mid": 1, "name": "UP" },
                  "module_dynamic": { "major": { "opus": { "summary": "x" } } }
                }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items)[0]
        assertEquals(0L, entry.commentOid)
        assertEquals(0, entry.commentType)
    }

    @Test
    fun `src 为空白的 DRAW 图被过滤`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "draw3", "type": "DYNAMIC_TYPE_DRAW",
                "modules": {
                  "module_author": { "mid": 1, "name": "UP" },
                  "module_dynamic": {
                    "major": {
                      "draw": { "items": [
                        { "src": "//i0/a.jpg" }, { "src": "" }, { "src": "//i0/b.jpg" }
                      ]}
                    }
                  }
                }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items)[0]
        assertEquals(2, entry.pics.size)
        assertTrue(entry.pics.none { it.isEmpty() })
    }

    @Test
    fun `module_author mid 映射为 authorMid`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "a1", "type": "DYNAMIC_TYPE_AV",
                "modules": {
                  "module_author": { "mid": 12345, "name": "UP" },
                  "module_dynamic": { "major": { "archive": { "bvid": "BV1", "title": "t" } } }
                }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items)[0]
        assertEquals(12345L, entry.authorMid)
    }

    private fun avItem(archive: String) = json.decodeFromString<DynamicFeedData>(
        """{ "items": [{
          "id_str": "1", "type": "DYNAMIC_TYPE_AV",
          "modules": { "module_author": { "mid": 1, "name": "UP" },
            "module_dynamic": { "major": { "type": "MAJOR_TYPE_ARCHIVE", "archive": $archive } } }
        }] }""".trimIndent(),
    ).items.single()

    @Test
    fun `动态里的视频 角标文字含充电或带充电字段算充电专属`() {
        fun charge(archive: String) = parseDynamicItem(avItem(archive))!!.video!!.chargeExclusive
        assertTrue(charge("""{"bvid":"BV1a","title":"t","badge":{"text":"充电专属"}}"""))
        assertTrue(charge("""{"bvid":"BV1b","title":"t","is_charging_arc":true}"""))
        assertTrue(charge("""{"bvid":"BV1c","title":"t","elec_arc_type":1}"""))
    }

    @Test
    fun `动态里的普通视频 角标是投稿视频 不算充电`() {
        val entry = parseDynamicItem(avItem("""{"bvid":"BV1d","title":"t","badge":{"text":"投稿视频"}}"""))!!
        assertEquals(false, entry.video!!.chargeExclusive)
        assertEquals(false, entry.video!!.limitedFree)
    }

    @Test
    fun `动态里限时免费的充电视频 角标文字是限时免费 不算充电专属`() {
        // 2026-10-05 真机日志：BV1jjHp6jEk7 badge.text="限时免费"（充电专属是「充电专属」，都带 disable_preview=1）
        val entry = parseDynamicItem(avItem("""{"bvid":"BV1a","title":"t","badge":{"text":"限时免费"}}"""))!!
        assertEquals(true, entry.video!!.limitedFree)
        assertEquals(false, entry.video!!.chargeExclusive)
    }

    @Test
    fun `转发的视频按原视频的角标算充电专属和限免`() {
        val forward = json.decodeFromString<DynamicFeedData>(
            """{ "items": [{
              "id_str": "2", "type": "DYNAMIC_TYPE_FORWARD",
              "modules": { "module_author": { "mid": 5, "name": "转发者" }, "module_dynamic": { "desc": { "text": "转了" } } },
              "orig": { "id_str": "3", "type": "DYNAMIC_TYPE_AV",
                "modules": { "module_author": { "mid": 9, "name": "原作者" },
                  "module_dynamic": { "major": { "type": "MAJOR_TYPE_ARCHIVE",
                    "archive": { "bvid": "BV1b", "title": "t", "badge": { "text": "限时免费" } } } } } }
            }] }""".trimIndent(),
        ).items.single()
        val video = parseDynamicItem(forward)!!.video!!
        assertEquals(true, video.limitedFree)
        assertEquals(false, video.chargeExclusive)
    }

    @Test
    fun `充电专属动态没充电时 major blocked 解出锁定标题与提示`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "lock1", "type": "DYNAMIC_TYPE_DRAW",
                "modules": {
                  "module_author": { "mid": 1, "name": "老猫鱼不吃鱼" },
                  "module_dynamic": {
                    "desc": null,
                    "major": {
                      "type": "MAJOR_TYPE_BLOCKED",
                      "blocked": {
                        "blocked_type": 1,
                        "title": "梅友机场专属动态",
                        "hint_message": "加入当前UP主的「30元档包月充电」即可解锁观看",
                        "button": { "text": "30元充电", "jump_url": "https://example.invalid" }
                      }
                    }
                  }
                }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items).single()
        assertEquals("梅友机场专属动态", entry.lockedTitle)
        assertEquals("加入当前UP主的「30元档包月充电」即可解锁观看", entry.lockedHint)
        assertEquals("", entry.descText)
        assertTrue(entry.pics.isEmpty())
    }

    @Test
    fun `纯文字类型的锁定动态没有文案也保留`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "lock2", "type": "DYNAMIC_TYPE_WORD",
                "modules": {
                  "module_author": { "mid": 1, "name": "UP" },
                  "module_dynamic": { "major": { "type": "MAJOR_TYPE_BLOCKED",
                    "blocked": { "title": "专属动态", "hint_message": "充电即可解锁观看" } } }
                }
              }]
            }
            """.trimIndent(),
        )
        assertEquals("专属动态", parseDynamicItems(data.items).single().lockedTitle)
    }

    @Test
    fun `转发锁定动态取原动态的锁定提示`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "fwd1", "type": "DYNAMIC_TYPE_FORWARD",
                "modules": {
                  "module_author": { "mid": 2, "name": "转发者" },
                  "module_dynamic": { "desc": { "text": "好看" } }
                },
                "orig": {
                  "id_str": "lock3", "type": "DYNAMIC_TYPE_DRAW",
                  "modules": {
                    "module_author": { "mid": 1, "name": "UP" },
                    "module_dynamic": { "major": { "type": "MAJOR_TYPE_BLOCKED",
                      "blocked": { "title": "专属动态", "hint_message": "充电即可解锁观看" } } }
                  }
                }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items).single()
        assertEquals("好看", entry.descText)
        assertEquals("专属动态", entry.lockedTitle)
        assertEquals("充电即可解锁观看", entry.lockedHint)
    }
}
