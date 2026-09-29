package com.biliseek.core.ai.provider.openai

import com.biliseek.core.ai.AiError
import com.biliseek.core.ai.ConfigErrorKind
import com.biliseek.core.ai.internal.AiException
import com.biliseek.core.ai.internal.SSE_DONE
import com.biliseek.core.ai.internal.SseParser
import com.biliseek.core.ai.internal.businessError
import com.biliseek.core.ai.internal.classifyException
import com.biliseek.core.ai.internal.classifyHttpError
import com.biliseek.core.ai.internal.isTemperatureRejection
import com.biliseek.core.ai.internal.parseJsonOrNull
import com.biliseek.core.ai.internal.parseRetryAfterMs
import com.biliseek.core.ai.provider.AiEndpointConfig
import com.biliseek.core.ai.provider.AiTimeouts
import com.biliseek.core.ai.provider.ChatCapabilities
import com.biliseek.core.ai.provider.ChatClient
import com.biliseek.core.ai.provider.ChatRequest
import com.biliseek.core.ai.provider.ChatResult
import com.biliseek.core.ai.provider.ChatStreamEvent
import com.biliseek.core.ai.provider.ProviderPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI 兼容 `POST {baseUrl}/chat/completions`（MiniMax、Kimi 开放平台、DeepSeek、通义、Ollama…）。
 *
 * - 失败一律抛 [AiException]，分类见 `AiErrorPolicy`；**2xx 也要看响应体**（MiniMax `base_resp`）。
 * - 不做重试（除了「temperature 不被接受 → 去掉重发一次」这种参数兼容）；瞬时错误的重试由上层按步骤做，
 *   这样续做粒度清楚。
 * - 思考内容原样透出（`Content` 可能含 `<think>`，独立字段化作 `Thinking` 心跳），由调用方按预设剥离。
 */
internal class OpenAiChatClient(
    private val config: AiEndpointConfig,
    private val preset: ProviderPreset?,
    private val capabilities: ChatCapabilities,
    private val http: OkHttpClient,
    private val timeouts: AiTimeouts,
) : ChatClient {

    /** 该服务拒收 temperature 后在本客户端生命周期内（= 一次任务）不再发送。 */
    private val temperatureDisabled = AtomicBoolean(!capabilities.acceptsTemperature)

    override suspend fun complete(request: ChatRequest): ChatResult = withContext(Dispatchers.IO) {
        val response = executeWithTemperatureFallback(request, stream = false) { call ->
            call.timeout().timeout(timeouts.callMs, TimeUnit.MILLISECONDS)
        }
        response.use { resp ->
            val raw = resp.body.string()
            val json = parseJsonOrNull(raw)
                ?: throw AiException(AiError.BadResponse("服务返回的不是 JSON（地址可能不对，或当前网络需要登录）"))
            businessError(json, preset)?.let { throw AiException(it) }
            val choice = json.firstChoice()
                ?: throw AiException(AiError.BadResponse("响应里没有 choices"))
            val message = choice["message"] as? JsonObject
            ChatResult(
                content = message?.string("content").orEmpty(),
                finishReason = choice.string("finish_reason"),
            )
        }
    }

    override fun stream(request: ChatRequest): Flow<ChatStreamEvent> = channelFlow {
        val deadline = AtomicLong(System.nanoTime() + ms(timeouts.firstEventMs))
        val timedOut = AtomicBoolean(false)
        var sawEvent = false
        val callRef = AtomicReference<Call?>(null)

        // 看门狗：超过期限没有新事件就掐断连接（阻塞读不响应协程取消，只能 cancel 掉 call）
        val watchdog = launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                if (System.nanoTime() > deadline.get()) {
                    timedOut.set(true)
                    callRef.get()?.cancel()
                    break
                }
            }
        }
        // 下游取消时同样掐断连接
        coroutineContext.job.invokeOnCompletion { callRef.get()?.cancel() }

        try {
            withContext(Dispatchers.IO) {
                executeWithTemperatureFallback(request, stream = true) { callRef.set(it) }.use { resp ->
                    val source = resp.body.source()
                    val parser = SseParser()
                    var finishReason: String? = null
                    var done = false
                    while (!done) {
                        val line = source.readUtf8Line() ?: break
                        val data = parser.feedLine(line) ?: continue
                        sawEvent = true
                        deadline.set(System.nanoTime() + ms(timeouts.idleMs))
                        if (data.trim() == SSE_DONE) {
                            done = true
                        } else {
                            finishReason = handleStreamEvent(data) { send(it) } ?: finishReason
                        }
                    }
                    if (!done) {
                        parser.finish()?.takeIf { it.trim() != SSE_DONE }?.let { data ->
                            finishReason = handleStreamEvent(data) { send(it) } ?: finishReason
                        }
                    }
                    // 没有 [DONE] 也没有 finish_reason 就断了：视为中途断开
                    if (!done && finishReason == null) throw AiException(AiError.StreamInterrupted)
                    send(ChatStreamEvent.Finished(finishReason))
                }
            }
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            throw AiException(
                when {
                    timedOut.get() && sawEvent -> AiError.StreamInterrupted
                    timedOut.get() -> AiError.Network("等待响应超时")
                    sawEvent -> AiError.StreamInterrupted
                    else -> classifyException(e)
                },
                e,
            )
        } finally {
            watchdog.cancel()
        }
    }

    /** 解析一个流式事件并发出；返回其中的 finish_reason（如有）。 */
    private suspend fun handleStreamEvent(
        data: String,
        emit: suspend (ChatStreamEvent) -> Unit,
    ): String? {
        val json = parseJsonOrNull(data)
            ?: throw AiException(AiError.BadResponse("流式事件不是 JSON"))
        businessError(json, preset)?.let { throw AiException(it) }
        val choice = json.firstChoice() ?: return null // 例如只带 usage 的收尾事件
        val delta = choice["delta"] as? JsonObject
        delta?.string("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { emit(ChatStreamEvent.Thinking) }
        delta?.string("content")?.takeIf { it.isNotEmpty() }?.let { emit(ChatStreamEvent.Content(it)) }
        return choice.string("finish_reason")
    }

    /** 发请求；遇「temperature 不被接受」去掉该参数重发一次。非 2xx 抛 [AiException]。 */
    private fun executeWithTemperatureFallback(
        request: ChatRequest,
        stream: Boolean,
        configure: (Call) -> Unit,
    ): Response {
        val url = endpointUrl() ?: throw AiException(AiError.Config(ConfigErrorKind.WRONG_ENDPOINT))
        var allowTemperatureRetry = true
        while (true) {
            val sendTemperature = request.temperature != null && !temperatureDisabled.get()
            val call = http.newCall(buildHttpRequest(url, request, stream, sendTemperature))
            configure(call)
            val response = try {
                call.execute()
            } catch (e: IOException) {
                throw AiException(classifyException(e), e)
            }
            if (response.isSuccessful) return response
            val raw = response.use { it.body.string() }
            if (sendTemperature && allowTemperatureRetry && isTemperatureRejection(response.code, raw)) {
                temperatureDisabled.set(true)
                allowTemperatureRetry = false
                continue
            }
            throw AiException(
                classifyHttpError(
                    httpCode = response.code,
                    json = parseJsonOrNull(raw),
                    rawBody = raw,
                    preset = preset,
                    retryAfterMs = parseRetryAfterMs(response.header("Retry-After")),
                ),
            )
        }
    }

    private fun endpointUrl(): HttpUrl? =
        "${config.baseUrl.trim().trimEnd('/')}/chat/completions".toHttpUrlOrNull()

    private fun buildHttpRequest(
        url: HttpUrl,
        request: ChatRequest,
        stream: Boolean,
        sendTemperature: Boolean,
    ): Request {
        val body = buildJsonObject {
            put("model", config.model)
            putJsonArray("messages") {
                request.messages.forEach { m ->
                    addJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    }
                }
            }
            put("stream", stream)
            if (sendTemperature) put("temperature", request.temperature)
            request.maxTokens?.let { put("max_tokens", it) }
            capabilities.extraBody.forEach { (k, v) -> put(k, v) }
        }
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val WATCHDOG_TICK_MS = 100L
        fun ms(value: Long) = TimeUnit.MILLISECONDS.toNanos(value)
    }
}

private fun JsonObject.firstChoice(): JsonObject? = (this["choices"] as? JsonArray)?.firstOrNull() as? JsonObject

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
