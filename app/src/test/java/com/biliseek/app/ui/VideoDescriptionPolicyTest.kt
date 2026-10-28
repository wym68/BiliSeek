package com.biliseek.app.ui

import com.biliseek.core.network.avToBv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDescriptionPolicyTest {

    private fun parse(desc: String, durationMs: Long = 0L) = VideoDescriptionPolicy.parse(desc, durationMs)

    // ── 是否显示 ──

    @Test
    fun `空简介与单个横杠不显示`() {
        assertFalse(VideoDescriptionPolicy.shouldShow(""))
        assertFalse(VideoDescriptionPolicy.shouldShow("  \n "))
        assertFalse(VideoDescriptionPolicy.shouldShow("-"))
        assertFalse(VideoDescriptionPolicy.shouldShow(" - "))
        assertTrue(VideoDescriptionPolicy.shouldShow("--"))
        assertTrue(VideoDescriptionPolicy.shouldShow("简介"))
    }

    // ── 统计行 ──

    @Test
    fun `统计行拼接播放量与日期，缺项省略`() {
        assertEquals("播放 52.1 万 · 3天前", VideoDescriptionPolicy.statsLine(521_000, "3天前"))
        assertEquals("播放 999", VideoDescriptionPolicy.statsLine(999, ""))
        assertEquals("3天前", VideoDescriptionPolicy.statsLine(0, "3天前"))
        assertEquals("", VideoDescriptionPolicy.statsLine(0, ""))
    }

    @Test
    fun `简介预览行数 可用高度除以行高向下取整 不小于0`() {
        assertEquals(5, VideoDescriptionPolicy.previewLines(availableHeightPx = 340, lineHeightPx = 64))
        assertEquals(1, VideoDescriptionPolicy.previewLines(availableHeightPx = 64, lineHeightPx = 64))
        assertEquals(0, VideoDescriptionPolicy.previewLines(availableHeightPx = 63, lineHeightPx = 64))
        assertEquals(0, VideoDescriptionPolicy.previewLines(availableHeightPx = 0, lineHeightPx = 64))
        assertEquals(0, VideoDescriptionPolicy.previewLines(availableHeightPx = 300, lineHeightPx = 0))
    }

    // ── 时间点 ──

    @Test
    fun `分秒与时分秒时间点`() {
        assertEquals(
            listOf(
                DescSegment.Timestamp("05:12", 312_000),
                DescSegment.Plain(" 开场\n"),
                DescSegment.Timestamp("1:02:03", 3_723_000),
                DescSegment.Plain(" 结尾"),
            ),
            parse("05:12 开场\n1:02:03 结尾"),
        )
    }

    @Test
    fun `一位分钟与三位分钟都认`() {
        assertEquals(listOf(DescSegment.Timestamp("5:07", 307_000)), parse("5:07"))
        assertEquals(listOf(DescSegment.Timestamp("125:30", 7_530_000)), parse("125:30"))
    }

    @Test
    fun `秒数或三段时的分钟越界不算时间点`() {
        assertEquals(listOf(DescSegment.Plain("05:60")), parse("05:60"))
        assertEquals(listOf(DescSegment.Plain("1:75:00")), parse("1:75:00"))
    }

    @Test
    fun `比例与长数字串里的片段不算时间点`() {
        assertEquals(listOf(DescSegment.Plain("画幅 16:9")), parse("画幅 16:9"))
        assertEquals(listOf(DescSegment.Plain("12345:30")), parse("12345:30"))
        assertEquals(listOf(DescSegment.Plain("05:123")), parse("05:123"))
        assertEquals(listOf(DescSegment.Plain("1:02:03:04")), parse("1:02:03:04"))
    }

    @Test
    fun `超过视频时长的时间点不可点，时长未知时都可点`() {
        val tenMinutes = 600_000L
        assertEquals(listOf(DescSegment.Plain("12:00")), parse("12:00", tenMinutes))
        assertEquals(listOf(DescSegment.Timestamp("10:00", 600_000)), parse("10:00", tenMinutes))
        assertEquals(listOf(DescSegment.Timestamp("12:00", 720_000)), parse("12:00", 0))
    }

    // ── 链接 ──

    @Test
    fun `普通网址走浏览器，止于空白与中文标点`() {
        assertEquals(
            listOf(
                DescSegment.Plain("官网："),
                DescSegment.Link("https://example.com/a?b=1", DescLinkTarget.Web("https://example.com/a?b=1")),
                DescSegment.Plain("。欢迎"),
            ),
            parse("官网：https://example.com/a?b=1。欢迎"),
        )
    }

    @Test
    fun `B 站视频页链接应用内打开`() {
        val url = "https://www.bilibili.com/video/BV1xx411c7mD/?p=2"
        assertEquals(DescLinkTarget.Video("BV1xx411c7mD"), VideoDescriptionPolicy.classifyUrl(url))
        assertEquals(
            DescLinkTarget.Video(avToBv(170001)),
            VideoDescriptionPolicy.classifyUrl("https://m.bilibili.com/video/av170001"),
        )
    }

    @Test
    fun `b23 短链需展开，其他 B 站页面走浏览器`() {
        assertEquals(DescLinkTarget.ShortLink("https://b23.tv/abc123"), VideoDescriptionPolicy.classifyUrl("https://b23.tv/abc123"))
        assertEquals(
            DescLinkTarget.Web("https://space.bilibili.com/123/"),
            VideoDescriptionPolicy.classifyUrl("https://space.bilibili.com/123/"),
        )
        // 搜索框的解析会把任意网址里的 /123/ 当成 av 号，这里不能
        assertEquals(
            DescLinkTarget.Web("https://example.com/avatar/123/"),
            VideoDescriptionPolicy.classifyUrl("https://example.com/avatar/123/"),
        )
    }

    @Test
    fun `只有协议头不算链接`() {
        assertEquals(listOf(DescSegment.Plain("https:// 空")), parse("https:// 空"))
    }

    @Test
    fun `链接里的时间样式不再单独切出`() {
        val url = "https://example.com/t=05:12"
        assertEquals(listOf(DescSegment.Link(url, DescLinkTarget.Web(url))), parse(url))
    }

    // ── BV / av 号 ──

    @Test
    fun `正文里的 BV 与 av 号应用内打开`() {
        assertEquals(
            listOf(
                DescSegment.Plain("上期："),
                DescSegment.Link("BV1xx411c7mD", DescLinkTarget.Video("BV1xx411c7mD")),
                DescSegment.Plain(" 和 "),
                DescSegment.Link("av170001", DescLinkTarget.Video(avToBv(170001))),
            ),
            parse("上期：BV1xx411c7mD 和 av170001"),
        )
    }

    @Test
    fun `紧贴字母数字的片段不算视频号`() {
        assertEquals(listOf(DescSegment.Plain("xBV1xx411c7mD")), parse("xBV1xx411c7mD"))
        assertEquals(listOf(DescSegment.Plain("BV1xx411c7mDz")), parse("BV1xx411c7mDz"))
        assertEquals(listOf(DescSegment.Plain("have123")), parse("have123"))
        assertEquals(listOf(DescSegment.Plain("avatar")), parse("avatar"))
        assertEquals(listOf(DescSegment.Plain("av123x")), parse("av123x"))
    }

    @Test
    fun `空串返回空列表，纯文字合并成一段`() {
        assertEquals(emptyList<DescSegment>(), parse(""))
        assertEquals(listOf(DescSegment.Plain("第一行\n第二行")), parse("第一行\n第二行"))
    }
}
