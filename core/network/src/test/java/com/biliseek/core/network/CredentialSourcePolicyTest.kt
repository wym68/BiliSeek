package com.biliseek.core.network

import com.biliseek.core.network.internal.CredentialSource
import com.biliseek.core.network.internal.parseLoginBuvids
import com.biliseek.core.network.internal.resolveCredentialSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** B4 采信登录 buvid 的纯函数判据（工单 §4.1）。 */
class CredentialSourcePolicyTest {

    @Test
    fun `buvid3 与 buvid_fp 都在则采信真登录`() {
        assertEquals(CredentialSource.REAL_LOGIN, resolveCredentialSource("b3", "fp"))
    }

    @Test
    fun `缺任一则回落自种`() {
        assertEquals(CredentialSource.SELF_SEEDED, resolveCredentialSource("b3", null))
        assertEquals(CredentialSource.SELF_SEEDED, resolveCredentialSource(null, "fp"))
        assertEquals(CredentialSource.SELF_SEEDED, resolveCredentialSource("", ""))
        assertEquals(CredentialSource.SELF_SEEDED, resolveCredentialSource(null, null))
    }

    @Test
    fun `从 Set-Cookie 抽取 buvid 系`() {
        val cookies = listOf(
            "SESSDATA=abc; Path=/; HttpOnly",
            "buvid3=B3VAL; Domain=.bilibili.com",
            "buvid4=B4VAL; Path=/",
            "buvid_fp=FPVAL; Path=/",
            "b_nut=1700000000; Path=/",
            "bili_jct=JCT; Path=/",
        )
        val b = parseLoginBuvids(cookies)
        assertEquals("B3VAL", b.buvid3)
        assertEquals("B4VAL", b.buvid4)
        assertEquals("FPVAL", b.buvidFp)
        assertEquals("1700000000", b.bNut)
    }

    @Test
    fun `缺失的 buvid 项抽出空串`() {
        val b = parseLoginBuvids(listOf("SESSDATA=abc; Path=/"))
        assertEquals("", b.buvid3)
        assertEquals("", b.buvidFp)
    }
}
