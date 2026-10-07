package com.biliseek.app.hint

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 首次使用演示的状态持有者（功能控制器，`AppViewModel.hints`；界面经 `LocalHints` 取用）。
 * 判定全在 HintPolicy.kt，这里只管状态与落盘：持久部分一变就写。只在主线程调用。
 */
class HintController(private val records: HintRecords) {

    private val _state = MutableStateFlow(HintState(persisted = records.load()))
    val state: StateFlow<HintState> = _state.asStateFlow()

    /** 冷启动一次（`MainActivity` 没有 savedInstanceState 时）。 */
    fun onColdStart() = update {
        it.copy(persisted = it.persisted.copy(launchSeq = it.persisted.launchSeq + 1))
    }

    /** 全屏 / 画中画时不开始新演示。 */
    fun setEnvironmentBlocked(blocked: Boolean) = update { it.copy(environmentBlocked = blocked) }

    /** 进入动态页一次（⑪ 要等之后的另一次进入）。 */
    fun enterDynamic() = update { it.copy(dynamicVisit = it.dynamicVisit + 1) }

    /** 能放就开始放 [key]；返回这次是否开始了。 */
    fun tryStart(key: HintKey): Boolean {
        val before = _state.value.active
        update { startHint(it, key) }
        return before != key && _state.value.active == key
    }

    /** 演示放完：记为看过。 */
    fun finish(key: HintKey) = update { markHintSeen(it, key) }

    /** 没放完就离开页面：不记。 */
    fun cancel(key: HintKey) = update { cancelHint(it, key) }

    /** 用户自己做了 [key] 对应的操作。 */
    fun onUserAction(key: HintKey) = update { applyUserAction(it, key) }

    /** 演示中手指碰了屏幕：当前演示结束、记为看过。 */
    fun onTouch() {
        val active = _state.value.active ?: return
        finish(active)
    }

    /** 设置里「重新显示操作提示」。 */
    fun reset() = update { resetHints(it) }

    private fun update(transform: (HintState) -> HintState) {
        val before = _state.value
        val after = transform(before)
        if (after == before) return
        _state.value = after
        if (after.persisted != before.persisted) records.save(after.persisted)
    }
}
