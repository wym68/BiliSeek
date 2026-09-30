package com.biliseek.core.ai

import com.biliseek.core.ai.provider.AiEndpointConfig
import com.biliseek.core.ai.provider.AiTimeouts
import com.biliseek.core.ai.provider.ChatCapabilities
import com.biliseek.core.ai.provider.ReasoningFormat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** 总结流水线端到端：所有请求只打 MockWebServer；按请求里的提示词分辨是哪一步。 */
class SummarizerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val steps = CopyOnWriteArrayList<String>()

    /** 各步骤的应答，测试里按需替换。 */
    @Volatile var chunkReply: (RecordedRequest) -> MockResponse = { json("- [00:10] 分块要点") }
    @Volatile var condenseReply: (RecordedRequest) -> MockResponse = { json("- [00:10] 压缩要点") }
    @Volatile var finalReply: (RecordedRequest) -> MockResponse = { sse("## 一句话总结\n", "最终总结 [00:10]") }
    @Volatile var continuationReply: (RecordedRequest) -> MockResponse = { json("续写内容") }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body?.utf8().orEmpty()
                val step = when {
                    "从中断处继续" in body -> "continue"
                    "长视频的第" in body -> "chunk"
                    "相邻若干段" in body -> "condense"
                    else -> "final"
                }
                steps += step
                return when (step) {
                    "chunk" -> chunkReply(request)
                    "condense" -> condenseReply(request)
                    "continue" -> continuationReply(request)
                    else -> finalReply(request)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun json(content: String, finish: String = "stop") = MockResponse.Builder().code(200)
        .body("""{"choices":[{"message":{"content":${quote(content)}},"finish_reason":"$finish"}]}""").build()

    private fun sse(vararg parts: String, finish: String = "stop") = MockResponse.Builder().code(200)
        .body(
            parts.joinToString("") { """data: {"choices":[{"delta":{"content":${quote(it)}}}]}""" + "\n\n" } +
                """data: {"choices":[{"delta":{},"finish_reason":"$finish"}]}""" + "\n\ndata: [DONE]\n\n",
        ).build()

    private fun quote(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()

    private fun config(model: String = "model-A", maxBytes: Int = 96 * 1024) = AiEndpointConfig(
        presetId = "custom_openai",
        baseUrl = server.url("/v1").toString(),
        model = model,
        apiKey = "sk-test-0000",
        capabilityOverrides = ChatCapabilities(reasoningFormat = ReasoningFormat.AUTO, maxRequestBytes = maxBytes),
    )

    private val summarizer = Summarizer(
        OkHttpClient(),
        AiTimeouts(connectMs = 2_000, firstEventMs = 2_000, idleMs = 2_000, callMs = 2_000),
        sleep = {},
        streamThrottleMs = 0,
    )

    private fun transcript(lines: Int, charsPerLine: Int = 10) = Transcript(
        List(lines) { i -> TranscriptSegment(i * 30_000L, i * 30_000L + 5_000, "第${i}句" + "内".repeat(charsPerLine)) },
    )

    private fun request(
        transcript: Transcript,
        style: SummaryStyle = SummaryStyle.Brief,
        chat: AiEndpointConfig = config(),
        dir: File? = null,
    ) = SummaryRequest(
        title = "测试视频",
        meta = linkedMapOf("UP" to "某人"),
        style = style,
        transcript = transcript,
        chat = chat,
        checkpointDir = dir,
    )

    private fun run(request: SummaryRequest) = runBlocking { summarizer.summarize(request).toList() }

    // 预算最小 2048 字节；每行约 3 字节 × 300 字 ≈ 900 字节，所以 6 行会切成多块
    private val longTranscript get() = transcript(lines = 6, charsPerLine = 300)

    @Test
    fun `单块 直接流式出最终结果`() {
        val events = run(request(transcript(3)))
        assertEquals(listOf("final"), steps)
        assertTrue(events.any { it is SummaryProgress.Streaming })
        val done = events.last() as SummaryProgress.Done
        assertTrue(done.result.markdown, done.result.markdown.startsWith("# 测试视频\n\n> UP：某人 · 模型：model-A"))
        assertTrue(done.result.markdown.contains("最终总结 [00:10]"))
        assertEquals(listOf("model-A"), done.result.models)
    }

    @Test
    fun `思考内容不进结果`() {
        finalReply = { sse("<think>先想想", "</think>正文") }
        val done = run(request(transcript(3))).last() as SummaryProgress.Done
        assertFalse(done.result.markdown.contains("先想想"))
        assertTrue(done.result.markdown.contains("正文"))
    }

    @Test
    fun `多块 逐块总结后合并`() {
        val events = run(request(longTranscript, chat = config(maxBytes = 100)))
        val total = (events.first { it is SummaryProgress.Chunking } as SummaryProgress.Chunking).total
        assertTrue(total > 1)
        assertEquals(List(total) { "chunk" } + "final", steps)
        assertEquals(SummaryProgress.ChunkDone(total, total), events.filterIsInstance<SummaryProgress.ChunkDone>().last())
        assertTrue(events.last() is SummaryProgress.Done)
    }

    @Test
    fun `分片摘要太多时先分层压缩再最终合并`() {
        chunkReply = { json("- [00:10] " + "要".repeat(200)) } // 每个分片摘要约 600 字节
        val events = run(request(transcript(lines = 12, charsPerLine = 300), chat = config(maxBytes = 100)))
        assertTrue(steps.toString(), "condense" in steps)
        assertEquals("final", steps.last())
        assertTrue(events.last() is SummaryProgress.Done)
    }

    @Test
    fun `中途失败后重跑只做剩下的块`() {
        val dir = tmp.newFolder()
        var chunkCalls = 0
        chunkReply = {
            chunkCalls++
            if (chunkCalls == 2) MockResponse.Builder().code(402).body("{}").build() else json("- 要点$chunkCalls")
        }
        val first = run(request(longTranscript, chat = config(maxBytes = 100), dir = dir))
        assertEquals(AiError.QuotaExceeded(null), (first.last() as SummaryProgress.Failed).error)
        assertTrue(File(dir, "checkpoint.json").exists())

        steps.clear()
        chunkReply = { json("- 续做") }
        val second = run(request(longTranscript, chat = config(model = "model-B", maxBytes = 100), dir = dir))
        val total = (second.first { it is SummaryProgress.Chunking } as SummaryProgress.Chunking).total
        // 第 1 块已在断点里，只做剩下的
        assertEquals(List(total - 1) { "chunk" } + "final", steps)
        val done = second.last() as SummaryProgress.Done
        assertEquals(listOf("model-A", "model-B"), done.result.models)
        assertFalse(File(dir, "checkpoint.json").exists())
    }

    @Test
    fun `额度用尽不重试`() {
        finalReply = { MockResponse.Builder().code(402).body("{}").build() }
        val events = run(request(transcript(3)))
        assertEquals(listOf("final"), steps)
        assertEquals(AiError.QuotaExceeded(null), (events.last() as SummaryProgress.Failed).error)
    }

    @Test
    fun `服务端临时故障 退避重试后成功`() {
        var calls = 0
        finalReply = {
            calls++
            if (calls <= 2) MockResponse.Builder().code(503).body("busy").build() else sse("好了")
        }
        val events = run(request(transcript(3)))
        assertEquals(3, steps.size)
        assertTrue(events.last() is SummaryProgress.Done)
    }

    @Test
    fun `最终结果被截断 续写一次`() {
        finalReply = { sse("前半", finish = "length") }
        val done = run(request(transcript(3))).last() as SummaryProgress.Done
        assertEquals(listOf("final", "continue"), steps)
        assertTrue(done.result.markdown.contains("前半\n续写内容"))
        assertFalse(done.result.truncated)

        steps.clear()
        continuationReply = { json("还是没写完", finish = "length") }
        val still = run(request(transcript(3))).last() as SummaryProgress.Done
        assertTrue(still.result.truncated)
        assertTrue(still.result.markdown.contains("输出可能不完整"))
    }

    @Test
    fun `分块总结被截断 对半拆开重做`() {
        var calls = 0
        chunkReply = {
            calls++
            if (calls == 1) json("截断了", finish = "length") else json("- 半块$calls")
        }
        val events = run(request(longTranscript, chat = config(maxBytes = 100)))
        assertTrue(events.last() is SummaryProgress.Done)
        val total = (events.first { it is SummaryProgress.Chunking } as SummaryProgress.Chunking).total
        // 第 1 块多出两次（拆成两半）
        assertEquals(total + 2, steps.count { it == "chunk" })
    }

    @Test
    fun `上下文超长 缩小分块重来`() {
        var calls = 0
        finalReply = {
            calls++
            if (calls == 1) {
                MockResponse.Builder().code(400)
                    .body("""{"error":{"message":"maximum context length exceeded"}}""").build()
            } else {
                sse("缩块后成功")
            }
        }
        val events = run(request(transcript(3)))
        assertTrue(events.last() is SummaryProgress.Done)
    }

    @Test
    fun `原文笔记不发任何请求`() {
        val events = run(request(transcript(3), style = SummaryStyle.TranscriptNote))
        assertEquals(0, server.requestCount)
        val done = events.single() as SummaryProgress.Done
        assertTrue(done.result.markdown.contains("原文笔记，未经 AI 整理"))
        assertTrue(done.result.models.isEmpty())
    }

    @Test
    fun `配置不完整直接失败 不发请求`() {
        val events = run(request(transcript(3), chat = config().copy(apiKey = "")))
        assertEquals(AiError.Config(ConfigErrorKind.INVALID_KEY), (events.single() as SummaryProgress.Failed).error)
        assertEquals(0, server.requestCount)
    }
}
