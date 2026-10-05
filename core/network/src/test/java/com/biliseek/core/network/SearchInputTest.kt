package com.biliseek.core.network

import com.biliseek.core.network.internal.RetryAction
import com.biliseek.core.network.internal.resolveRetryAction
import com.biliseek.core.network.internal.shouldRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchInputTest {

    @Test
    fun `纯关键词走搜索`() {
        assertEquals(SearchIntent.Keyword("洛天依"), parseSearchInput("洛天依"))
        assertEquals(SearchIntent.Keyword("初音 未来"), parseSearchInput("  初音 未来  "))
    }

    @Test
    fun `BV 号直达`() {
        assertEquals(SearchIntent.Video("BV1GJ411x7h7"), parseSearchInput("BV1GJ411x7h7"))
    }

    @Test
    fun `从完整链接里提取 BV 号`() {
        val intent = parseSearchInput("https://www.bilibili.com/video/BV1GJ411x7h7?spm_id_from=333")
        assertEquals(SearchIntent.Video("BV1GJ411x7h7"), intent)
    }

    @Test
    fun `b23 短链识别为待展开`() {
        val intent = parseSearchInput("看看这个 https://b23.tv/AbCd123")
        assertTrue(intent is SearchIntent.ShortLink)
    }

    @Test
    fun `av 号直达`() {
        assertEquals(SearchIntent.VideoAid(170001), parseSearchInput("av170001"))
    }

    @Test
    fun `av 号不分大小写`() {
        assertEquals(SearchIntent.VideoAid(170001), parseSearchInput("AV170001"))
        assertEquals(SearchIntent.VideoAid(170001), parseSearchInput("  Av170001  "))
    }

    @Test
    fun `视频页网址里的 av 号直达，后面可以跟斜杠、问号、html`() {
        assertEquals(SearchIntent.VideoAid(170001), parseSearchInput("https://www.bilibili.com/video/av170001?p=2"))
        assertEquals(SearchIntent.VideoAid(170001), parseSearchInput("看这个 https://www.bilibili.com/video/av170001/ 好看"))
        assertEquals(SearchIntent.VideoAid(170001), parseSearchInput("https://m.bilibili.com/video/av170001.html"))
    }

    @Test
    fun `纯数字是关键词，不再当 av 号`() {
        assertEquals(SearchIntent.Keyword("2024"), parseSearchInput("2024"))
        assertEquals(SearchIntent.Keyword("170001"), parseSearchInput("170001"))
    }

    @Test
    fun `链接里别的数字不算 av 号`() {
        val favlist = "https://space.bilibili.com/12345/favlist?fid=1"
        assertEquals(SearchIntent.Keyword(favlist), parseSearchInput(favlist))
        val column = "https://www.bilibili.com/read/cv12345/"
        assertEquals(SearchIntent.Keyword(column), parseSearchInput(column))
    }

    @Test
    fun `av 后面不是纯数字、或数字是 0，都不算`() {
        assertEquals(SearchIntent.Keyword("avatar2024"), parseSearchInput("avatar2024"))
        assertEquals(SearchIntent.Keyword("av170001abc"), parseSearchInput("av170001abc"))
        assertEquals(SearchIntent.Keyword("av0"), parseSearchInput("av0"))
        assertEquals(SearchIntent.Keyword("av 170001"), parseSearchInput("av 170001"))
    }

    @Test
    fun `活动页网址规整成不带分享参数的 https 地址`() {
        val raw = "https://www.bilibili.com/blackboard/era/fuwAmTrtqFLexuSx.html" +
            "?bili_only=1&share_session_id=abc&timestamp=1"
        assertEquals(
            SearchIntent.EraPage("https://www.bilibili.com/blackboard/era/fuwAmTrtqFLexuSx.html"),
            parseSearchInput(raw),
        )
    }

    @Test
    fun `m 站和 http 的活动页网址也认，并规整成 https 的 www 站`() {
        assertEquals(
            SearchIntent.EraPage("https://www.bilibili.com/blackboard/era/abc-1_x.html"),
            parseSearchInput("http://m.bilibili.com/blackboard/era/abc-1_x.html"),
        )
    }

    @Test
    fun `分享文本里能挑出活动页网址`() {
        assertEquals(
            SearchIntent.EraPage("https://www.bilibili.com/blackboard/era/abc.html"),
            parseSearchInput("【热门播客榜单】 https://www.bilibili.com/blackboard/era/abc.html?x=1 快来看"),
        )
    }

    @Test
    fun `不是 era 的活动页、别的域名下的 era 路径、仿冒域名都不算活动页`() {
        val other = "https://www.bilibili.com/blackboard/activity-xyz.html"
        assertEquals(SearchIntent.Keyword(other), parseSearchInput(other))
        val evil = "https://evil.com/blackboard/era/abc.html"
        assertEquals(SearchIntent.Keyword(evil), parseSearchInput(evil))
        val spoof = "https://bilibili.com.evil.com/blackboard/era/abc.html"
        assertEquals(SearchIntent.Keyword(spoof), parseSearchInput(spoof))
    }

    @Test
    fun `空输入不崩`() {
        assertEquals(SearchIntent.Keyword(""), parseSearchInput("   "))
    }

    @Test
    fun `剥离标题高亮标签`() {
        assertEquals(
            "洛天依原创曲",
            stripHighlight("<em class=\"keyword\">洛天依</em>原创曲"),
        )
    }

    @Test
    fun `解码二次转义的 HTML 实体`() {
        // 接口返回的标题是二次转义的：先转义引号，又转义了 &
        assertEquals(
            "\"当音乐再次响起\"【经典歌合集】",
            stripHighlight("&amp;quot;当音乐再次响起&amp;quot;【经典歌合集】"),
        )
        assertEquals("A & B", stripHighlight("A &amp; B"))
        assertEquals("<tag>", stripHighlight("&lt;tag&gt;"))
    }

    @Test
    fun `高亮标签与实体同时存在`() {
        assertEquals(
            "\"洛天依\"原创",
            stripHighlight("<em class=\"keyword\">&amp;quot;洛天依&amp;quot;</em>原创"),
        )
    }

    @Test
    fun `时长文本解析成秒`() {
        assertEquals(272L, parseDurationText("4:32"))
        assertEquals(3723L, parseDurationText("1:02:03"))
        assertEquals(0L, parseDurationText("0:00"))
        assertEquals(59L, parseDurationText("59"))
        assertEquals(272L, parseDurationText("  4:32  "))
    }

    /** 解析不出来返回 0，交给播放时的时长回写补上，绝不能抛异常打断入库。 */
    @Test
    fun `时长文本脏数据返回零`() {
        assertEquals(0L, parseDurationText(""))
        assertEquals(0L, parseDurationText("   "))
        assertEquals(0L, parseDurationText("--"))
        assertEquals(0L, parseDurationText("4:3x"))
        assertEquals(0L, parseDurationText("1:2:3:4"))
        assertEquals(0L, parseDurationText("-1:30"))
    }

    @Test
    fun `封面地址补协议`() {
        assertEquals("https://i0.hdslb.com/a.jpg", normalizeCoverUrl("//i0.hdslb.com/a.jpg"))
        assertEquals("https://x.com/a.jpg", normalizeCoverUrl("https://x.com/a.jpg"))
        assertEquals("", normalizeCoverUrl(""))
        // 动态接口会给明文 http（M11 真机：Android 9+ 禁明文，不升 https 封面就加载不出来）
        assertEquals("https://i2.hdslb.com/a.jpg", normalizeCoverUrl("http://i2.hdslb.com/a.jpg"))
        assertEquals("https://i2.hdslb.com/a.jpg?x=1", normalizeCoverUrl("http://i2.hdslb.com/a.jpg?x=1"))
    }

    /**
     * 《架构设计篇》§3.2 标注为「做错会主动伤害用户账号」的地方：
     * -352 是风控拦截信号，刷新凭据再打一次会把临时限流推成长期封禁。
     */
    @Test
    fun `风控码 352 绝不重试`() {
        assertEquals(RetryAction.BACKOFF_NO_RETRY, resolveRetryAction(-352))
        assertFalse(shouldRetry(-352))
    }

    @Test
    fun `凭据失效码 101 刷新后重试一次`() {
        assertEquals(RetryAction.REFRESH_AND_RETRY, resolveRetryAction(-101))
        assertTrue(shouldRetry(-101))
    }

    @Test
    fun `拦截码 412 重新种植凭据但不重试`() {
        assertEquals(RetryAction.RESEED_CREDENTIALS, resolveRetryAction(-412))
        assertFalse(shouldRetry(-412))
    }

    @Test
    fun `其他错误码交回调用方`() {
        assertEquals(RetryAction.FAIL, resolveRetryAction(-404))
        assertFalse(shouldRetry(0))
    }

    @Test
    fun `从 Set-Cookie 头解析会话`() {
        val cookies = listOf(
            "SESSDATA=abc%2Cdef; Path=/; Domain=bilibili.com; HttpOnly",
            "bili_jct=token123; Path=/; Domain=bilibili.com",
            "DedeUserID=12345; Path=/; Domain=bilibili.com",
        )
        val session = parseSessionFromCookies(cookies, "refresh_abc")
        assertEquals("abc%2Cdef", session.sessData)
        assertEquals("token123", session.biliJct)
        assertEquals("12345", session.dedeUserId)
        assertEquals("refresh_abc", session.refreshToken)
        assertTrue(session.isLoggedIn)
    }

    @Test
    fun `缺少 SESSDATA 视为未登录`() {
        val session = parseSessionFromCookies(listOf("bili_jct=x"), "")
        assertFalse(session.isLoggedIn)
    }
}
