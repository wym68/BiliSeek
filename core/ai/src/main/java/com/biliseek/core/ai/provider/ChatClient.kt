package com.biliseek.core.ai.provider

import kotlinx.coroutines.flow.Flow

/** 一条对话消息。role 取 `system` / `user` / `assistant`。 */
internal data class ChatMessage(val role: String, val content: String)

internal data class ChatRequest(
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

/** 非流式结果。[content] 为原始正文（思考内容未剥，由调用方按预设剥离）。 */
internal data class ChatResult(val content: String, val finishReason: String?)

/** 流式事件。 */
internal sealed interface ChatStreamEvent {
    /** 正文增量（原始，可能含 `<think>`）。 */
    data class Content(val text: String) : ChatStreamEvent

    /** 独立字段的思考增量：不展示，只说明「对方还活着」。 */
    data object Thinking : ChatStreamEvent

    data class Finished(val finishReason: String?) : ChatStreamEvent
}

/**
 * 一种线协议的客户端（SPI）。失败一律抛 [com.biliseek.core.ai.internal.AiException]。
 *
 * 新协议 = 新实现 + [ProviderFactory] 一行分派（工单 §3.4）。
 */
internal interface ChatClient {
    suspend fun complete(request: ChatRequest): ChatResult
    fun stream(request: ChatRequest): Flow<ChatStreamEvent>
}
