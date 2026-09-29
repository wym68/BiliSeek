package com.biliseek.core.ai

import com.biliseek.core.ai.internal.AiException
import com.biliseek.core.ai.internal.backoffMs
import com.biliseek.core.ai.internal.businessError
import com.biliseek.core.ai.internal.classifyException
import com.biliseek.core.ai.internal.classifyHttpError
import com.biliseek.core.ai.internal.isTemperatureRejection
import com.biliseek.core.ai.internal.parseJsonOrNull
import com.biliseek.core.ai.internal.parseRetryAfterMs
import com.biliseek.core.ai.internal.shouldRetry
import com.biliseek.core.ai.provider.ProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.random.Random

/** 工单 §3.7.1 错误分类表逐行对照。 */
class AiErrorPolicyTest {

    /** 抖动恒为 0 的随机源。放在类级别：中文命名的测试函数里建匿名类，类文件名会含中文。 */
    private val zero = object : Random() {
        override fun nextBits(bitCount: Int) = 0
    }

    private val minimax = ProviderCatalog.MINIMAX_CN
    private val custom = ProviderCatalog.CUSTOM_OPENAI

    private fun http(code: Int, body: String, preset: com.biliseek.core.ai.provider.ProviderPreset? = custom, retryAfter: Long? = null) =
        classifyHttpError(code, parseJsonOrNull(body), body, preset, retryAfter)

    @Test
    fun `401 403 是 Key 无效 MiniMax 预设提示区域不匹配`() {
        assertEquals(AiError.Config(ConfigErrorKind.INVALID_KEY), http(401, """{"error":{"message":"bad key"}}"""))
        assertEquals(AiError.Config(ConfigErrorKind.INVALID_KEY), http(403, "forbidden"))
        assertEquals(AiError.Config(ConfigErrorKind.REGION_MISMATCH), http(401, "{}", minimax))
    }

    @Test
    fun `额度用尽 402 类型字段 关键词 厂商码`() {
        assertEquals(AiError.QuotaExceeded(null), http(402, "{}"))
        assertEquals(
            AiError.QuotaExceeded(null),
            http(400, """{"error":{"type":"insufficient_balance_error","message":"x"}}"""),
        )
        assertEquals(AiError.QuotaExceeded(null), http(429, """{"error":{"message":"You exceeded your current quota"}}"""))
        assertEquals(
            AiError.QuotaExceeded(1028),
            http(200, """{"base_resp":{"status_code":1028,"status_msg":"usage limit"}}""", minimax),
        )
    }

    @Test
    fun `限流 429 带 Retry-After MiniMax 1002 按限流`() {
        assertEquals(AiError.RateLimited(5_000), http(429, "{}", retryAfter = 5_000))
        assertEquals(AiError.RateLimited(null), http(200, """{"base_resp":{"status_code":1002}}""", minimax))
    }

    @Test
    fun `地址与模型配置错误`() {
        assertEquals(AiError.Config(ConfigErrorKind.WRONG_ENDPOINT), http(404, "<html>not found</html>"))
        assertEquals(AiError.Config(ConfigErrorKind.WRONG_ENDPOINT), http(405, ""))
        assertEquals(
            AiError.Config(ConfigErrorKind.MODEL_NOT_FOUND),
            http(404, """{"error":{"message":"The model `gpt-9` does not exist","code":"model_not_found"}}"""),
        )
        assertEquals(
            AiError.Config(ConfigErrorKind.MODEL_NOT_IN_PLAN),
            http(200, """{"base_resp":{"status_code":2061,"status_msg":"not in plan"}}""", minimax),
        )
    }

    @Test
    fun `上下文超长 内容审核 服务端故障 其他`() {
        assertEquals(AiError.ContextTooLong, http(400, """{"error":{"message":"This model's maximum context length is 8192 tokens"}}"""))
        assertEquals(AiError.ContentFiltered(1027), http(200, """{"base_resp":{"status_code":1027,"status_msg":"output new_sensitive"}}""", minimax))
        assertEquals(AiError.ContentFiltered(null), http(400, """{"error":{"message":"input contains sensitive content"}}"""))
        assertEquals(AiError.Server(503), http(503, "overloaded"))
        assertEquals(AiError.Server(529), http(529, "{}"))
        assertTrue(http(418, "teapot") is AiError.BadResponse)
    }

    @Test
    fun `HTTP 200 也要看响应体`() {
        val ok = parseJsonOrNull("""{"base_resp":{"status_code":0},"choices":[]}""")!!
        assertNull(businessError(ok, minimax))
        val normal = parseJsonOrNull("""{"choices":[]}""")!!
        assertNull(businessError(normal, custom))
        val quota = parseJsonOrNull("""{"base_resp":{"status_code":1030,"status_msg":"x"}}""")!!
        assertEquals(AiError.QuotaExceeded(1030), businessError(quota, minimax))
    }

    @Test
    fun `非 JSON 响应解析为 null`() {
        assertNull(parseJsonOrNull("<html>portal</html>"))
    }

    @Test
    fun `temperature 被拒识别`() {
        assertTrue(isTemperatureRejection(400, """{"error":{"message":"temperature is not supported"}}"""))
        assertFalse(isTemperatureRejection(500, "temperature"))
    }

    @Test
    fun `网络异常分类`() {
        assertTrue(classifyException(UnknownHostException()) is AiError.Network)
        assertTrue(classifyException(ConnectException()) is AiError.Network)
        assertTrue(classifyException(SocketTimeoutException()) is AiError.Network)
        assertTrue(classifyException(SSLHandshakeException("x")) is AiError.Network)
        assertEquals(AiError.Truncated, classifyException(AiException(AiError.Truncated)))
    }

    @Test
    fun `重试策略`() {
        val net = AiError.Network("x")
        assertTrue(shouldRetry(net, 0))
        assertTrue(shouldRetry(net, 2))
        assertFalse(shouldRetry(net, 3))
        assertTrue(shouldRetry(AiError.StreamInterrupted, 0))
        assertTrue(shouldRetry(AiError.BadResponse("x"), 0))
        assertFalse(shouldRetry(AiError.BadResponse("x"), 1))
        // 额度、配置、审核、截断、取消：一律不自动重试
        listOf(
            AiError.QuotaExceeded(null),
            AiError.Config(ConfigErrorKind.INVALID_KEY),
            AiError.ContentFiltered(null),
            AiError.Truncated,
            AiError.ContextTooLong,
            AiError.Cancelled,
            AiError.Offline,
        ).forEach { assertFalse(it.toString(), shouldRetry(it, 0)) }
    }

    @Test
    fun `退避时长 2s 6s 18s 加抖动 限流看 Retry-After`() {
        assertEquals(2_000L, backoffMs(AiError.Network("x"), 0, zero))
        assertEquals(6_000L, backoffMs(AiError.Network("x"), 1, zero))
        assertEquals(18_000L, backoffMs(AiError.Server(500), 2, zero))
        val jittered = backoffMs(AiError.Network("x"), 0)
        assertTrue(jittered in 2_000L until 3_000L)
        assertEquals(7_000L, backoffMs(AiError.RateLimited(7_000), 0))
        assertEquals(20_000L, backoffMs(AiError.RateLimited(null), 0))
    }

    @Test
    fun `Retry-After 秒数解析`() {
        assertEquals(30_000L, parseRetryAfterMs("30"))
        assertNull(parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(parseRetryAfterMs(null))
    }
}
