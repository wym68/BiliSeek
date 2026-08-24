package com.biliseek.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WbiSignerTest {

    /** 官方前端 JS 里的真实密钥对，用于固定 mixinKey 推导结果。 */
    private val imgKey = "7cd084941338484aae1ad9425b84077c"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"

    @Test
    fun `mixinKey 取打乱后的前 32 位`() {
        val mixin = WbiSigner.mixinKey(imgKey + subKey)
        assertEquals(32, mixin.length)
        // 同样输入必须得到同样输出，否则签名无法复现
        assertEquals(mixin, WbiSigner.mixinKey(imgKey + subKey))
    }

    @Test
    fun `签名结果对固定输入稳定`() {
        val params = mapOf("foo" to "114", "bar" to "514", "zab" to "1919810")
        val signed = WbiSigner.sign(params, imgKey, subKey, timestampSeconds = 1702204169)

        assertEquals("1702204169", signed["wts"])
        // 该组输入的官方文档示例值
        assertEquals("8f6f2b5b3d485fe1886cec6a0be8c5d4", signed["w_rid"])
    }

    @Test
    fun `签名保留原始未编码值交给 Retrofit`() {
        val signed = WbiSigner.sign(mapOf("q" to "hello world"), imgKey, subKey)
        // 值不能被预先编码，否则 Retrofit 会二次编码
        assertEquals("hello world", signed["q"])
    }

    @Test
    fun `非法字符被剔除`() {
        assertEquals("abc", WbiSigner.filterIllegalChars("a!b'c"))
        assertEquals("xy", WbiSigner.filterIllegalChars("x()*y"))
    }

    @Test
    fun `编码对齐 encodeURIComponent`() {
        assertEquals("hello%20world", WbiSigner.encodeComponent("hello world"))
        assertEquals("%2A", WbiSigner.encodeComponent("*"))
        assertEquals("~", WbiSigner.encodeComponent("~"))
    }

    @Test
    fun `从 nav 的 img_url 抠出密钥`() {
        val url = "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png"
        assertEquals("7cd084941338484aae1ad9425b84077c", WbiSigner.extractKey(url))
    }

    @Test
    fun `签名产物包含必需字段`() {
        val signed = WbiSigner.sign(mapOf("bvid" to "BV1xx411c7mD"), imgKey, subKey)
        assertTrue(signed.containsKey("w_rid"))
        assertTrue(signed.containsKey("wts"))
        assertEquals(32, signed.getValue("w_rid").length)
    }
}
