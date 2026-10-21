package com.biliseek.core.ai

import com.biliseek.core.ai.internal.parseJsonOrNull
import com.biliseek.core.ai.internal.parseModelIds
import com.biliseek.core.ai.provider.AiEndpointConfig
import com.biliseek.core.ai.provider.AiTimeouts
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** 「获取模型列表」：全部请求只打本地 MockWebServer（AGENTS.md：测试不联网）。 */
class AiModelListerTest {

    private lateinit var server: MockWebServer
    private val fastTimeouts = AiTimeouts(connectMs = 2_000, callMs = 2_000)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun config(presetId: String = "custom_openai", apiKey: String = "sk-test-key-1234") = AiEndpointConfig(
        presetId = presetId,
        baseUrl = server.url("/v1/").toString(),
        model = "",
        apiKey = apiKey,
    )

    private fun response(code: Int, body: String) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").body(body).build()

    private fun list(presetId: String = "custom_openai", http: OkHttpClient = OkHttpClient()) =
        runBlocking { AiModelLister.list(config(presetId), http, fastTimeouts) }

    @Test
    fun `GET models 带 Key，返回去重排序的模型名`() {
        server.enqueue(
            response(200, """{"object":"list","data":[{"id":"qwen-max"},{"id":"deepseek-chat"},{"id":"qwen-max"},{"object":"model"}]}"""),
        )
        assertEquals(ModelListResult.Ok(listOf("deepseek-chat", "qwen-max")), list())
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.url.encodedPath)
        assertEquals("Bearer sk-test-key-1234", request.headers["Authorization"])
    }

    @Test
    fun `不带 Cookie 也不经过调用方的拦截器`() {
        server.enqueue(response(200, """{"data":[]}"""))
        val dirty = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Cookie", "SESSDATA=secret").build())
            }
            .build()
        assertEquals(ModelListResult.Ok(emptyList()), list(http = dirty))
        assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.headers["Cookie"])
    }

    @Test
    fun `401 是 Key 无效，MiniMax 预设提示区域不匹配`() {
        server.enqueue(response(401, """{"error":{"message":"invalid api key"}}"""))
        assertEquals(ModelListResult.Failed(AiError.Config(ConfigErrorKind.INVALID_KEY)), list())
        server.enqueue(response(401, """{"error":{"message":"invalid api key"}}"""))
        assertEquals(ModelListResult.Failed(AiError.Config(ConfigErrorKind.REGION_MISMATCH)), list("minimax_cn"))
    }

    @Test
    fun `404 是地址不对或服务不支持`() {
        server.enqueue(response(404, """{"error":{"message":"not found"}}"""))
        assertEquals(ModelListResult.Failed(AiError.Config(ConfigErrorKind.WRONG_ENDPOINT)), list())
    }

    @Test
    fun `2xx 但不是 JSON 或没有 data 都算异常响应`() {
        server.enqueue(MockResponse.Builder().code(200).body("<html>login</html>").build())
        assertTrue((list() as ModelListResult.Failed).error is AiError.BadResponse)
        server.enqueue(response(200, """{"models":[]}"""))
        assertTrue((list() as ModelListResult.Failed).error is AiError.BadResponse)
    }

    @Test
    fun `2xx 里的 MiniMax 业务错误码照常分类`() {
        server.enqueue(response(200, """{"base_resp":{"status_code":1004,"status_msg":"auth failed"}}"""))
        assertEquals(ModelListResult.Failed(AiError.Config(ConfigErrorKind.REGION_MISMATCH)), list("minimax_cn"))
    }

    @Test
    fun `缺地址或 Key 不发请求`() = runBlocking {
        val noKey = AiModelLister.list(config(apiKey = ""), OkHttpClient(), fastTimeouts)
        assertEquals(ModelListResult.Failed(AiError.Config(ConfigErrorKind.INVALID_KEY)), noKey)
        val noUrl = AiModelLister.list(config().copy(baseUrl = " "), OkHttpClient(), fastTimeouts)
        assertEquals(ModelListResult.Failed(AiError.Config(ConfigErrorKind.WRONG_ENDPOINT)), noUrl)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `parseModelIds 跳过非字符串与空 id`() {
        val json = parseJsonOrNull("""{"data":[{"id":" b "},{"id":""},{"id":1},"x",{"id":"A"}]}""")!!
        assertEquals(listOf("A", "b"), parseModelIds(json))
        assertNull(parseModelIds(parseJsonOrNull("""{"data":{}}""")!!))
    }
}
