package com.biliseek.core.ai.internal

import com.biliseek.core.ai.AiError
import com.biliseek.core.ai.ConfigErrorKind
import com.biliseek.core.ai.provider.ProviderPreset
import com.biliseek.core.ai.provider.VendorErrorKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.random.Random

/** 调用链内部传递 [AiError] 的异常。只在本模块内抛出与捕获。 */
internal class AiException(val error: AiError, cause: Throwable? = null) :
    Exception(error.toString(), cause)

/** 从错误响应体里抽出的结构化信息。兼容 OpenAI 风格 `error{}` 与 MiniMax 风格 `base_resp{}`。 */
internal data class ErrorBody(
    val vendorCode: Int?,
    val type: String?,
    val code: String?,
    val message: String,
)

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 解析响应体；不是 JSON 返回 null。 */
internal fun parseJsonOrNull(body: String): JsonObject? =
    runCatching { lenientJson.parseToJsonElement(body) as? JsonObject }.getOrNull()

/**
 * 抽取错误信息。MiniMax 可能在 HTTP 200 里用 `base_resp.status_code != 0` 表达失败——
 * 调用方对 2xx 响应也要过一遍 [businessError]。
 */
internal fun extractErrorBody(json: JsonObject?, rawBody: String): ErrorBody {
    val baseResp = json?.get("base_resp")?.asObject()
    val error = json?.get("error")?.let { it as? JsonObject ?: JsonObject(mapOf("message" to it)) }
    val baseCode = baseResp?.get("status_code")?.asPrimitive()?.intOrNull
    val errorCodePrimitive = error?.get("code")?.asPrimitive()
    val message = baseResp?.get("status_msg")?.asPrimitive()?.contentOrNull()
        ?: error?.get("message")?.asPrimitive()?.contentOrNull()
        ?: json?.get("message")?.asPrimitive()?.contentOrNull()
        ?: rawBody
    return ErrorBody(
        vendorCode = baseCode?.takeIf { it != 0 } ?: errorCodePrimitive?.intOrNull,
        type = error?.get("type")?.asPrimitive()?.contentOrNull(),
        code = errorCodePrimitive?.takeIf { it.isString }?.content,
        message = message.take(MAX_MESSAGE_CHARS),
    )
}

/** 2xx 响应里的业务错误（`base_resp.status_code != 0` 或顶层 `error`）。没有返回 null。 */
internal fun businessError(json: JsonObject, preset: ProviderPreset?): AiError? {
    val baseCode = json["base_resp"]?.asObject()?.get("status_code")?.asPrimitive()?.intOrNull
    val hasError = json["error"] != null
    if ((baseCode == null || baseCode == 0) && !hasError) return null
    return classifyHttpError(200, json, "", preset)
}

/**
 * 把一次失败的 HTTP 响应映射成 [AiError]（工单 §3.7.1）。
 *
 * 顺序：厂商错误码 → HTTP 码 → 响应体关键词。厂商码最具体，放最前。
 */
internal fun classifyHttpError(
    httpCode: Int,
    json: JsonObject?,
    rawBody: String,
    preset: ProviderPreset?,
    retryAfterMs: Long? = null,
): AiError {
    val body = extractErrorBody(json, rawBody)
    val text = listOfNotNull(body.message, body.type, body.code).joinToString(" ").lowercase()
    val invalidKey = if (preset?.regionSensitive == true) {
        AiError.Config(ConfigErrorKind.REGION_MISMATCH)
    } else {
        AiError.Config(ConfigErrorKind.INVALID_KEY)
    }

    body.vendorCode?.let { code ->
        when (preset?.errorCodeMap?.get(code)) {
            VendorErrorKind.QUOTA -> return AiError.QuotaExceeded(code)
            VendorErrorKind.MODEL_NOT_IN_PLAN -> return AiError.Config(ConfigErrorKind.MODEL_NOT_IN_PLAN)
            VendorErrorKind.CONTENT_FILTERED -> return AiError.ContentFiltered(code)
            VendorErrorKind.RATE_LIMITED -> return AiError.RateLimited(retryAfterMs)
            VendorErrorKind.INVALID_KEY -> return invalidKey
            null -> Unit
        }
    }

    val quotaLike = QUOTA_WORDS.any { it in text } || body.type == "insufficient_balance_error"
    val modelLike = MODEL_WORDS.any { it in text }
    return when {
        httpCode == 401 || httpCode == 403 -> invalidKey
        httpCode == 402 || quotaLike -> AiError.QuotaExceeded(body.vendorCode)
        httpCode == 429 -> AiError.RateLimited(retryAfterMs)
        (httpCode == 404 || httpCode == 400) && modelLike -> AiError.Config(ConfigErrorKind.MODEL_NOT_FOUND)
        httpCode == 404 || httpCode == 405 -> AiError.Config(ConfigErrorKind.WRONG_ENDPOINT)
        CONTEXT_WORDS.any { it in text } -> AiError.ContextTooLong
        SENSITIVE_WORDS.any { it in text } -> AiError.ContentFiltered(body.vendorCode)
        httpCode >= 500 -> AiError.Server(httpCode)
        else -> AiError.BadResponse("HTTP $httpCode: ${body.message}".take(MAX_MESSAGE_CHARS))
    }
}

/** 400 且提示 temperature 不被接受：去掉该参数重试一次（借鉴 BiliNote）。 */
internal fun isTemperatureRejection(httpCode: Int, rawBody: String): Boolean =
    httpCode == 400 && rawBody.contains("temperature", ignoreCase = true)

/** 网络层异常 → [AiError]。系统是否离线由 :app 判断（本模块不碰 ConnectivityManager）。 */
internal fun classifyException(e: Throwable): AiError = when (e) {
    is AiException -> e.error
    is UnknownHostException -> AiError.Network("无法解析域名")
    is ConnectException -> AiError.Network("无法连接服务器")
    is SocketTimeoutException -> AiError.Network("连接超时")
    is SSLException -> AiError.Network("安全连接失败")
    is InterruptedIOException -> AiError.Network("请求超时")
    is IOException -> AiError.Network(e.message?.take(MAX_MESSAGE_CHARS) ?: "网络异常")
    else -> AiError.BadResponse(e.message?.take(MAX_MESSAGE_CHARS) ?: e::class.java.simpleName)
}

/** 第 [attempt] 次（0 基）失败后是否自动重试（工单 §3.7.1）。 */
internal fun shouldRetry(error: AiError, attempt: Int): Boolean = when (error) {
    is AiError.Network, is AiError.Server, is AiError.RateLimited, AiError.StreamInterrupted ->
        attempt < MAX_TRANSIENT_RETRIES
    is AiError.BadResponse -> attempt < 1
    else -> false
}

/** 第 [attempt] 次（0 基）重试前等待多久：限流看 Retry-After，其余 2s、6s、18s 加抖动。 */
internal fun backoffMs(error: AiError, attempt: Int, random: Random = Random.Default): Long {
    if (error is AiError.RateLimited) return error.retryAfterMs ?: DEFAULT_RETRY_AFTER_MS
    var base = BASE_BACKOFF_MS
    repeat(attempt.coerceAtLeast(0)) { base *= 3 }
    return base + random.nextLong(JITTER_MS)
}

/** `Retry-After` 头（秒数形式）→ 毫秒。HTTP 日期形式不解析，按缺省处理。 */
internal fun parseRetryAfterMs(header: String?): Long? =
    header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)

internal const val MAX_TRANSIENT_RETRIES = 3
internal const val MAX_MESSAGE_CHARS = 200
private const val BASE_BACKOFF_MS = 2_000L
private const val JITTER_MS = 1_000L
private const val DEFAULT_RETRY_AFTER_MS = 20_000L

private val QUOTA_WORDS = listOf("quota", "insufficient", "balance", "余额", "额度", "用尽", "欠费")
private val MODEL_WORDS = listOf("model not", "model_not", "no such model", "unknown model", "invalid model", "模型不存在")
private val CONTEXT_WORDS = listOf("context length", "context_length", "maximum context", "too long", "too many tokens", "token limit", "上下文")
private val SENSITIVE_WORDS = listOf("sensitive", "敏感", "content_filter", "content policy")

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
private fun JsonElement.asPrimitive(): JsonPrimitive? = this as? JsonPrimitive
private fun JsonPrimitive.contentOrNull(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
