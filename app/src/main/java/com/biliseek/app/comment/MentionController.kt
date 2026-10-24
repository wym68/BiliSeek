package com.biliseek.app.comment

import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.dto.MentionUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** @ 候选面板：[keyword] 为 null = 没在 @ 人，面板不显示。 */
data class MentionPanelState(
    val keyword: String? = null,
    val users: List<MentionUser> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * 写评论时 @ 的候选人（评论区、「回复与@」共用一份，同一时间只有一个输入栏在用）。
 *
 * 随打字查询：停 [debounceMs] 再发，新词来了旧的作废；同一个词进程内只查一次（缓存在内存）。
 * 没登录不发请求。**不重试**：失败显示原因，换个字会再查。
 */
class MentionController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val session: StateFlow<BiliSession>,
    private val debounceMs: Long = 350,
) {
    private val _state = MutableStateFlow(MentionPanelState())
    val state: StateFlow<MentionPanelState> = _state.asStateFlow()

    private val cache = mutableMapOf<String, List<MentionUser>>()
    private var job: Job? = null

    /** 输入栏里正在打的 @ 关键词变了；null = 不在 @ 了（收起面板）。 */
    fun search(keyword: String?) {
        if (keyword == _state.value.keyword) return
        job?.cancel()
        if (keyword == null) {
            _state.value = MentionPanelState()
            return
        }
        if (!session.value.isLoggedIn) {
            _state.value = MentionPanelState(keyword, errorMessage = "登录后才能 @ 人")
            return
        }
        cache[keyword]?.let {
            _state.value = MentionPanelState(keyword, it)
            return
        }
        // 等结果时先留着上一次的候选，免得列表一闪一闪
        _state.value = _state.value.copy(keyword = keyword, isLoading = true, errorMessage = null)
        job = scope.launch {
            delay(debounceMs)
            val result = repository.searchMentionUsers(keyword)
            if (!isActive) return@launch
            result
                .onSuccess { users ->
                    cache[keyword] = users
                    _state.value = MentionPanelState(keyword, users)
                }
                .onFailure { err ->
                    _state.value = MentionPanelState(keyword, errorMessage = commentErrorText("@ 候选加载", err))
                }
        }
    }
}
