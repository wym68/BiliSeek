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
}