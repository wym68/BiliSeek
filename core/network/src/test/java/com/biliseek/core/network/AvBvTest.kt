package com.biliseek.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvBvTest {

    /** 公开的对照组。换表或异或常量抄错，这几条会立刻红。 */
    @Test
    fun `已知 av 与 BV 对照`() {
        assertEquals("BV17x411w7KC", avToBv(170001))
        assertEquals("BV1Q541167Qg", avToBv(455017605))
        assertEquals("BV1mK4y1C7Bz", avToBv(882584971))
    }

    @Test
    fun `逆运算还原 av 号`() {
        assertEquals(170001L, bvToAv("BV17x411w7KC"))
        assertEquals(455017605L, bvToAv("BV1Q541167Qg"))
        assertEquals(882584971L, bvToAv("BV1mK4y1C7Bz"))
    }

    @Test
    fun `往返一致`() {
        listOf(1L, 2L, 999L, 170001L, 100_000_000L, 882584971L, 1_000_000_000L).forEach { aid ->
            assertEquals(aid, bvToAv(avToBv(aid)))
        }
    }

    @Test
    fun `输出恒为 12 位且以 BV1 开头`() {
        listOf(1L, 170001L, 882584971L).forEach { aid ->
            val bv = avToBv(aid)
            assertEquals(12, bv.length)
            assertTrue(bv.startsWith("BV1"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非正 aid 拒绝换算`() {
        avToBv(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `长度不对的 BV 号拒绝解析`() {
        bvToAv("BV1")
    }

    // ── 短链落点校验 ──────────────────────────────────────

    @Test
    fun `bilibili 域内的落点可信`() {
        assertTrue(isTrustedBiliHost("https://www.bilibili.com/video/BV17x411w7KC"))
        assertTrue(isTrustedBiliHost("https://m.bilibili.com/video/BV17x411w7KC"))
        assertTrue(isTrustedBiliHost("http://bilibili.com/video/BV17x411w7KC"))
        assertTrue(isTrustedBiliHost("https://api.bilibili.com/x/y?z=1"))
    }

    /**
     * b23.tv 是通用短链服务，落点不保证还在 B 站。
     * 这几条都是「看起来像」但不是的写法。
     */
    @Test
    fun `域外落点一律不可信`() {
        assertFalse(isTrustedBiliHost("https://evil.com/video/BV17x411w7KC"))
        assertFalse(isTrustedBiliHost("https://bilibili.com.evil.com/x"))
        assertFalse(isTrustedBiliHost("https://notbilibili.com/x"))
        assertFalse(isTrustedBiliHost("https://bilibili.com@evil.com/x"))
        assertFalse(isTrustedBiliHost("ftp://www.bilibili.com/x"))
        assertFalse(isTrustedBiliHost(""))
        assertFalse(isTrustedBiliHost("не url"))
    }

    // ── 外部分享文本 ──────────────────────────────────────

    @Test
    fun `SEND 取 extra text`() {
        assertEquals(
            "看看这个 https://b23.tv/AbCd123",
            sharedTextFrom(
                action = "android.intent.action.SEND",
                type = "text/plain",
                extraText = "看看这个 https://b23.tv/AbCd123",
                dataString = null,
            ),
        )
    }

    @Test
    fun `VIEW 取 data`() {
        assertEquals(
            "https://www.bilibili.com/video/BV17x411w7KC",
            sharedTextFrom(
                action = "android.intent.action.VIEW",
                type = null,
                extraText = null,
                dataString = "https://www.bilibili.com/video/BV17x411w7KC",
            ),
        )
    }

    @Test
    fun `无关 intent 与空内容都返回 null`() {
        assertNull(sharedTextFrom("android.intent.action.MAIN", null, null, null))
        assertNull(sharedTextFrom("android.intent.action.SEND", "image/png", "x", null))
        assertNull(sharedTextFrom("android.intent.action.SEND", "text/plain", "  ", null))
        assertNull(sharedTextFrom("android.intent.action.VIEW", null, null, ""))
        assertNull(sharedTextFrom(null, null, null, null))
    }

    /** 分享文本里混着说明文字，解析器要能从中挑出链接。 */
    @Test
    fun `分享文本里的短链能被挑出来`() {
        val shared = sharedTextFrom(
            "android.intent.action.SEND",
            "text/plain",
            "【标题】 - 哔哩哔哩 https://b23.tv/AbCd123 快来看",
            null,
        )
        assertTrue(parseSearchInput(shared!!) is SearchIntent.ShortLink)
    }
}
