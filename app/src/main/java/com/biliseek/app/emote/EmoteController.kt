package com.biliseek.app.emote

import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.dto.Emote
import com.biliseek.core.network.dto.EmotePackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 表情面板的加载状态。 */
data class EmotePanelState(
    val packages: List<EmotePackage> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * 表情面板 + 进程级表情字典。
 *
 * - [ensureLoaded]：**只在用户打开表情面板或进入不下发表情表的消息页时调用**，
 *   进程内成功一次后不再请求（仓库层另有缓存，双保险）；
 * - [dictionary]：全部表情包合并成 `[doge]` → [Emote]，供 RichText 兜底；
 *   另外吸收评论 / 私信里见过的表情（[remember]），不额外发请求。
 */
class EmoteController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
) {
    private val _panel = MutableStateFlow(EmotePanelState())
    val panel: StateFlow<EmotePanelState> = _panel.asStateFlow()

    private val _dictionary = MutableStateFlow<Map<String, Emote>>(emptyMap())
    val dictionary: StateFlow<Map<String, Emote>> = _dictionary.asStateFlow()

    fun ensureLoaded() {
        val cur = _panel.value
        if (cur.isLoading || cur.packages.isNotEmpty()) return
        _panel.value = cur.copy(isLoading = true, errorMessage = null)
        scope.launch {
            repository.emotePanel()
                .onSuccess { packages ->
                    _panel.value = EmotePanelState(packages = packages)
                    remember(EmoteDictionaryPolicy.fromPackages(packages))
                }
                .onFailure { err ->
                    _panel.value = EmotePanelState(errorMessage = err.message ?: "表情加载失败")
                }
        }
    }

    /** 把某条内容自带的表情表并入字典（已有的不覆盖，面板里的优先）。 */
    fun remember(emotes: Map<String, Emote>) {
        if (emotes.isEmpty()) return
        val cur = _dictionary.value
        val merged = EmoteDictionaryPolicy.merge(cur, emotes)
        if (merged.size != cur.size) _dictionary.value = merged
    }
}
