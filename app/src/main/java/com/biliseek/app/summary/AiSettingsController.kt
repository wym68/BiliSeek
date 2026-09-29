package com.biliseek.app.summary

import com.biliseek.core.ai.AiConnectionTester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** 「测试连接」的结果。 */
sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data object Ok : ConnectionTestState
    data class Failed(val message: String) : ConnectionTestState
}

/**
 * AI 设置页的状态与动作（工单 AI-3）。
 *
 * [aiHttp] 是 AI 专用的 OkHttpClient（与 B 站的 client 分开）；`:core:ai` 内部还会再清一遍拦截器与 Cookie。
 */
class AiSettingsController(
    private val scope: CoroutineScope,
    val store: AiSettingsStore,
    private val aiHttp: OkHttpClient,
    private val emitMessage: (String) -> Unit,
) {
    val state: StateFlow<AiSettings> = store.state

    private val _tests = MutableStateFlow<Map<EndpointSlot, ConnectionTestState>>(emptyMap())
    val tests: StateFlow<Map<EndpointSlot, ConnectionTestState>> = _tests.asStateFlow()

    /** 保存。返回校验失败原因（null = 已保存）。 */
    fun save(slot: EndpointSlot, draft: EndpointDraft, onResult: (String?) -> Unit = {}) {
        scope.launch {
            val hasSavedKey = state.value.endpoint(slot)?.keyConfigured == true
            val problem = AiSettingsPolicy.validate(draft, hasSavedKey)
            if (problem == null) {
                store.saveEndpoint(slot, draft)
                _tests.update { it - slot }
                emitMessage(if (slot == EndpointSlot.PRIMARY) "已保存总结模型" else "已保存备用模型")
            }
            onResult(problem)
        }
    }

    fun clear(slot: EndpointSlot) {
        scope.launch {
            store.clearEndpoint(slot)
            _tests.update { it - slot }
        }
    }

    /** 用草稿测试（草稿 Key 留空则用已保存的）。只在用户点击时调用。 */
    fun test(slot: EndpointSlot, draft: EndpointDraft) {
        scope.launch {
            val config = AiSettingsPolicy.resolve(draft, store.savedKey(slot))
            if (config == null) {
                _tests.update { it + (slot to ConnectionTestState.Failed("请先填写 API Key")) }
                return@launch
            }
            _tests.update { it + (slot to ConnectionTestState.Testing) }
            val error = AiConnectionTester.test(config, aiHttp)
            val result = if (error == null) {
                store.markVerified(config)
                ConnectionTestState.Ok
            } else {
                ConnectionTestState.Failed(AiErrorText.describe(error, config.presetId))
            }
            _tests.update { it + (slot to result) }
        }
    }

    fun setLastStyle(value: StyleChoice) {
        scope.launch { store.setLastStyle(value) }
    }

    fun setCustomPrompt(value: String) {
        scope.launch { store.setCustomPrompt(value) }
    }

    fun acceptPrivacy() {
        scope.launch { store.setPrivacyAccepted() }
    }
}
