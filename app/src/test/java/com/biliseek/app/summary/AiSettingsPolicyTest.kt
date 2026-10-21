package com.biliseek.app.summary

import com.biliseek.core.ai.AiError
import com.biliseek.core.ai.ConfigErrorKind
import com.biliseek.core.ai.SummaryStyle
import com.biliseek.core.ai.provider.AiEndpointConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工单 AI-3：设置页的纯函数。 */
class AiSettingsPolicyTest {

    private val draft = EndpointDraft("minimax_cn", "https://api.minimaxi.com/v1", "MiniMax-M2.7", "sk-abcdefgh1234")

    @Test
    fun `切换预设带出地址 自定义保留已填地址`() {
        assertEquals("https://api.minimax.io/v1", AiSettingsPolicy.baseUrlForPreset("minimax_global", "x"))
        assertEquals("https://my.host/v1", AiSettingsPolicy.baseUrlForPreset("custom_openai", "https://my.host/v1"))
        assertEquals("keep", AiSettingsPolicy.baseUrlForPreset("unknown", "keep"))
    }

    @Test
    fun `模型下拉优先用当前地址拉到的列表 否则用预设内置备选`() {
        val loaded = ModelListState.Loaded("https://my.host/v1", listOf("a", "b"))
        assertEquals(listOf("a", "b"), AiSettingsPolicy.modelChoices("custom_openai", " https://my.host/v1 ", loaded))
        // 地址改了就不再用旧列表
        assertEquals(emptyList<String>(), AiSettingsPolicy.modelChoices("custom_openai", "https://other/v1", loaded))
        val minimax = AiSettingsPolicy.modelChoices("minimax_cn", "https://api.minimaxi.com/v1", ModelListState.Idle)
        assertTrue("MiniMax-M2.7" in minimax)
        assertEquals(emptyList<String>(), AiSettingsPolicy.modelChoices("unknown", "x", ModelListState.Idle))
    }

    @Test
    fun `模型下拉按输入筛选 空输入或正好选中时全部显示`() {
        val models = listOf("deepseek-chat", "deepseek-reasoner", "qwen-max")
        assertEquals(models, AiSettingsPolicy.filterModels(models, " "))
        assertEquals(models, AiSettingsPolicy.filterModels(models, "qwen-max"))
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), AiSettingsPolicy.filterModels(models, "DeepSeek"))
        assertEquals(emptyList<String>(), AiSettingsPolicy.filterModels(models, "gpt"))
    }

    @Test
    fun `拉取模型列表 404 同时提示地址错或不支持`() {
        val wrong = AiSettingsPolicy.describeModelListError(AiError.Config(ConfigErrorKind.WRONG_ENDPOINT), "minimax_cn")
        assertTrue("不支持获取列表" in wrong)
        val key = AiSettingsPolicy.describeModelListError(AiError.Config(ConfigErrorKind.INVALID_KEY), "custom_openai")
        assertEquals("没有拿到模型列表：API Key 无效", key)
    }

    @Test
    fun `草稿 Key 为空时用已保存的 都没有返回 null`() {
        assertEquals("sk-abcdefgh1234", AiSettingsPolicy.resolve(draft, "saved")!!.apiKey)
        assertEquals("saved", AiSettingsPolicy.resolve(draft.copy(apiKey = " "), "saved")!!.apiKey)
        assertNull(AiSettingsPolicy.resolve(draft.copy(apiKey = ""), null))
    }

    @Test
    fun `保存前校验`() {
        assertNull(AiSettingsPolicy.validate(draft, hasSavedKey = false))
        assertEquals("请填写接口地址", AiSettingsPolicy.validate(draft.copy(baseUrl = ""), false))
        assertEquals("接口地址应以 https:// 开头", AiSettingsPolicy.validate(draft.copy(baseUrl = "api.x.com"), false))
        assertEquals("请填写模型名", AiSettingsPolicy.validate(draft.copy(model = " "), false))
        assertEquals("请填写 API Key", AiSettingsPolicy.validate(draft.copy(apiKey = ""), false))
        assertNull(AiSettingsPolicy.validate(draft.copy(apiKey = ""), hasSavedKey = true))
    }

    @Test
    fun `配置指纹 不含明文 随任一字段变化`() {
        val config = AiEndpointConfig("minimax_cn", "https://a/v1", "m", "sk-secret-9999")
        val fp = AiSettingsPolicy.fingerprint(config)
        assertEquals(16, fp.length)
        assertFalse(fp.contains("secret"))
        assertEquals(fp, AiSettingsPolicy.fingerprint(config.copy(baseUrl = "https://a/v1/")))
        assertNotEquals(fp, AiSettingsPolicy.fingerprint(config.copy(apiKey = "sk-secret-9998")))
        assertNotEquals(fp, AiSettingsPolicy.fingerprint(config.copy(model = "m2")))
    }

    @Test
    fun `Key 末四位提示 过短不提示`() {
        assertEquals("1234", AiSettingsPolicy.keyHint("sk-abcdefgh1234"))
        assertEquals("", AiSettingsPolicy.keyHint("short"))
    }

    @Test
    fun `实测 RTF 滑动平均`() {
        assertEquals(0.06f, AiSettingsPolicy.blendRtf(null, 0.06f), 1e-6f)
        assertEquals(0.07f + 0.03f, AiSettingsPolicy.blendRtf(0.1f, 0.1f), 1e-6f)
    }

    @Test
    fun `风格选项映射 原文笔记不需要大模型`() {
        assertEquals(SummaryStyle.Custom("p"), StyleChoice.CUSTOM.toStyle("p"))
        assertEquals(SummaryStyle.TranscriptNote, StyleChoice.TRANSCRIPT_NOTE.toStyle(""))
        assertFalse(StyleChoice.TRANSCRIPT_NOTE.needsLlm)
        assertTrue(StyleChoice.BRIEF.needsLlm)
    }

    @Test
    fun `配置是否完整`() {
        val ready = EndpointView("minimax_cn", "https://a", "m", keyConfigured = true, keyHint = "1234")
        assertTrue(AiSettings(primary = ready).primaryReady)
        assertFalse(AiSettings(primary = ready.copy(keyConfigured = false)).primaryReady)
        assertFalse(AiSettings(primary = ready.copy(model = "")).primaryReady)
        assertFalse(AiSettings().primaryReady)
        assertFalse(AiSettings(primary = ready).backupReady)
    }
}

class AiErrorTextTest {

    @Test
    fun `配置错误说清楚错在哪`() {
        assertEquals("API Key 无效", AiErrorText.describe(AiError.Config(ConfigErrorKind.INVALID_KEY)))
        assertTrue(AiErrorText.describe(AiError.Config(ConfigErrorKind.REGION_MISMATCH)).contains("国内版"))
        assertEquals("当前套餐不包含这个模型", AiErrorText.describe(AiError.Config(ConfigErrorKind.MODEL_NOT_IN_PLAN)))
    }

    @Test
    fun `额度带厂商码`() {
        assertEquals("额度已用尽（错误码 1028）", AiErrorText.describe(AiError.QuotaExceeded(1028)))
        assertEquals("额度已用尽", AiErrorText.describe(AiError.QuotaExceeded(null)))
    }

    @Test
    fun `自定义预设的异常响应补充常见原因`() {
        assertTrue(AiErrorText.describe(AiError.BadResponse("x"), "custom_openai").contains("需要登录"))
        assertFalse(AiErrorText.describe(AiError.BadResponse("x"), "minimax_cn").contains("需要登录"))
    }
}
