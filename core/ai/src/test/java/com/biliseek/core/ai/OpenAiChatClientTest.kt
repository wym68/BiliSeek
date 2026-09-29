package com.biliseek.core.ai

import com.biliseek.core.ai.internal.AiException
import com.biliseek.core.ai.provider.AiEndpointConfig
import com.biliseek.core.ai.provider.AiTimeouts
import com.biliseek.core.ai.provider.ChatMessage
import com.biliseek.core.ai.provider.ChatRequest
import com.biliseek.core.ai.provider.ChatStreamEvent
import com.biliseek.core.ai.provider.ProviderFactory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** OpenAI 兼容客户端：全部请求只打本地 MockWebServer（AGENTS.md：测试不联网）。 */
class OpenAiChatClientTest {

    private lateinit var server: MockWebServer
    private val fastTimeouts = AiTimeouts(connectMs = 2_000, firstEventMs = 1_000, idleMs = 300, callMs = 2_000)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun config(presetId: String = "custom_openai") = AiEndpointConfig(
        presetId = presetId,
        baseUrl = server.url("/v1/").toString(),
        model = "test-model",
        apiKey = "sk-test-key-1234",
    )

    private fun client(
        presetId: String = "custom_openai",
        http: OkHttpClient = OkHttpClient(),
        timeouts: AiTimeouts = fastTimeouts,
    ) = ProviderFactory.chat(config(presetId), http, timeouts)

    private val request = ChatRequest(listOf(ChatMessage("user", "你好")), temperature = 0.3)

    private fun json(body: String) = MockResponse.Builder().code(200)
        .addHeader("Content-Type", "application/json").body(body).build()

    private fun sse(vararg events: String) = MockResponse.Builder().code(200)
        .addHeader("Content-Type", "text/event-stream")
        .body(events.joinToString("") { "data: $it\n\n" })
        .build()

    private fun expectError(block: suspend () -> Unit): AiError = runBlocking {
        try {
            block()
            fail("应当失败")
            error("unreachable")
        } catch (e: AiException) {
            e.error
        }
    }

    private fun recordedJson(): JsonObject =
        Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body!!.utf8()) as JsonObject

    // ── 非流式 ────────────────────────────────────────────

    @Test
    fun `非流式 请求形状与结果`() = runBlocking {
        server.enqueue(json("""{"choices":[{"message":{"content":"<think>x</think>答案"},"finish_reason":"stop"}]}"""))
        val result = client().complete(request)
        assertEquals("<think>x</think>答案", result.content)
        assertEquals("stop", result.finishReason)

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", recorded.url.encodedPath)
        assertEquals("Bearer sk-test-key-1234", recorded.headers["Authorization"])
        val body = Json.parseToJsonElement(recorded.body!!.utf8()) as JsonObject
        assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("false", body["stream"]!!.jsonPrimitive.content)
        assertEquals("0.3", body["temperature"]!!.jsonPrimitive.content)
    }

    @Test
    fun `不带 Cookie 也不经过调用方的拦截器`() = runBlocking {
        server.enqueue(json("""{"choices":[{"message":{"content":"ok"}}]}"""))
        val biliLike = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Cookie", "SESSDATA=secret").build())
            }
            .build()
        client(http = biliLike).complete(request)
        assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.headers["Cookie"])
    }

    @Test
    fun `HTTP 200 里的业务错误码不当成功`() {
        server.enqueue(json("""{"base_resp":{"status_code":1028,"status_msg":"quota"}}"""))
        assertEquals(AiError.QuotaExceeded(1028), expectError { client("minimax_cn").complete(request) })
    }

    @Test
    fun `非 JSON 的 200 是异常响应`() {
        server.enqueue(MockResponse.Builder().code(200).body("<html>请登录 Wi-Fi</html>").build())
        assertTrue(expectError { client().complete(request) } is AiError.BadResponse)
    }

    @Test
    fun `401 映射为 Key 无效`() {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"invalid"}}""").build())
        assertEquals(AiError.Config(ConfigErrorKind.INVALID_KEY), expectError { client().complete(request) })
    }

    @Test
    fun `temperature 被拒后去掉重发一次 之后不再带`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"message":"temperature not supported"}}""").build())
        server.enqueue(json("""{"choices":[{"message":{"content":"ok"}}]}"""))
        server.enqueue(json("""{"choices":[{"message":{"content":"ok2"}}]}"""))
        val c = client()
        assertEquals("ok", c.complete(request).content)
        assertTrue(recordedJson().containsKey("temperature"))
        assertFalse(recordedJson().containsKey("temperature"))
        c.complete(request)
        assertFalse(recordedJson().containsKey("temperature"))
    }

    // ── 流式 ──────────────────────────────────────────────

    @Test
    fun `流式 正文增量 思考心跳 结束原因`() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"reasoning_content":"想"}}]}""",
                """{"choices":[{"delta":{"content":"你"}}]}""",
                """{"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"total_tokens":3}}""",
                "[DONE]",
            ),
        )
        val events = client().stream(request).toList()
        assertEquals(
            listOf(
                ChatStreamEvent.Thinking,
                ChatStreamEvent.Content("你"),
                ChatStreamEvent.Content("好"),
                ChatStreamEvent.Finished("stop"),
            ),
            events,
        )
        assertEquals("true", recordedJson()["stream"]!!.jsonPrimitive.content)
    }

    @Test
    fun `流中途出现业务错误`() {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"content":"半"}}]}""",
                """{"base_resp":{"status_code":1027,"status_msg":"output sensitive"}}""",
            ),
        )
        assertEquals(AiError.ContentFiltered(1027), expectError { client("minimax_cn").stream(request).toList() })
    }

    @Test
    fun `没有 DONE 也没有结束原因就断了 视为中途断开`() {
        server.enqueue(sse("""{"choices":[{"delta":{"content":"半句"}}]}"""))
        assertEquals(AiError.StreamInterrupted, expectError { client().stream(request).toList() })
    }

    @Test
    fun `有结束原因但缺 DONE 视为正常结束`() = runBlocking {
        server.enqueue(sse("""{"choices":[{"delta":{"content":"完"},"finish_reason":"length"}]}"""))
        val events = client().stream(request).toList()
        assertEquals(ChatStreamEvent.Finished("length"), events.last())
    }

    @Test
    fun `首个事件迟迟不来 判网络超时`() {
        server.enqueue(
            MockResponse.Builder().code(200).headersDelay(3, TimeUnit.SECONDS)
                .body("data: [DONE]\n\n").build(),
        )
        assertTrue(expectError { client().stream(request).toList() } is AiError.Network)
    }

    @Test
    fun `事件之间停顿超过空闲超时 判中途断开`() {
        server.enqueue(MockResponse.Builder().code(200).body(PausingSseBody(pauseMs = 1_500)).build())
        assertEquals(AiError.StreamInterrupted, expectError { client().stream(request).toList() })
    }

    // ── 测试连接 ──────────────────────────────────────────

    @Test
    fun `测试连接 成功返回 null 失败返回原因`() = runBlocking {
        server.enqueue(json("""{"choices":[{"message":{"content":"pong"},"finish_reason":"length"}]}"""))
        assertNull(AiConnectionTester.test(config(), OkHttpClient(), fastTimeouts))

        server.enqueue(MockResponse.Builder().code(401).body("{}").build())
        assertEquals(
            AiError.Config(ConfigErrorKind.REGION_MISMATCH),
            AiConnectionTester.test(config("minimax_cn"), OkHttpClient(), fastTimeouts),
        )
    }

    @Test
    fun `测试连接 本地缺项不发请求`() = runBlocking {
        val blankKey = config().copy(apiKey = "")
        assertEquals(AiError.Config(ConfigErrorKind.INVALID_KEY), AiConnectionTester.test(blankKey, OkHttpClient()))
        val blankModel = config().copy(model = " ")
        assertEquals(AiError.Config(ConfigErrorKind.MODEL_NOT_FOUND), AiConnectionTester.test(blankModel, OkHttpClient()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `地址无法解析为 URL 是地址错误`() {
        val bad = ProviderFactory.chat(config().copy(baseUrl = "not a url"), OkHttpClient(), fastTimeouts)
        assertEquals(AiError.Config(ConfigErrorKind.WRONG_ENDPOINT), expectError { bad.complete(request) })
    }
}

/** 先发一个事件，停顿 [pauseMs] 再发 [DONE]。独立成类：中文命名的测试函数里建匿名类，类文件名会含中文。 */
private class PausingSseBody(private val pauseMs: Long) : MockResponseBody {
    override val contentLength: Long = -1
    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8("data: {\"choices\":[{\"delta\":{\"content\":\"一\"}}]}\n\n").flush()
        Thread.sleep(pauseMs)
        sink.writeUtf8("data: [DONE]\n\n").flush()
    }
}
