package com.biliseek.core.network

import com.biliseek.core.network.dto.DynamicBlock
import com.biliseek.core.network.dto.DynamicDetailData
import com.biliseek.core.network.dto.DynamicFeedData
import com.biliseek.core.network.dto.DynamicPic
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图文动态详情四级降级（第二步）。纯函数 + mock JSON，覆盖：
 * - 可渲染性判定 / 占位文案识别
 * - 候选评分与挑选
 * - seed 合并：正文取更长、图片取并集、互动数取 feed
 * - modules 数组形态（opus/detail）→ 段落正文拼接
 */
class DynamicDetailParseTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    // ── 纯函数策略 ─────────────────────────────────

    @Test
    fun `网页链接占位不算正文`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            { "items": [{
              "id_str": "f", "type": "DYNAMIC_TYPE_OPUS",
              "modules": { "module_author": { "mid": 1, "name": "UP" },
                "module_dynamic": { "desc": { "text": "网页链接" } } }
            }] }
            """.trimIndent(),
        )
        val item = data.items.single()
        assertFalse(hasRenderableContent(item))
        assertTrue(isFoldedLinkPlaceholder("网页链接"))
        assertTrue(isFoldedLinkPlaceholder(" 网页链接 "))
    }

    @Test
    fun `正文或 major 任一存在即视为可渲染`() {
        val descOnly = json.decodeFromString<DynamicFeedData>(
            """{ "items": [{
              "id_str": "a", "type": "DYNAMIC_TYPE_DRAW",
              "modules": { "module_author": { "mid": 1, "name": "UP" },
                "module_dynamic": { "desc": { "text": "正文" } } } } ] }""".trimIndent(),
        ).items.single()
        assertTrue(hasRenderableContent(descOnly))
    }

    @Test
    fun `pickBestDetailItem 按内容评分挑选`() {
        val weak = json.decodeFromString<DynamicFeedData>(
            """{ "items": [{
              "id_str": "w", "type": "DYNAMIC_TYPE_OPUS",
              "modules": { "module_author": { "mid": 1, "name": "UP" },
                "module_dynamic": { "major": { "opus": { "summary": { "text": "短", "rich_text_nodes": [] }, "pics": ["//a.jpg"] } } } }
            }] }""".trimIndent(),
        ).items.single()
        val rich = json.decodeFromString<DynamicFeedData>(
            """{ "items": [{
              "id_str": "r", "type": "DYNAMIC_TYPE_OPUS",
              "modules": { "module_author": { "mid": 1, "name": "UP" },
                "module_dynamic": { "major": { "opus": { "summary": { "text": "很长的正文……", "rich_text_nodes": [] }, "pics": ["//a.jpg", "//b.jpg", "//c.jpg"] } } } }
            }] }""".trimIndent(),
        ).items.single()
        val best = pickBestDetailItem(listOf(weak, rich))
        assertEquals("r", best?.idStr)
    }

    @Test
    fun `不可渲染候选做兜底挑选 不抛`() {
        val bad = json.decodeFromString<DynamicFeedData>(
            """{ "items": [{
              "id_str": "b", "type": "DYNAMIC_TYPE_OPUS",
              "modules": { "module_author": { "mid": 1, "name": "UP" },
                "module_dynamic": { "desc": { "text": "网页链接" } } }
            }] }""".trimIndent(),
        ).items.single()
        // 候选全部不可渲染时返回第一个（保留给 UI 提示）
        val best = pickBestDetailItem(listOf(bad))
        assertEquals("b", best?.idStr)
    }

    @Test
    fun `shouldRequestOpusDetail 命中 OPUS DRAW DRAW类型 与 type=11`() {
        val opus = json.decodeFromString<DynamicFeedData>(
            """{ "items": [{
              "id_str": "o", "type": "DYNAMIC_TYPE_OPUS",
              "modules": { "module_author": { "mid": 1, "name": "UP" },
                "module_dynamic": { "major": { "opus": { "summary": { "text": "x", "rich_text_nodes": [] } } } } }
            }] }""".trimIndent(),
        ).items.single()
        val draw = json.decodeFromString<DynamicFeedData>(
            """{ "items": [{
              "id_str": "d", "type": "DYNAMIC_TYPE_DRAW",
              "modules": { "module_author": { "mid": 1, "name": "UP" },
                "module_dynamic": { "major": { "draw": { "items": [] } } } }
            }] }""".trimIndent(),
        ).items.single()
        val eleven = json.decodeFromString<DynamicFeedData>(
            """{ "items": [{
              "id_str": "11", "type": "DYNAMIC_TYPE_DRAW",
              "modules": { "module_author": { "mid": 1, "name": "UP" }, "module_dynamic": {} },
              "basic": { "comment_type": 11 }
            }] }""".trimIndent(),
        ).items.single()
        assertTrue(shouldRequestOpusDetail(opus))
        assertTrue(shouldRequestOpusDetail(draw))
        assertTrue(shouldRequestOpusDetail(eleven))
        // 普通视频
        val av = json.decodeFromString<DynamicFeedData>(
            """{ "items": [{
              "id_str": "av", "type": "DYNAMIC_TYPE_AV",
              "modules": { "module_author": { "mid": 1, "name": "UP" },
                "module_dynamic": { "major": { "archive": { "bvid": "BV1", "title": "t", "cover": "//x", "duration_text": "01:00" } } } }
            }] }""".trimIndent(),
        ).items.single()
        assertFalse(shouldRequestOpusDetail(av))
        // null 永远追加（web 没拿到结果时）
        assertTrue(shouldRequestOpusDetail(null))
    }

    @Test
    fun `mergeDetailItem 正文取更长 图片取并集 互动数取 seed`() {
        val detailItem = json.decodeFromString<DynamicFeedData>(
            """{ "items": [{
              "id_str": "d", "type": "DYNAMIC_TYPE_OPUS",
              "modules": { "module_author": { "mid": 99, "name": "详情作者" },
                "module_dynamic": {
                  "desc": { "text": "详情页完整长正文——可能比 feed 摘要更长" },
                  "major": { "type": "MAJOR_TYPE_OPUS", "opus": {
                    "title": "详情标题", "summary": { "text": "摘要", "rich_text_nodes": [] },
                    "pics": [ { "url": "//i0/d1.jpg" } ] } }
                },
                "module_stat": { "comment": { "count": 1 }, "forward": { "count": 0 }, "like": { "count": 2 } }
              }
            }] }""".trimIndent(),
        ).items.single()

        val feedEntry = parseDynamicItem(json.decodeFromString<DynamicFeedData>(
            """{ "items": [{
              "id_str": "f", "type": "DYNAMIC_TYPE_OPUS",
              "modules": { "module_author": { "mid": 1, "name": "原UP" },
                "module_dynamic": { "major": { "opus": {
                  "summary": { "text": "feed 摘要", "rich_text_nodes": [] },
                  "pics": [ { "url": "//i0/f1.jpg" }, { "url": "//i0/f2.jpg" } ] } } },
                "module_stat": { "comment": { "count": 88 }, "forward": { "count": 12 }, "like": { "count": 999 } }
              }
            }] }""".trimIndent(),
        ).items.single())!!

        val merged = mergeDetailItem(detailItem, feedEntry)
        assertEquals("详情页完整长正文——可能比 feed 摘要更长", merged.descText)
        assertEquals("详情标题", merged.opusTitle)
        // 图并集（detail 补全 feed 已有图，或反之）
        assertEquals(setOf("https://i0/d1.jpg", "https://i0/f1.jpg", "https://i0/f2.jpg"), merged.pics.toSet())
        // 互动数 seed（feed 88/12/999）优先于 detail 的 1/0/2
        assertEquals(88, merged.stat?.comment?.count)
        assertEquals(999, merged.stat?.like?.count)
        // 作者：详情有就用；feed 的 idStr=1 vs detail mid=99 —— 详情不为空就用详情
        assertEquals("详情作者", merged.authorName)
    }

    @Test
    fun `mergeDetailItem seed=null 时直接用详情解析结果`() {
        val detailItem = json.decodeFromString<DynamicFeedData>(
            """{ "items": [{
              "id_str": "x", "type": "DYNAMIC_TYPE_OPUS",
              "modules": { "module_author": { "mid": 1, "name": "作者" },
                "module_dynamic": { "desc": { "text": "正文" }, "major": null } }
            }] }""".trimIndent(),
        ).items.single()
        val merged = mergeDetailItem(detailItem, seed = null)
        assertEquals("正文", merged.descText)
        assertEquals("作者", merged.authorName)
    }

    // ── modules 数组形态（opus/detail）→ 正文 ─────────────────────

    @Test
    fun `opus_detail 的 modules 数组形态抽出段落正文与图片`() {
        // 直接模拟 opus/detail 的 modules 数组；DynamicItem.modules 兼容反序列化后
        // 再走 parseDynamicItem 拿到 descText + pics。
        val detailData = json.decodeFromString<com.biliseek.core.network.dto.DynamicDetailData>(
            """
            {
              "item": {
                "id_str": "op", "type": "DYNAMIC_TYPE_OPUS",
                "modules": [
                  { "module_type": "MODULE_TYPE_TITLE", "module_title": { "text": "长图文标题" } },
                  { "module_type": "MODULE_TYPE_AUTHOR", "module_author": { "mid": 1, "name": "UP" } },
                  { "module_type": "MODULE_TYPE_CONTENT", "module_content": { "paragraphs": [
                    { "text": { "nodes": [
                      { "word": { "words": "第一段" } },
                      { "rich": { "type": "RICH_TEXT_NODE_TYPE_TEXT", "text": "[doge]", "orig_text": "表情占位" } },
                      { "formula": { "latex_content": "E=mc^2" } }
                    ] } },
                    { "pic": { "pics": [ { "url": "//i0/p1.jpg" }, { "url": "//i0/p2.jpg" } ] } },
                    { "text": { "nodes": [ { "word": { "words": "结尾段" } } ] } }
                  ] } }
                ]
              }
            }
            """.trimIndent(),
        )
        val item = detailData.item!!
        // 模块数组已被 serializer 归一化进 DynamicContent
        val content = item.modules!!.dynamic!!
        val major = content.major!!
        val opus = major.opus!!
        assertEquals("长图文标题", opus.title)
        assertEquals("第一段表情占位E=mc^2\n结尾段", content.desc!!.text)
        assertEquals(listOf("https://i0/p1.jpg", "https://i0/p2.jpg"), opus.pics)

        // parseDynamicItem 走到 OPUS 分支，descText 走 desc.text → pic coverUrl 用首张
        val entry = parseDynamicItem(item)!!
        assertEquals("第一段表情占位E=mc^2\n结尾段", entry.descText)
        assertEquals("长图文标题", entry.opusTitle)
        assertEquals("https://i0/p1.jpg", entry.coverUrl)
    }

    @Test
    fun `paragraphs 缺内容时 保留已有 desc`() {
        val detailData = json.decodeFromString<com.biliseek.core.network.dto.DynamicDetailData>(
            """
            { "item": {
              "id_str": "p", "type": "DYNAMIC_TYPE_OPUS",
              "modules": [
                { "module_type": "MODULE_TYPE_AUTHOR", "module_author": { "mid": 1, "name": "UP" } },
                { "module_type": "MODULE_TYPE_CONTENT", "module_content": { "paragraphs": [
                  { "text": { "nodes": [ { "word": { "words": "段落文本" } } ] } }
                ] } }
              ]
            } }
            """.trimIndent(),
        )
        val entry = parseDynamicItem(detailData.item!!)!!
        assertEquals("段落文本", entry.descText)
    }

    // ── 长图文段落：按原文顺序保留文字与图片（展开后图文穿插）─────────────

    /** opus/detail：文字、单图、两段文字、分割线、一段两张图、结尾文字。 */
    private val opusDetailWithInlinePics = """
        {
          "item": {
            "id_str": "op2", "type": "DYNAMIC_TYPE_DRAW",
            "modules": [
              { "module_type": "MODULE_TYPE_AUTHOR", "module_author": { "mid": 1, "name": "UP" } },
              { "module_type": "MODULE_TYPE_CONTENT", "module_content": { "paragraphs": [
                { "para_type": 1, "text": { "nodes": [ { "word": { "words": "开头一段" } } ] } },
                { "para_type": 2, "pic": { "pics": [ { "url": "//i0/a.jpg", "width": 1200, "height": 800 } ] } },
                { "para_type": 1, "text": { "nodes": [ { "word": { "words": "第二段" } } ] } },
                { "para_type": 1, "text": { "nodes": [ { "word": { "words": "第三段" } } ] } },
                { "para_type": 3, "line": { "pic": { "url": "//i0/line.png" } } },
                { "para_type": 2, "pic": { "pics": [ { "url": "//i0/b.jpg" }, { "url": "//i0/c.jpg", "width": 100, "height": 300 } ] } },
                { "para_type": 1, "text": { "nodes": [ { "word": { "words": "结尾" } } ] } }
              ] } }
            ]
          }
        }
    """.trimIndent()

    /** feed / web 详情里同一条动态的摘要：图片位置是「[图片]」，末尾截断，比全文还长。 */
    private val truncatedSummary =
        "开头一段\n[图片]\n第二段\n第三段\n[图片]\n[图片]\n结尾之前还有很长很长很长很长很长的一段摘要…"

    @Test
    fun `opus 详情的段落按原文顺序保留文字与图片`() {
        val item = json.decodeFromString<DynamicDetailData>(opusDetailWithInlinePics).item!!
        val entry = parseDynamicItem(item)!!
        // 连续的文字段合成一段；分割线图是装饰，不进正文段落
        assertEquals(
            listOf(
                DynamicBlock.Text("开头一段"),
                DynamicBlock.Images(listOf(DynamicPic("https://i0/a.jpg", 1200, 800))),
                DynamicBlock.Text("第二段\n第三段"),
                DynamicBlock.Images(listOf(DynamicPic("https://i0/b.jpg"), DynamicPic("https://i0/c.jpg", 100, 300))),
                DynamicBlock.Text("结尾"),
            ),
            entry.body,
        )
        // 平铺的正文与图片照旧
        assertEquals("开头一段\n第二段\n第三段\n结尾", entry.descText)
        assertTrue("https://i0/line.png" in entry.pics)
    }

    @Test
    fun `feed 条目没有正文段落`() {
        val feedItem = json.decodeFromString<DynamicFeedData>(
            """{ "items": [{
              "id_str": "op2", "type": "DYNAMIC_TYPE_DRAW",
              "modules": { "module_author": { "mid": 1, "name": "UP" },
                "module_dynamic": { "major": { "type": "MAJOR_TYPE_OPUS", "opus": {
                  "summary": { "text": "摘要", "rich_text_nodes": [] }, "pics": [] } } } }
            }] }""".trimIndent(),
        ).items.single()
        assertTrue(parseDynamicItem(feedItem)!!.body.isEmpty())
    }

    @Test
    fun `带段落的详情优先于更长的截断摘要`() {
        val web = json.decodeFromString<DynamicFeedData>(
            """{ "items": [{
              "id_str": "op2", "type": "DYNAMIC_TYPE_DRAW",
              "modules": { "module_author": { "mid": 1, "name": "UP" },
                "module_dynamic": { "major": { "type": "MAJOR_TYPE_OPUS", "opus": {
                  "summary": { "text": "${truncatedSummary.replace("\n", "\\n")}", "rich_text_nodes": [] },
                  "pics": [] } } } }
            }] }""".trimIndent(),
        ).items.single()
        val detail = json.decodeFromString<DynamicDetailData>(opusDetailWithInlinePics).item!!
        assertSame(detail, pickBestDetailItem(listOf(web, detail)))
        assertSame(detail, pickBestDetailItem(listOf(detail, web)))
    }

    @Test
    fun `mergeDetailItem 详情带段落时正文用详情的 不取更长的摘要`() {
        val detail = json.decodeFromString<DynamicDetailData>(opusDetailWithInlinePics).item!!
        val seed = DynamicEntry(
            idStr = "op2", authorName = "UP", authorFace = "", pubTs = 0,
            descText = truncatedSummary, rawType = DynamicItemType.DRAW, video = null, coverUrl = "",
        )
        val merged = mergeDetailItem(detail, seed)
        assertEquals("开头一段\n第二段\n第三段\n结尾", merged.descText)
        assertEquals(5, merged.body.size)
    }

    @Test
    fun `详情 type 是数字时按 seed 的类型解析`() {
        // opus/detail 的 type 可能是数字（bilipai 有实例）
        val numeric = json.decodeFromString<DynamicDetailData>(
            """{ "item": { "id_str": "n1", "type": 1, "modules": [
              { "module_type": "MODULE_TYPE_AUTHOR", "module_author": { "mid": 1, "name": "UP" } },
              { "module_type": "MODULE_TYPE_CONTENT", "module_content": { "paragraphs": [
                { "para_type": 1, "text": { "nodes": [ { "word": { "words": "全文" } } ] } }
              ] } }
            ] } }""".trimIndent(),
        ).item!!
        val seed = DynamicEntry(
            idStr = "n1", authorName = "UP", authorFace = "", pubTs = 0,
            descText = "摘", rawType = DynamicItemType.WORD, video = null, coverUrl = "",
        )
        val merged = mergeDetailItem(numeric, seed)
        assertEquals("全文", merged.descText)
        assertEquals(DynamicItemType.WORD, merged.rawType)
        assertEquals(listOf(DynamicBlock.Text("全文")), merged.body)
        // 没有 seed 时按 OPUS 解析
        assertEquals("全文", mergeDetailItem(numeric, seed = null).descText)
    }
}
