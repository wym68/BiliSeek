package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.PlayerV2Data
import com.biliseek.core.network.dto.SubtitleBody
import com.biliseek.core.network.dto.VideoViewInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 总结的字幕 / 章节接口解析（工单 AI-4）。fixture 为本地构造，不联网。 */
class SubtitleDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `字幕列表 人工与 AI 字幕 章节`() {
        val data = json.decodeFromString<BiliResponse<PlayerV2Data>>(
            """
            {"code":0,"data":{"need_login_subtitle":false,
              "subtitle":{"subtitles":[
                {"id":1,"lan":"zh-CN","lan_doc":"中文（中国）","subtitle_url":"//aisubtitle.hdslb.com/bfs/subtitle/a.json","ai_type":0},
                {"id":2,"lan":"ai-zh","lan_doc":"中文（自动生成）","subtitle_url":"","ai_type":1,"extra":"x"}
              ]},
              "view_points":[{"type":2,"from":0,"to":95,"content":"开场","imgUrl":"x"}]}}
            """.trimIndent(),
        ).data!!
        assertEquals(2, data.subtitle!!.subtitles.size)
        assertEquals("zh-CN", data.subtitle!!.subtitles[0].lan)
        assertEquals(1, data.subtitle!!.subtitles[1].aiType)
        assertEquals("", data.subtitle!!.subtitles[1].subtitleUrl)
        assertEquals(95L, data.viewPoints!!.single().to)
    }

    @Test
    fun `没有字幕与章节字段也能解析`() {
        val data = json.decodeFromString<BiliResponse<PlayerV2Data>>("""{"code":0,"data":{}}""").data!!
        assertNull(data.subtitle)
        assertNull(data.viewPoints)
    }

    @Test
    fun `字幕文件`() {
        val body = json.decodeFromString<SubtitleBody>(
            """{"font_size":0.4,"body":[{"from":0.5,"to":2.25,"location":2,"content":"大家好"},{"from":2.3,"to":4,"content":"今天"}]}""",
        )
        assertEquals(2, body.body.size)
        assertEquals(0.5, body.body[0].from, 1e-9)
        assertEquals("今天", body.body[1].content)
    }

    @Test
    fun `视频详情的简介与分区`() {
        val view = json.decodeFromString<VideoViewInfo>(
            """{"bvid":"BV1xx","aid":1,"title":"标题","desc":"简介","tname":"知识","duration":620,"pic":"http://i0.hdslb.com/a.jpg"}""",
        )
        assertEquals("http://i0.hdslb.com/a.jpg", view.pic)
        assertEquals("简介", view.desc)
        assertEquals("知识", view.tname)
        assertEquals(620L, view.duration)
    }

    @Test
    fun `字幕地址 补协议 只收 B 站域名`() {
        assertEquals(
            "https://aisubtitle.hdslb.com/bfs/subtitle/a.json?auth_key=1",
            normalizeSubtitleUrl("//aisubtitle.hdslb.com/bfs/subtitle/a.json?auth_key=1"),
        )
        assertEquals("https://i0.hdslb.com/a.json", normalizeSubtitleUrl("http://i0.hdslb.com/a.json"))
        assertTrue(normalizeSubtitleUrl("https://api.bilibili.com/x.json")!!.startsWith("https://api.bilibili.com"))
        assertNull(normalizeSubtitleUrl("https://evil.example.com/a.json"))
        assertNull(normalizeSubtitleUrl("https://hdslb.com.evil.io/a.json"))
        assertNull(normalizeSubtitleUrl(""))
        assertNull(normalizeSubtitleUrl("ftp://i0.hdslb.com/a"))
    }
}

/** 官方 AI 总结接口解析（工单 AI-4b）。 */
class ConclusionDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `摘要加大纲`() {
        val data = json.decodeFromString<BiliResponse<com.biliseek.core.network.dto.ConclusionData>>(
            """
            {"code":0,"data":{"code":0,"stid":"7231","model_result":{"result_type":2,"summary":"总结",
              "outline":[{"title":"一","timestamp":10,"part_outline":[{"timestamp":12,"content":"要点"}]}],
              "subtitle":[{"title":"","timestamp":0,"part_subtitle":[{"content":"原话","start_timestamp":0,"end_timestamp":3}]}]}}}
            """.trimIndent(),
        ).data!!
        assertEquals(0, data.code)
        assertEquals("7231", data.stid)
        assertEquals("总结", data.modelResult!!.summary)
        assertEquals(12L, data.modelResult!!.outline.single().partOutline.single().timestamp)
        assertEquals("原话", data.modelResult!!.subtitle.single().partSubtitle.single().content)
    }

    @Test
    fun `排队中 stid 为数字也能读成字符串`() {
        val data = json.decodeFromString<BiliResponse<com.biliseek.core.network.dto.ConclusionData>>(
            """{"code":0,"data":{"code":1,"stid":0,"model_result":null}}""",
        ).data!!
        assertEquals(1, data.code)
        assertEquals("0", data.stid)
    }

    @Test
    fun `未登录只有外层码`() {
        val resp = json.decodeFromString<BiliResponse<com.biliseek.core.network.dto.ConclusionData>>(
            """{"code":-101,"message":"账号未登录"}""",
        )
        assertEquals(-101, resp.code)
        assertEquals(null, resp.data)
    }
}
