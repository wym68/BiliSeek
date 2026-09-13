package com.biliseek.core.network

import com.biliseek.core.network.dto.DynamicFeedData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPUS 图文文档形态解析（第一步 feed 路径修复）。
 *
 * 背景：B 站 `major.opus` 文档形态为 `summary:{text,rich_text_nodes}` +
 * `pics:[{url,…}]`，与早期按裸字符串建模的 DTO 不兼容——遇到文档形态会
 * **整页解码失败**。这里验证双形态兼容与正文抽取优先级。
 */
class DynamicOpusCompatTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `OPUS 文档形态混在 AV 中整页解析成功`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [
                { "id_str": "av", "type": "DYNAMIC_TYPE_AV",
                  "modules": { "module_author": { "mid": 1, "name": "视频主" },
                    "module_dynamic": { "major": { "archive": { "bvid": "BV1x", "title": "t", "cover": "//i0/c.jpg", "duration_text": "01:00" } } } } },
                { "id_str": "opus", "type": "DYNAMIC_TYPE_OPUS",
                  "modules": { "module_author": { "mid": 2, "name": "图文主" },
                    "module_dynamic": {
                      "desc": null,
                      "major": { "type": "MAJOR_TYPE_OPUS", "opus": {
                        "title": "图文标题",
                        "jump_url": "//www.bilibili.com/opus/1",
                        "pics": [ { "url": "//i0/p1.jpg", "width": 1080, "height": 720, "size": 123.4 },
                                  { "url": "//i0/p2.jpg", "width": 1080, "height": 720, "size": 56.7 } ],
                        "summary": { "text": "图文正文", "rich_text_nodes": [
                          { "type": "RICH_TEXT_NODE_TYPE_TEXT", "text": "图文正文", "orig_text": "图文正文" } ] }
                      } }
                    } } }
              ]
            }
            """.trimIndent(),
        )
        val entries = parseDynamicItems(data.items)
        assertEquals(2, entries.size)
        val opus = entries[1]
        assertEquals("图文正文", opus.descText)
        assertEquals("图文标题", opus.opusTitle)
        assertEquals(2, opus.pics.size)
        assertEquals("https://i0/p1.jpg", opus.coverUrl)
        assertEquals("https://i0/p2.jpg", opus.pics[1])
    }

    @Test
    fun `OPUS summary_text 为空时拼接 rich_text_nodes`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "opus2", "type": "DYNAMIC_TYPE_OPUS",
                "modules": { "module_author": { "mid": 1, "name": "UP" },
                  "module_dynamic": { "major": { "opus": {
                    "pics": [ { "url": "//i0/a.jpg" } ],
                    "summary": { "text": "", "rich_text_nodes": [
                      { "type": "RICH_TEXT_NODE_TYPE_TEXT", "text": "前半", "orig_text": "前半" },
                      { "type": "RICH_TEXT_NODE_TYPE_EMOJI", "text": "[脱单doge]", "orig_text": "表情" },
                      { "type": "RICH_TEXT_NODE_TYPE_TEXT", "text": "后半", "orig_text": "后半" } ] }
                  } } } } }
              ]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items).single()
        // orig_text 优先（表情节点取原始文本而不是 [脱单doge] 占位）
        assertEquals("前半表情后半", entry.descText)
    }

    @Test
    fun `type=DRAW 但 major 为 opus 时按 opus 取图（真机抓包实例）`() {
        // 真实抓包：B 站新版图文动态顶层 type 仍是 DYNAMIC_TYPE_DRAW，major.draw 为 null，
        // 图片/正文都在 major.opus——按 item.type 分流会把图丢光，回归见 DynamicFeed.kt。
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "d1", "type": "DYNAMIC_TYPE_DRAW",
                "modules": { "module_author": { "mid": 1, "name": "UP" },
                  "module_dynamic": { "desc": null, "major": {
                    "type": "MAJOR_TYPE_OPUS", "draw": null,
                    "opus": {
                      "pics": [ { "url": "http://i0.hdslb.com/bfs/new_dyn/a.jpg", "width": 2000, "height": 1500, "size": 1424.4, "aigc": null, "live_url": null } ],
                      "summary": { "text": "真实抓包正文", "rich_text_nodes": [] }
                    }
                  } } }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items).single()
        assertEquals("真实抓包正文", entry.descText)
        assertEquals(1, entry.pics.size)
        assertEquals("https://i0.hdslb.com/bfs/new_dyn/a.jpg", entry.coverUrl)
    }

    @Test
    fun `WORD 纯文字动态保留 空文案丢弃`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [
                { "id_str": "w1", "type": "DYNAMIC_TYPE_WORD",
                  "modules": { "module_author": { "mid": 1, "name": "话痨" },
                    "module_dynamic": { "desc": { "text": "纯文字内容" } } } },
                { "id_str": "w2", "type": "DYNAMIC_TYPE_WORD",
                  "modules": { "module_author": { "mid": 2, "name": "空白" },
                    "module_dynamic": { "desc": { "text": "" } } } }
              ]
            }
            """.trimIndent(),
        )
        val entries = parseDynamicItems(data.items)
        assertEquals(1, entries.size)
        assertEquals("纯文字内容", entries[0].descText)
        assertTrue(entries[0].video == null)
    }

    @Test
    fun `FORWARD 转发者与 orig 都有文案时追加展示`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "fw", "type": "DYNAMIC_TYPE_FORWARD",
                "modules": { "module_author": { "mid": 2, "name": "转发者" },
                  "module_dynamic": { "desc": { "text": "转发者的话" } } },
                "orig": {
                  "id_str": "orig1", "type": "DYNAMIC_TYPE_OPUS",
                  "modules": { "module_author": { "mid": 1, "name": "原作者" },
                    "module_dynamic": { "major": { "opus": {
                      "pics": [ { "url": "//i0/o.jpg" } ],
                      "summary": { "text": "原作者的话", "rich_text_nodes": [] }
                    } } } } }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items).single()
        assertEquals("转发者的话\n\n//@原作者 原作者的话", entry.descText)
        assertEquals("https://i0/o.jpg", entry.coverUrl)
    }

    @Test
    fun `FORWARD 转发者无文案时用 orig 文案兜底`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "fw2", "type": "DYNAMIC_TYPE_FORWARD",
                "modules": { "module_author": { "mid": 2, "name": "转发者" },
                  "module_dynamic": { "desc": { "text": "" } } },
                "orig": {
                  "id_str": "orig2", "type": "DYNAMIC_TYPE_DRAW",
                  "modules": { "module_author": { "mid": 1, "name": "原作者" },
                    "module_dynamic": { "major": { "draw": { "items": [ { "src": "//i0/d.jpg" } ] } } } } }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items).single()
        // 转发者空文案，orig DRAW 也无文案 → descText 为空但条目保留（有图可展示）
        assertEquals("", entry.descText)
        assertEquals("https://i0/d.jpg", entry.coverUrl)
    }
}
