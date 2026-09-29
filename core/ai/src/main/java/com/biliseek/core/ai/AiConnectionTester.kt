package com.biliseek.core.ai

import com.biliseek.core.ai.internal.classifyException
import com.biliseek.core.ai.provider.AiEndpointConfig
import com.biliseek.core.ai.provider.AiTimeouts
import com.biliseek.core.ai.provider.ChatMessage
import com.biliseek.core.ai.provider.ChatRequest
import com.biliseek.core.ai.provider.ProviderFactory
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

/**
 * 「测试连接」与任务开始前的预检（工单 §3.7.3）：发一条极短消息，能拿到可解析的 2xx 就算通过。
 *
 * 只在用户点击或任务开始时调用，绝不在后台定时调用。
 */
object AiConnectionTester {

    /** @return null = 配置可用；否则为具体原因（Key 无效 / 地址错 / 模型不存在 / 网络…）。 */
    suspend fun test(config: AiEndpointConfig, http: OkHttpClient): AiError? =
        test(config, http, AiTimeouts())

    internal suspend fun test(config: AiEndpointConfig, http: OkHttpClient, timeouts: AiTimeouts): AiError? {
        incompleteReason(config)?.let { return it }
        return try {
            ProviderFactory.chat(config, http, timeouts).complete(
                ChatRequest(messages = listOf(ChatMessage("user", "ping")), maxTokens = PING_MAX_TOKENS),
            )
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            classifyException(e)
        }
    }

    /** 本地就能判断的缺项，不必发请求。 */
    internal fun incompleteReason(config: AiEndpointConfig): AiError? = when {
        config.baseUrl.isBlank() -> AiError.Config(ConfigErrorKind.WRONG_ENDPOINT)
        config.apiKey.isBlank() -> AiError.Config(ConfigErrorKind.INVALID_KEY)
        config.model.isBlank() -> AiError.Config(ConfigErrorKind.MODEL_NOT_FOUND)
        else -> null
    }

    private const val PING_MAX_TOKENS = 32
}
