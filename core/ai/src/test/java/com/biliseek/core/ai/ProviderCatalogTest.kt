package com.biliseek.core.ai

import com.biliseek.core.ai.provider.AiEndpointConfig
import com.biliseek.core.ai.provider.ChatCapabilities
import com.biliseek.core.ai.provider.ProviderCatalog
import com.biliseek.core.ai.provider.ReasoningFormat
import com.biliseek.core.ai.provider.VendorErrorKind
import com.biliseek.core.ai.provider.maskKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 预设目录与端点配置（工单 §3.2 / §3.3）。 */
class ProviderCatalogTest {

    private fun config(
        presetId: String = "minimax_cn",
        baseUrl: String = "https://api.minimaxi.com/v1",
        model: String = "MiniMax-M2.7",
        apiKey: String = "sk-api-abcdefgh12345678",
        overrides: ChatCapabilities? = null,
    ) = AiEndpointConfig(presetId, baseUrl, model, apiKey, overrides)

    @Test
    fun `预设 id 唯一且可按 id 找回`() {
        val ids = ProviderCatalog.chat.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ProviderCatalog.chat.forEach { assertEquals(it, ProviderCatalog.byId(it.id)) }
        assertNull(ProviderCatalog.byId("no_such_preset"))
    }

    @Test
    fun `MiniMax 国内与国际走各自域名且都标记区域敏感`() {
        assertEquals("https://api.minimaxi.com/v1", ProviderCatalog.MINIMAX_CN.baseUrl)
        assertEquals("https://api.minimax.io/v1", ProviderCatalog.MINIMAX_GLOBAL.baseUrl)
        assertTrue(ProviderCatalog.MINIMAX_CN.regionSensitive)
        assertTrue(ProviderCatalog.MINIMAX_GLOBAL.regionSensitive)
        assertFalse(ProviderCatalog.CUSTOM_OPENAI.regionSensitive)
    }

    @Test
    fun `自定义预设不带地址也不带厂商错误码`() {
        assertEquals("", ProviderCatalog.CUSTOM_OPENAI.baseUrl)
        assertTrue(ProviderCatalog.CUSTOM_OPENAI.errorCodeMap.isEmpty())
    }

    @Test
    fun `MiniMax 额度与套餐错误码有映射`() {
        val codes = ProviderCatalog.MINIMAX_CN.errorCodeMap
        assertEquals(VendorErrorKind.QUOTA, codes[1028])
        assertEquals(VendorErrorKind.QUOTA, codes[1030])
        assertEquals(VendorErrorKind.MODEL_NOT_IN_PLAN, codes[2061])
        assertEquals(VendorErrorKind.CONTENT_FILTERED, codes[1027])
    }

    @Test
    fun `能力取值 用户覆盖优先 其次预设 未知预设用默认`() {
        val custom = ChatCapabilities(reasoningFormat = ReasoningFormat.NONE, maxRequestBytes = 1024)
        assertEquals(custom, ProviderCatalog.capabilitiesFor(config(overrides = custom)))
        assertEquals(
            ProviderCatalog.MINIMAX_CN.chatCapabilities,
            ProviderCatalog.capabilitiesFor(config()),
        )
        assertEquals(ChatCapabilities(), ProviderCatalog.capabilitiesFor(config(presetId = "gone")))
    }

    @Test
    fun `配置不完整的判定`() {
        assertTrue(config().isComplete)
        assertFalse(config(model = " ").isComplete)
        assertFalse(config(apiKey = "").isComplete)
        assertFalse(config(baseUrl = "").isComplete)
    }

    @Test
    fun `toString 不泄露 Key`() {
        val text = config(apiKey = "sk-api-SECRETSECRET9876").toString()
        assertFalse(text.contains("SECRET"))
        assertTrue(text.contains("****9876"))
    }

    @Test
    fun `过短的 Key 全部打码`() {
        assertEquals("****", maskKey("abc"))
        assertEquals("****", maskKey("12345678"))
        assertEquals("****6789", maskKey("123456789"))
    }
}
