package com.biliseek.core.network

import com.biliseek.core.network.dto.ArchiveRelation
import com.biliseek.core.network.dto.DynamicFeedData
import com.biliseek.core.network.dto.EmotePanelData
import com.biliseek.core.network.dto.MessageHistoryData
import com.biliseek.core.network.dto.ReplyData
import com.biliseek.core.network.dto.VideoViewInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表情 / 富文本 / 互动状态相关 DTO 的离线解析（UI 重构新增字段）。
 * 全部本地 JSON，不访问 B 站。
 */
class EmoteDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `评论解析表情表、at 成员与蓝字关键词`() {
        val data = json.decodeFromString<ReplyData>(
            """
            {"replies": [{
              "rpid": 1,
              "content": {
                "message": "@小明 看[doge]原神",
                "emote": {
                  "[doge]": {"id": 26, "package_id": 1, "text": "[doge]", "url": "https://i0.hdslb.com/doge.png", "meta": {"size": 1}},
                  "[大]": {"id": 2, "text": "[大]", "url": "https://i0.hdslb.com/big.png", "meta": {"size": 2}}
                },
                "members": [{"mid": "123", "uname": "小明"}],
                "jump_url": {"原神": {"title": "原神", "prefix_icon": "x"}}
              }
            }]}
            """.trimIndent(),
        )
        val content = data.replies!!.single().content
        assertEquals("https://i0.hdslb.com/doge.png", content.emote.getValue("[doge]").url)
        assertFalse(content.emote.getValue("[doge]").isLarge)
        assertTrue(content.emote.getValue("[大]").isLarge)
        assertEquals("小明", content.members.single().uname)
        assertEquals("123", content.members.single().mid)
        assertEquals(listOf("原神"), content.jumpKeywords)
    }

    @Test
    fun `评论表情里的坏项被丢弃而不是整页失败`() {
        val data = json.decodeFromString<ReplyData>(
            """
            {"replies": [{
              "rpid": 1,
              "content": {
                "message": "x",
                "emote": {"[a]": "坏数据", "[b]": {"url": ""}, "[c]": {"url": "https://c.png"}},
                "jump_url": []
              }
            }]}
            """.trimIndent(),
        )
        val content = data.replies!!.single().content
        assertEquals(setOf("[c]"), content.emote.keys)
        // 缺 text 时用 key 回填
        assertEquals("[c]", content.emote.getValue("[c]").text)
        assertEquals(emptyList<String>(), content.jumpKeywords)
    }

    @Test
    fun `旧形态评论（只有 message）仍能解析`() {
        val data = json.decodeFromString<ReplyData>("""{"replies":[{"rpid":1,"content":{"message":"hi"}}]}""")
        val content = data.replies!!.single().content
        assertEquals("hi", content.message)
        assertTrue(content.emote.isEmpty())
        assertTrue(content.members.isEmpty())
    }

    @Test
    fun `表情面板解析`() {
        val data = json.decodeFromString<EmotePanelData>(
            """
            {"packages": [
              {"id": 1, "text": "小黄脸", "url": "https://p.png", "type": 1,
               "emote": [{"id": 26, "text": "[doge]", "url": "https://doge.png", "meta": {"size": 1, "alias": "doge"}}]},
              {"id": 4, "text": "颜文字", "type": 4, "emote": [{"id": 9, "text": "(=・ω・=)", "url": ""}]}
            ]}
            """.trimIndent(),
        )
        val packages = data.packages!!
        assertEquals(2, packages.size)
        assertEquals("doge", packages[0].emote!!.single().meta.alias)
        assertEquals("", packages[1].emote!!.single().url)
    }

    @Test
    fun `动态 rich_text_nodes 表情收集进 entry_emotes（含转发原动态）`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {"items": [{
              "id_str": "f1", "type": "DYNAMIC_TYPE_FORWARD",
              "modules": {
                "module_author": {"mid": 1, "name": "转发者"},
                "module_dynamic": {"desc": {"text": "转[doge]", "rich_text_nodes": [
                  {"type": "RICH_TEXT_NODE_TYPE_TEXT", "text": "转"},
                  {"type": "RICH_TEXT_NODE_TYPE_EMOJI", "text": "[doge]", "emoji": {"text": "[doge]", "icon_url": "//i0/doge.png", "size": 1}}
                ]}}
              },
              "orig": {
                "id_str": "o1", "type": "DYNAMIC_TYPE_WORD",
                "modules": {
                  "module_author": {"mid": 2, "name": "原作者"},
                  "module_dynamic": {"desc": {"text": "原[大哭]", "rich_text_nodes": [
                    {"type": "RICH_TEXT_NODE_TYPE_EMOJI", "text": "[大哭]", "emoji": {"text": "[大哭]", "icon_url": "https://i0/cry.png", "size": 2}}
                  ]}}
                }
              }
            }]}
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items).single()
        assertEquals("https://i0/doge.png", entry.emotes.getValue("[doge]").url)
        assertTrue(entry.emotes.getValue("[大哭]").isLarge)
    }

    @Test
    fun `私信 e_infos 解析`() {
        val data = json.decodeFromString<MessageHistoryData>(
            """{"messages": [], "e_infos": [{"text": "[doge]", "url": "https://doge.png", "size": 1}]}""",
        )
        val emote = data.emoteInfos.single().toEmote()
        assertEquals("[doge]", emote.text)
        assertEquals("https://doge.png", emote.url)
    }

    @Test
    fun `图片与大表情私信取图片地址`() {
        assertEquals("https://i0/x.jpg", privateMessageImageUrl(2, """{"url":"//i0/x.jpg"}"""))
        assertEquals("https://i0/e.png", privateMessageImageUrl(6, """{"url":"https://i0/e.png"}"""))
        assertNull(privateMessageImageUrl(1, """{"content":"hi"}"""))
        assertNull(privateMessageImageUrl(6, """{"url":""}"""))
    }

    @Test
    fun `视频 stat 与 copyright`() {
        val info = json.decodeFromString<VideoViewInfo>(
            """{"bvid":"BV1","aid":2,"copyright":2,"stat":{"like":10,"coin":3,"favorite":4,"reply":5,"share":6}}""",
        )
        assertEquals(10L, info.stat!!.like)
        assertEquals(2, info.copyright)
    }

    @Test
    fun `互动状态兼容布尔与 0-1`() {
        val a = json.decodeFromString<ArchiveRelation>("""{"like":true,"favorite":false,"coin":2}""")
        assertTrue(a.like); assertFalse(a.favorite); assertEquals(2, a.coin)
        val b = json.decodeFromString<ArchiveRelation>("""{"like":1,"favorite":1,"coin":0}""")
        assertTrue(b.like); assertTrue(b.favorite)
    }
}
