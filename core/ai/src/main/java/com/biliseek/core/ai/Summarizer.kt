package com.biliseek.core.ai

import com.biliseek.core.ai.internal.AiException
import com.biliseek.core.ai.internal.Chunk
import com.biliseek.core.ai.internal.CheckpointStore
import com.biliseek.core.ai.internal.MarkdownPolicy
import com.biliseek.core.ai.internal.PartialResult
import com.biliseek.core.ai.internal.PromptPolicy
import com.biliseek.core.ai.internal.TranscriptNotePolicy
import com.biliseek.core.ai.internal.backoffMs
import com.biliseek.core.ai.internal.chunkTranscript
import com.biliseek.core.ai.internal.classifyException
import com.biliseek.core.ai.internal.compactTranscript
import com.biliseek.core.ai.internal.planMerge
import com.biliseek.core.ai.internal.sha256Hex
import com.biliseek.core.ai.internal.shouldRetry
import com.biliseek.core.ai.internal.stripReasoning
import com.biliseek.core.ai.provider.AiTimeouts
import com.biliseek.core.ai.provider.ChatCapabilities
import com.biliseek.core.ai.provider.ChatClient
import com.biliseek.core.ai.provider.ChatMessage
import com.biliseek.core.ai.provider.ChatRequest
import com.biliseek.core.ai.provider.ChatStreamEvent
import com.biliseek.core.ai.provider.ProviderCatalog
import com.biliseek.core.ai.provider.ProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * 总结流水线（工单 AI-2）：原文 → 压缩 → 分块 → 逐块总结（落断点）→ 分层合并 → 最终合并（流式）→ 规整 md。
 * 只有一块时直接流式出最终结果。
 *
 * 失败不抛异常，而是发出 [SummaryProgress.Failed] 后结束；已完成的分块与合并轮次留在断点里，
 * 用同一个 [SummaryRequest.checkpointDir] 再调一次即从断点继续（换模型也沿用已完成分块）。
 * 取消（协程取消）照常传播，断点保留。
 *
 * [http] 会被派生成隔离的客户端：清空拦截器、不带 Cookie（工单 §3.6）。
 */
class Summarizer internal constructor(
    private val http: OkHttpClient,
    private val timeouts: AiTimeouts,
    private val sleep: suspend (Long) -> Unit,
    private val streamThrottleMs: Long,
) {
    constructor(http: OkHttpClient) : this(http, AiTimeouts(), { delay(it) }, DEFAULT_STREAM_THROTTLE_MS)

    fun summarize(request: SummaryRequest): Flow<SummaryProgress> = flow {
        try {
            execute(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(SummaryProgress.Failed(classifyException(e)))
        }
    }

    private suspend fun FlowCollector<SummaryProgress>.execute(request: SummaryRequest) {
        val transcript = request.transcript
        val durationMs = transcript.segments.maxOfOrNull { it.endMs }

        if (request.style is SummaryStyle.TranscriptNote) {
            val body = TranscriptNotePolicy.render(transcript)
            emit(done(request, body, emptyList(), truncated = false))
            return
        }
        AiConnectionTester.incompleteReason(request.chat)?.let {
            emit(SummaryProgress.Failed(it))
            return
        }

        val client = ProviderFactory.chat(request.chat, http, timeouts)
        val caps = ProviderCatalog.capabilitiesFor(request.chat)
        val useHours = (durationMs ?: 0) >= 3_600_000
        val lines = compactTranscript(transcript.segments, transcript.chapters.map { it.startMs })
        if (lines.isEmpty()) {
            emit(SummaryProgress.Failed(AiError.BadResponse("原文为空，无法总结")))
            return
        }
        val overhead = PromptPolicy.overheadBytes(request.title, request.context, request.style, transcript.timing)

        var maxBytes = caps.maxRequestBytes
        var shrinks = 0
        while (true) {
            val budget = (maxBytes - overhead).coerceAtLeast(MIN_CHUNK_BYTES)
            val chunks = chunkTranscript(lines, transcript.chapters, budget, useHours)
            try {
                runChunks(request, client, caps, chunks, maxBytes, overhead, durationMs)
                return
            } catch (e: AiException) {
                if (e.error != AiError.ContextTooLong || shrinks >= MAX_SHRINKS) throw e
                maxBytes /= 2
                shrinks++
            }
        }
    }

    private suspend fun FlowCollector<SummaryProgress>.runChunks(
        request: SummaryRequest,
        client: ChatClient,
        caps: ChatCapabilities,
        chunks: List<Chunk>,
        maxBytes: Int,
        overhead: Int,
        durationMs: Long?,
    ) {
        val timing = request.transcript.timing
        val model = request.chat.model

        if (chunks.size == 1) {
            val messages = PromptPolicy.single(request.title, request.context, request.style, timing, chunks[0].text)
            val (body, truncated) = streamFinal(client, caps, messages)
            emit(done(request, body, listOf(model), truncated, durationMs))
            return
        }

        val store = CheckpointStore(request.checkpointDir)
        var checkpoint = store.load(signature(request, maxBytes))
        val total = chunks.size
        val partials = checkpoint.partials.filter { it.index < total }.associateBy { it.index }.toMutableMap()
        emit(SummaryProgress.Chunking(total))
        if (partials.isNotEmpty()) emit(SummaryProgress.ChunkDone(partials.size, total))

        for ((index, chunk) in chunks.withIndex()) {
            if (index in partials) continue
            val text = summarizeChunk(client, caps, request, chunk, index + 1, total, depth = 0)
            partials[index] = PartialResult(index, text, request.chat.presetId, model)
            checkpoint = checkpoint.copy(partials = partials.values.sortedBy { it.index })
            store.save(checkpoint)
            emit(SummaryProgress.ChunkDone(partials.size, total))
        }

        // 分层合并：已完成的轮次直接接上
        var texts = checkpoint.mergeRounds.lastOrNull() ?: partials.values.sortedBy { it.index }.map { it.text }
        var mergeBudget = (maxBytes - overhead).coerceAtLeast(MIN_CHUNK_BYTES)
        var mergeShrinks = 0
        var rounds = checkpoint.mergeRounds.size
        var result: Pair<String, Boolean>? = null
        while (result == null) {
            try {
                val plan = planMerge(texts, mergeBudget)
                if (plan == null) {
                    val messages = PromptPolicy.finalMerge(request.title, request.context, request.style, timing, texts)
                    result = streamFinal(client, caps, messages)
                } else {
                    // 每组只剩一个分片还放不下，或轮数用尽：再分组也没有进展
                    if (plan.size >= texts.size || rounds >= MAX_MERGE_ROUNDS) throw AiException(AiError.ContextTooLong)
                    texts = plan.map { group ->
                        condense(client, caps, request.title, timing, group.map { texts[it] })
                    }
                    rounds++
                    checkpoint = checkpoint.copy(
                        mergeRounds = checkpoint.mergeRounds + listOf(texts),
                        mergeModels = checkpoint.mergeModels + model,
                    )
                    store.save(checkpoint)
                }
            } catch (e: AiException) {
                if (e.error != AiError.ContextTooLong || mergeShrinks >= MAX_SHRINKS) throw e
                mergeBudget = (mergeBudget / 2).coerceAtLeast(MIN_CHUNK_BYTES)
                mergeShrinks++
            }
        }

        val models = (partials.values.sortedBy { it.index }.map { it.model } + checkpoint.mergeModels + model).distinct()
        emit(done(request, result.first, models, result.second, durationMs))
        store.clear()
    }

    /** 一块原文的总结。输出被截断（finish_reason=length）时对半拆开重做。 */
    private suspend fun summarizeChunk(
        client: ChatClient,
        caps: ChatCapabilities,
        request: SummaryRequest,
        chunk: Chunk,
        index: Int,
        total: Int,
        depth: Int,
    ): String {
        val messages = PromptPolicy.chunk(request.title, request.context, request.transcript.timing, chunk, index, total)
        val result = withRetry { completeVisible(client, caps, messages) }
        if (result.second != FINISH_LENGTH || depth >= MAX_SPLIT_DEPTH) return result.first
        val halves = chunk.splitInHalf() ?: return result.first
        val parts = mutableListOf<String>()
        for (half in halves) parts += summarizeChunk(client, caps, request, half, index, total, depth + 1)
        return parts.joinToString("\n")
    }

    private suspend fun condense(
        client: ChatClient,
        caps: ChatCapabilities,
        title: String,
        timing: Transcript.Timing,
        partials: List<String>,
    ): String = withRetry { completeVisible(client, caps, PromptPolicy.condense(title, timing, partials)).first }

    /** 非流式请求，返回 (去掉思考内容的正文, finish_reason)。空正文视为异常响应。 */
    private suspend fun completeVisible(
        client: ChatClient,
        caps: ChatCapabilities,
        messages: List<ChatMessage>,
    ): Pair<String, String?> {
        val r = client.complete(ChatRequest(messages, temperature = TEMPERATURE))
        val visible = stripReasoning(r.content, caps.reasoningFormat, complete = true)
        if (visible.isBlank()) throw AiException(AiError.BadResponse("模型返回了空内容"))
        return visible to r.finishReason
    }

    /**
     * 最终结果（流式）。返回 (正文, 是否在续写后仍被截断)。
     * 被截断时追加一次「从中断处继续」请求；流式中断按瞬时错误整步重试。
     */
    private suspend fun FlowCollector<SummaryProgress>.streamFinal(
        client: ChatClient,
        caps: ChatCapabilities,
        messages: List<ChatMessage>,
    ): Pair<String, Boolean> = withRetry {
        val raw = StringBuilder()
        var finish: String? = null
        if (caps.streaming) {
            var lastEmitNs = 0L
            client.stream(ChatRequest(messages, temperature = TEMPERATURE)).collect { event ->
                when (event) {
                    is ChatStreamEvent.Content -> {
                        raw.append(event.text)
                        val now = System.nanoTime()
                        if (now - lastEmitNs >= streamThrottleMs * 1_000_000) {
                            lastEmitNs = now
                            val soFar = stripReasoning(raw.toString(), caps.reasoningFormat, complete = false)
                            if (soFar.isNotEmpty()) emit(SummaryProgress.Streaming(soFar))
                        }
                    }
                    ChatStreamEvent.Thinking -> Unit
                    is ChatStreamEvent.Finished -> finish = event.finishReason
                }
            }
        } else {
            val r = client.complete(ChatRequest(messages, temperature = TEMPERATURE))
            raw.append(r.content)
            finish = r.finishReason
        }
        var visible = stripReasoning(raw.toString(), caps.reasoningFormat, complete = true)
        if (visible.isBlank()) throw AiException(AiError.BadResponse("模型返回了空内容"))

        var truncated = false
        if (finish == FINISH_LENGTH) {
            val (more, moreFinish) = completeVisible(client, caps, PromptPolicy.continuation(messages, visible))
            visible = visible + (if (visible.endsWith("\n")) "" else "\n") + more
            truncated = moreFinish == FINISH_LENGTH
        }
        emit(SummaryProgress.Streaming(visible))
        visible to truncated
    }

    /** 瞬时错误按工单 §3.7.1 退避重试；其余错误直接抛出。 */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = classifyException(e)
                if (!shouldRetry(error, attempt)) throw (e as? AiException ?: AiException(error, e))
                sleep(backoffMs(error, attempt))
                attempt++
            }
        }
    }

    private fun done(
        request: SummaryRequest,
        body: String,
        models: List<String>,
        truncated: Boolean,
        durationMs: Long? = request.transcript.segments.maxOfOrNull { it.endMs },
    ): SummaryProgress.Done {
        val normalized = MarkdownPolicy.normalize(body, durationMs)
        val markdown = MarkdownPolicy.compose(request.title, request.meta, models, normalized, truncated)
        return SummaryProgress.Done(SummaryResult(markdown, models, truncated))
    }

    private fun signature(request: SummaryRequest, maxBytes: Int): String = sha256Hex(
        json.encodeToString(Transcript.serializer(), request.transcript),
        styleKey(request.style),
        "maxBytes=$maxBytes",
    )

    private companion object {
        val json = Json
        const val TEMPERATURE = 0.3
        const val FINISH_LENGTH = "length"
        const val MAX_SHRINKS = 2
        const val MAX_MERGE_ROUNDS = 4
        const val MAX_SPLIT_DEPTH = 2
        const val MIN_CHUNK_BYTES = 2_048
        const val DEFAULT_STREAM_THROTTLE_MS = 150L

        fun styleKey(style: SummaryStyle): String = when (style) {
            SummaryStyle.Brief -> "brief"
            SummaryStyle.DetailedNotes -> "detailed"
            SummaryStyle.TimestampOutline -> "outline"
            is SummaryStyle.Custom -> "custom:" + sha256Hex(style.prompt)
            SummaryStyle.TranscriptNote -> "note"
        }
    }
}

/** 按行对半拆开；只有一行时返回 null（拆不动）。 */
private fun Chunk.splitInHalf(): List<Chunk>? {
    val lines = text.lines()
    if (lines.size < 2) return null
    val mid = lines.size / 2
    return listOf(
        copy(text = lines.subList(0, mid).joinToString("\n")),
        copy(text = lines.subList(mid, lines.size).joinToString("\n")),
    )
}

