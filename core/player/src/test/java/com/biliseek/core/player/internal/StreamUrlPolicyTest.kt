package com.biliseek.core.player.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线路排序与 playurl 缓存复用。
 *
 * 排序决定 manifest 里的 `dvb:priority`，也就是 ExoPlayer 先用哪条、坏了换哪条；
 * 缓存复用决定「会不会拿一份播到一半就过期的直链去播」。两者出错都表现为某些视频卡住。
 */
class StreamUrlPolicyTest {

    private val upos = "https://upos-sz-mirrorcos.bilivideo.com/a.m4s?deadline=100"
    private val upos2 = "https://upos-sz-mirrorali.bilivideo.com/a.m4s?deadline=100"
    private val mcdn = "https://xy1x2x3x4xy.mcdn.bilivideo.cn:4483/a.m4s?deadline=100"
    private val ipDirect = "https://123.45.67.89:8082/a.m4s"
    private val cleartext = "http://upos-sz-mirrorhw.bilivideo.com/a.m4s"

    @Test
    fun `mcdn 主线路排到官方镜像之后`() {
        assertEquals(listOf(upos, upos2, mcdn), orderCdnUrls(mcdn, listOf(upos, upos2)))
    }

    @Test
    fun `IP 直连同样殿后，同档内保持接口原始顺序`() {
        assertEquals(listOf(upos2, upos, ipDirect), orderCdnUrls(ipDirect, listOf(upos2, upos)))
    }

    @Test
    fun `有 https 可用时丢掉明文 http`() {
        assertEquals(listOf(upos), orderCdnUrls(cleartext, listOf(upos)))
    }

    @Test
    fun `只有明文 http 时原样保留——不能把唯一的线路也丢了`() {
        assertEquals(listOf(cleartext), orderCdnUrls(cleartext, emptyList()))
    }

    @Test
    fun `去重、去空`() {
        assertEquals(listOf(upos, upos2), orderCdnUrls(upos, listOf("", upos, upos2)))
    }

    @Test
    fun `最近连不上的主机排到最后，哪怕它是主线路`() {
        val akamai = "https://upos-hz-mirrorakam.akamaized.net/a.m4s"
        val cosov = "https://upos-sz-mirrorcosov.bilivideo.com/a.m4s"
        assertEquals(
            listOf(cosov, akamai),
            orderCdnUrls(akamai, listOf(cosov), avoidHosts = setOf("upos-hz-mirrorakam.akamaized.net")),
        )
    }

    @Test
    fun `连不上的主机排在险路之后——险路至少没刚失败过`() {
        assertEquals(
            listOf(upos2, mcdn, upos),
            orderCdnUrls(upos, listOf(upos2, mcdn), avoidHosts = setOf("upos-sz-mirrorcos.bilivideo.com")),
        )
    }

    @Test
    fun `失败记录只在窗口内有效`() {
        val failedAt = mapOf("a.example" to 1_000L, "b.example" to 9_000L)
        assertEquals(setOf("a.example", "b.example"), activeCdnFailures(failedAt, nowMs = 9_500L, windowMs = 10_000L))
        assertEquals(setOf("b.example"), activeCdnFailures(failedAt, nowMs = 12_000L, windowMs = 10_000L))
    }

    @Test
    fun `解析直链里的 deadline`() {
        assertEquals(1_727_600_000_000L, playUrlDeadlineMs("https://x/a.m4s?e=abc&deadline=1727600000&gen=playurlv2"))
        assertEquals(1_000L, playUrlDeadlineMs("https://x/a.m4s?deadline=1"))
        assertNull(playUrlDeadlineMs("https://x/a.m4s?e=abc&gen=playurlv2"))
        assertNull("参数名要完整匹配", playUrlDeadlineMs("https://x/a.m4s?olddeadline=1727600000"))
    }

    private val minute = 60_000L

    @Test
    fun `播到结尾前直链不过期才复用`() {
        val fetchedAt = 0L
        val deadline = 120 * minute
        // 第 70 分钟拿出来播 20 分钟的视频：70 + 20 + 5 < 100（取流上限）→ 可以
        assertTrue(shouldReusePlayUrl(fetchedAt, deadline, 20 * minute, 70 * minute, 100 * minute, 5 * minute))
        // 第 90 分钟拿出来播 40 分钟的视频：播到一半就过期 → 不复用，重新取流
        assertFalse(shouldReusePlayUrl(fetchedAt, deadline, 40 * minute, 90 * minute, 100 * minute, 5 * minute))
    }

    @Test
    fun `deadline 比取流上限更早时以 deadline 为准`() {
        // deadline 只有 30 分钟：第 20 分钟再播 10 分钟的视频就不够了
        assertFalse(shouldReusePlayUrl(0L, 30 * minute, 10 * minute, 20 * minute, 100 * minute, 5 * minute))
        assertTrue(shouldReusePlayUrl(0L, 30 * minute, 10 * minute, 10 * minute, 100 * minute, 5 * minute))
    }

    @Test
    fun `没有 deadline 时按取流时刻估`() {
        assertTrue(shouldReusePlayUrl(0L, null, 10 * minute, 10 * minute, 100 * minute, 5 * minute))
        assertFalse(shouldReusePlayUrl(0L, null, 10 * minute, 90 * minute, 100 * minute, 5 * minute))
    }
}
