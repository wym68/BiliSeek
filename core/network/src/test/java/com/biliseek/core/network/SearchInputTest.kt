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
