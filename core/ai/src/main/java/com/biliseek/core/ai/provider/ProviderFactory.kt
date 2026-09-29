package com.biliseek.core.ai.provider

import com.biliseek.core.ai.provider.openai.OpenAiChatClient
import okhttp3.OkHttpClient

/**
 * 协议 → 客户端的唯一分派点。新增协议在这里加一行（工单 §3.4）。
 *
 * [http] 会先经 [isolatedForAi] 派生：清空拦截器、不带 Cookie。
 */
internal object ProviderFactory {
    fun chat(
        config: AiEndpointConfig,
        http: OkHttpClient,
        timeouts: AiTimeouts = AiTimeouts(),
    ): ChatClient {
        val preset = ProviderCatalog.byId(config.presetId)
        val client = http.isolatedForAi(timeouts)
        return when (preset?.chatProtocol ?: ChatProtocol.OPENAI_COMPAT) {
            ChatProtocol.OPENAI_COMPAT -> OpenAiChatClient(
                config = config,
                preset = preset,
                capabilities = ProviderCatalog.capabilitiesFor(config),
                http = client,
                timeouts = timeouts,
            )
        }
    }
}
