package com.biliseek.core.ai

import com.biliseek.core.ai.internal.AiException
import com.biliseek.core.ai.internal.businessError
import com.biliseek.core.ai.internal.classifyException
import com.biliseek.core.ai.internal.classifyHttpError
import com.biliseek.core.ai.internal.parseJsonOrNull
import com.biliseek.core.ai.internal.parseModelIds
import com.biliseek.core.ai.internal.parseRetryAfterMs
import com.biliseek.core.ai.provider.AiEndpointConfig
import com.biliseek.core.ai.provider.AiTimeouts
import com.biliseek.core.ai.provider.ProviderCatalog
import com.biliseek.core.ai.provider.isolatedForAi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 「获取模型列表」的结果。 */
sealed interface ModelListResult {
    /** 模型名已去重、按名排序；可能为空。 */
    data class Ok(val models: List<String>) : ModelListResult
    data class Failed(val error: AiError) : ModelListResult
}

/**
 * 设置页「获取模型列表」：OpenAI 兼容 `GET {baseUrl}/models`。不需要已填模型名。
 *
 * 只在用户点击时调用，绝不在后台或输入过程中自动调用。
 */
object AiModelLister {

    suspend fun list(config: AiEndpointConfig, http: OkHttpClient): ModelListResult =
        list(config, http, AiTimeouts())

    internal suspend fun list(config: AiEndpointConfig, http: OkHttpClient, timeouts: AiTimeouts): ModelListResult {
        when {
            config.baseUrl.isBlank() -> return ModelListResult.Failed(AiError.Config(ConfigErrorKind.WRONG_ENDPOINT))
            config.apiKey.isBlank() -> return ModelListResult.Failed(AiError.Config(ConfigErrorKind.INVALID_KEY))
        }
        return try {
            ModelListResult.Ok(fetch(config, http.isolatedForAi(timeouts), timeouts))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ModelListResult.Failed(classifyException(e))
        }
    }

    private suspend fun fetch(config: AiEndpointConfig, http: OkHttpClient, timeouts: AiTimeouts): List<String> =
        withContext(Dispatchers.IO) {
            val url = "${config.baseUrl.trim().trimEnd('/')}/models".toHttpUrlOrNull()
                ?: throw AiException(AiError.Config(ConfigErrorKind.WRONG_ENDPOINT))
            val preset = ProviderCatalog.byId(config.presetId)
            val call = http.newCall(
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .header("Accept", "application/json")
                    .get()
                    .build(),
            )
            call.timeout().timeout(timeouts.callMs, TimeUnit.MILLISECONDS)
            call.execute().use { resp ->
                val raw = resp.body.string()
                val json = parseJsonOrNull(raw)
                if (!resp.isSuccessful) {
                    throw AiException(
                        classifyHttpError(resp.code, json, raw, preset, parseRetryAfterMs(resp.header("Retry-After"))),
                    )
                }
                json ?: throw AiException(AiError.BadResponse("服务返回的不是 JSON（地址可能不对，或当前网络需要登录）"))
                businessError(json, preset)?.let { throw AiException(it) }
                parseModelIds(json) ?: throw AiException(AiError.BadResponse("响应里没有模型列表"))
            }
        }
}
