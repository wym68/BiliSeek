package com.biliseek.core.ai.provider

import kotlinx.serialization.json.JsonElement

/**
 * 一家 AI 服务 = 协议（怎么说话）× 预设（在哪、叫什么、有何怪癖）。
 *
 * 加一家已支持协议的服务 = 只在 [ProviderCatalog] 加一条预设；
 * 加一种新协议 = 新枚举值 + 适配器 + ProviderFactory 一行 + 预设（工单 §3.4）。
 */
enum class ChatProtocol {
    OPENAI_COMPAT,
    // ANTHROPIC, GEMINI —— 预留
}

/** 推理模型的「思考内容」长在哪。决定 ReasoningStripPolicy 怎么剥。 */
enum class ReasoningFormat { NONE, THINK_TAG_IN_CONTENT, SEPARATE_FIELD, AUTO }

/** 一家服务在某协议下的已知怪癖。全部有保守默认值，预设只覆盖差异。 */
data class ChatCapabilities(
    val streaming: Boolean = true,
    val reasoningFormat: ReasoningFormat = ReasoningFormat.AUTO,
    /** 部分推理模型拒收 temperature；为 false 时请求里不带。 */
    val acceptsTemperature: Boolean = true,
    /** 单次请求正文字节上限（保守估计，用于分块）。 */
    val maxRequestBytes: Int = 96 * 1024,
    /** 额外固定请求参数，原样并入 body。 */
    val extraBody: Map<String, JsonElement> = emptyMap(),
)

/** 厂商错误码能映射到的类别；AiErrorPolicy 据此构造具体 AiError（工单 §3.7.1）。 */
enum class VendorErrorKind { QUOTA, MODEL_NOT_IN_PLAN, CONTENT_FILTERED, RATE_LIMITED, INVALID_KEY }

/** 一个可选的服务预设。只是「帮你填好」，baseUrl 与模型名在设置页都可覆盖。 */
data class ProviderPreset(
    /** 稳定 id，落库用；改名不改 id。 */
    val id: String,
    val displayName: String,
    /** 自定义预设为空，由用户填。 */
    val baseUrl: String,
    val chatProtocol: ChatProtocol = ChatProtocol.OPENAI_COMPAT,
    val chatCapabilities: ChatCapabilities = ChatCapabilities(),
    /** 设置页「去哪拿 Key」。 */
    val docsUrl: String = "",
    /** 厂商错误码 → 分类。HTTP 200 + 业务错误码的厂商（MiniMax）靠它识别失败。 */
    val errorCodeMap: Map<Int, VendorErrorKind> = emptyMap(),
    /** 该预设下 401 时额外提示「Key 与域名的区域不匹配」。 */
    val regionSensitive: Boolean = false,
    /** 设置页模型下拉的内置备选；服务不支持 `GET /models` 时靠它选。 */
    val knownModels: List<String> = emptyList(),
)

/**
 * 用户对「总结模型」（或备用模型）的最终配置 = 预设 id + 覆盖项。
 *
 * 存预设 id 而不是展开后的全部字段：以后修正了预设的能力值，老用户自动受益；
 * 用户显式填写的 baseUrl / model 以用户为准。
 */
data class AiEndpointConfig(
    val presetId: String,
    val baseUrl: String,
    /** 必填，无默认。 */
    val model: String,
    /** 运行期明文，仅在内存；落盘为 Keystore 密文（:app 负责）。 */
    val apiKey: String,
    /** 高级选项，v1 UI 不暴露。 */
    val capabilityOverrides: ChatCapabilities? = null,
) {
    /** baseUrl、model、Key 都已填写。 */
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    /** Key 永不进日志：toString 只露末 4 位。 */
    override fun toString(): String =
        "AiEndpointConfig(presetId=$presetId, baseUrl=$baseUrl, model=$model, apiKey=${maskKey(apiKey)})"
}

/** `sk-abcdef1234` → `****1234`；过短的 Key 全部打码。 */
internal fun maskKey(key: String): String =
    if (key.length <= 8) "****" else "****" + key.takeLast(4)

object ProviderCatalog {

    /**
     * MiniMax 业务错误码。来源：MiniMax 官方 CLI（MiniMax-AI/cli `src/errors/api.ts`）与其错误码说明，
     * **未经真机核对**（工单 §3.7.1）。1002 / 1039 在官方 CLI 里与审核拦截同一分支，
     * 但按错误码说明更像限流；按限流处理——误判的代价只是多重试几次，反之会把限流错报成「内容被拦截」。
     */
    /** 来源：MiniMax 开放平台「OpenAI 兼容」文档（2026-10），**未经真机核对**。 */
    private val MINIMAX_MODELS = listOf("MiniMax-M3", "MiniMax-M3.1-Flash", "MiniMax-M2.7", "MiniMax-M2.7-highspeed")

    private val MINIMAX_ERROR_CODES: Map<Int, VendorErrorKind> = mapOf(
        1002 to VendorErrorKind.RATE_LIMITED,
        1039 to VendorErrorKind.RATE_LIMITED,
        1004 to VendorErrorKind.INVALID_KEY,
        2049 to VendorErrorKind.INVALID_KEY,
        1008 to VendorErrorKind.QUOTA,
        1028 to VendorErrorKind.QUOTA,
        1030 to VendorErrorKind.QUOTA,
        2061 to VendorErrorKind.MODEL_NOT_IN_PLAN,
        1026 to VendorErrorKind.CONTENT_FILTERED,
        1027 to VendorErrorKind.CONTENT_FILTERED,
    )

    val MINIMAX_CN = ProviderPreset(
        id = "minimax_cn",
        displayName = "MiniMax（国内版）",
        baseUrl = "https://api.minimaxi.com/v1",
        docsUrl = "https://platform.minimaxi.com",
        errorCodeMap = MINIMAX_ERROR_CODES,
        regionSensitive = true,
        knownModels = MINIMAX_MODELS,
    )

    val MINIMAX_GLOBAL = ProviderPreset(
        id = "minimax_global",
        displayName = "MiniMax（国际版）",
        baseUrl = "https://api.minimax.io/v1",
        docsUrl = "https://platform.minimax.io",
        errorCodeMap = MINIMAX_ERROR_CODES,
        regionSensitive = true,
        knownModels = MINIMAX_MODELS,
    )

    /** 任意 OpenAI 兼容服务（Kimi 开放平台、DeepSeek、通义、Ollama…），地址由用户填。 */
    val CUSTOM_OPENAI = ProviderPreset(
        id = "custom_openai",
        displayName = "自定义（OpenAI 兼容）",
        baseUrl = "",
    )

    val chat: List<ProviderPreset> = listOf(MINIMAX_CN, MINIMAX_GLOBAL, CUSTOM_OPENAI)

    fun byId(id: String): ProviderPreset? = chat.firstOrNull { it.id == id }

    /** 实际生效的能力：用户覆盖 > 预设 > 默认。未知预设 id 按默认能力处理。 */
    fun capabilitiesFor(config: AiEndpointConfig): ChatCapabilities =
        config.capabilityOverrides ?: byId(config.presetId)?.chatCapabilities ?: ChatCapabilities()
}
