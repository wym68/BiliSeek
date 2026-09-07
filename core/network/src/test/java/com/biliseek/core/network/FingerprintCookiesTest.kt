package com.biliseek.core.network

import com.biliseek.core.network.internal.FingerprintCookies
import com.biliseek.core.network.internal.murmur3X64_128Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** B2 指纹 cookie 的格式/向量判据（工单 §4.1）。字节级对齐属真机取证，见诚实红线。 */
class FingerprintCookiesTest {

    @Test
    fun `b_nut 是种植时刻的 unix 秒`() {
        assertEquals("1700000000", FingerprintCookies.bNut(1_700_000_000_000L))
    }

    @Test
    fun `b_lsid 形如 8位大写hex 下划线 时间hex`() {
        val lsid = FingerprintCookies.bLsid(1_700_000_000_000L, Random(1))
        assertTrue(lsid, Regex("^[0-9A-F]{8}_[0-9A-F]+$").matches(lsid))
    }

    @Test
    fun `uuid 形状为 8-4-4-4-12 加5位加 infoc`() {
        val u = FingerprintCookies.uuid(1_700_000_012_345L, Random(2))
        assertTrue(u, Regex("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\d{5}infoc$").matches(u))
        // 尾部 5 位 = ms % 1e5
        assertTrue(u.endsWith("12345infoc"))
    }

    // ── murmur3_x64_128 已知向量 ────────────────────────────

    @Test
    fun `murmur3 空串映射为全零`() {
        // MurmurHash3 x64 128, seed 0, 空输入 → 全零，是公认向量
        assertEquals("00000000000000000000000000000000", murmur3X64_128Hex(ByteArray(0)))
    }

    @Test
    fun `murmur3 确定且长度为 32 hex`() {
        val a = murmur3X64_128Hex("hello".toByteArray())
        val b = murmur3X64_128Hex("hello".toByteArray())
        assertEquals(a, b)
        assertEquals(32, a.length)
        assertNotEquals(a, murmur3X64_128Hex("world".toByteArray()))
    }

    @Test
    fun `buvidFp 输出 32 位 hex`() {
        assertEquals(32, FingerprintCookies.buvidFp("anything").length)
    }
}
