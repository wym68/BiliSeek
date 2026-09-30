package com.biliseek.app.summary

import com.biliseek.core.ai.AiError
import com.biliseek.core.ai.ConfigErrorKind
import com.biliseek.core.ai.SummaryStyle
import com.biliseek.core.ai.provider.AiEndpointConfig
import com.biliseek.core.ai.provider.ProviderCatalog
import java.security.MessageDigest

/** 总结模型 / 备用模型（工单 §3.7.5）。 */
enum class EndpointSlot { PRIMARY, BACKUP }

/** 设置页给界面看的端点信息：**不含 Key 明文**，只有是否已配置与末 4 位。 */
data class EndpointView(
    val presetId: String,
    val baseUrl: String,
    val model: String,
    val keyConfigured: Boolean,
    val keyHint: String,
)

/** 设置页正在编辑的草稿。[apiKey] 为空表示「沿用已保存的 Key」。 */
data class EndpointDraft(
    val presetId: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)

/** 风格弹窗的选项，同时是「上次用的风格」的持久化值。 */
enum class StyleChoice(val label: String) {
    BRIEF("精简"),
    DETAILED("详细笔记"),
    OUTLINE("时间戳大纲"),
    CUSTOM("自定义"),
    TRANSCRIPT_NOTE("原文笔记（不调用 AI）"),
    ;

    fun toStyle(customPrompt: String): SummaryStyle = when (this) {
        BRIEF -> SummaryStyle.Brief
        DETAILED -> SummaryStyle.DetailedNotes
        OUTLINE -> SummaryStyle.TimestampOutline
        CUSTOM -> SummaryStyle.Custom(customPrompt)
        TRANSCRIPT_NOTE -> SummaryStyle.TranscriptNote
    }

    /** 是否需要调用大模型（原文笔记不需要）。 */
    val needsLlm: Boolean get() = this != TRANSCRIPT_NOTE
}

data class AiSettings(
    val primary: EndpointView? = null,
    val backup: EndpointView? = null,
    /** 默认选择里的风格；[defaultOfficial] 为 true 但视频没有官方总结时也用它。 */
    val lastStyle: StyleChoice = StyleChoice.OUTLINE,
    /** 默认选择是「B 站总结」：弹窗里官方总结可用时默认选它，不可用时退回 [lastStyle]。 */
    val defaultOfficial: Boolean = false,
    val customPrompt: String = "",
    val privacyAccepted: Boolean = false,
    /** 最近几次本地识别实测 RTF 的滑动平均（AI-8）。 */
    val asrRtf: Float? = null,
) {
    fun endpoint(slot: EndpointSlot): EndpointView? = if (slot == EndpointSlot.PRIMARY) primary else backup

    /** 总结模型已完整配置（地址、模型名、Key 都有）。 */
    val primaryReady: Boolean
        get() = primary?.let { it.baseUrl.isNotBlank() && it.model.isNotBlank() && it.keyConfigured } == true

    val backupReady: Boolean
        get() = backup?.let { it.baseUrl.isNotBlank() && it.model.isNotBlank() && it.keyConfigured } == true
}

/** 设置相关的纯函数（JVM 单测）。 */
object AiSettingsPolicy {

    /** 切换预设时带出的地址：自定义预设保留用户已填的地址。 */
    fun baseUrlForPreset(presetId: String, currentBaseUrl: String): String {
        val preset = ProviderCatalog.byId(presetId) ?: return currentBaseUrl
        return preset.baseUrl.ifBlank { currentBaseUrl }
    }

    /**
     * 草稿 + 已保存的 Key → 可用于请求的配置。草稿 Key 为空时用已保存的；两者都没有返回 null。
     */
    fun resolve(draft: EndpointDraft, savedKey: String?): AiEndpointConfig? {
        val key = draft.apiKey.trim().ifBlank { savedKey.orEmpty() }
        if (key.isBlank()) return null
        return AiEndpointConfig(
            presetId = draft.presetId,
            baseUrl = draft.baseUrl.trim(),
            model = draft.model.trim(),
            apiKey = key,
        )
    }

    /** 保存前的本地校验。返回 null = 可以保存。 */
    fun validate(draft: EndpointDraft, hasSavedKey: Boolean): String? = when {
        draft.baseUrl.isBlank() -> "请填写接口地址"
        !draft.baseUrl.trim().let { it.startsWith("https://") || it.startsWith("http://") } -> "接口地址应以 https:// 开头"
        draft.model.isBlank() -> "请填写模型名"
        draft.apiKey.isBlank() && !hasSavedKey -> "请填写 API Key"
        else -> null
    }

    /**
     * 配置指纹（工单 §3.7.3）：预设 + 地址 + 模型 + Key 的 SHA-256 前 16 位。
     * 用来记住「这套配置验证通过过」，不存 Key 明文。
     */
    fun fingerprint(config: AiEndpointConfig): String {
        val md = MessageDigest.getInstance("SHA-256")
        listOf(config.presetId, config.baseUrl.trimEnd('/'), config.model, config.apiKey).forEach {
            md.update(it.toByteArray(Charsets.UTF_8))
            md.update(0)
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /** `sk-abcd1234` → `1234`。 */
    fun keyHint(key: String): String = if (key.length <= 8) "" else key.takeLast(4)

    /** 新的 RTF 并入滑动平均（新值权重 0.3）。 */
    fun blendRtf(previous: Float?, measured: Float): Float =
        if (previous == null) measured else previous * 0.7f + measured * 0.3f
}

/** [AiError] → 给用户看的一句话（通知、设置页测试结果、阅读页失败横幅共用）。 */
object AiErrorText {
    fun describe(error: AiError, presetId: String? = null): String = when (error) {
        AiError.Offline -> "网络不可用"
        is AiError.Network -> "网络异常：${error.message}"
        is AiError.Server -> "AI 服务暂时不可用（HTTP ${error.code}）"
        is AiError.RateLimited -> "请求太频繁，被 AI 服务限流了"
        is AiError.QuotaExceeded -> "额度已用尽" + (error.vendorCode?.let { "（错误码 $it）" } ?: "")
        is AiError.Config -> when (error.kind) {
            ConfigErrorKind.INVALID_KEY -> "API Key 无效"
            ConfigErrorKind.WRONG_ENDPOINT -> "接口地址不对"
            ConfigErrorKind.MODEL_NOT_FOUND -> "模型名不存在或填写有误"
            ConfigErrorKind.MODEL_NOT_IN_PLAN -> "当前套餐不包含这个模型"
            ConfigErrorKind.REGION_MISMATCH ->
                "API Key 无效，或与所选区域不匹配（国内版 Key 只能用国内域名，国际版同理）"
        }
        is AiError.ContentFiltered -> "内容被 AI 服务的审核拦截"
        AiError.ContextTooLong -> "原文太长，超出了模型的处理上限"
        AiError.Truncated -> "模型输出被截断"
        is AiError.BadResponse -> "AI 服务返回了异常内容：${error.message}"
        AiError.StreamInterrupted -> "生成过程中连接中断"
        AiError.Cancelled -> "已取消"
    }.let { text ->
        // 自定义预设遇到非 JSON 时补一句最常见的原因
        if (error is AiError.BadResponse && presetId == ProviderCatalog.CUSTOM_OPENAI.id) {
            "$text（接口地址可能不对，或当前网络需要登录）"
        } else {
            text
        }
    }
}
