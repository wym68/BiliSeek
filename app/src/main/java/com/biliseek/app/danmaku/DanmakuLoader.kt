package com.biliseek.app.danmaku

import com.biliseek.core.network.dto.DanmakuItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 按播放进度逐段拉弹幕，结果只放内存（远端数据不落库）。
 *
 * - 换视频（cid 变了）清空重来；
 * - 每段只请求一次：成功的记下，失败的也记下且**不重试**——
 *   撞了 -352 还硬顶会把临时限制升级成长期封禁（CLAUDE.md 风控规则）；
 * - 一次只跑一个请求，段与段之间由网络层的限速桶再隔开。
 */
class DanmakuLoader(
    private val scope: CoroutineScope,
    private val fetch: suspend (cid: Long, segmentIndex: Int) -> Result<List<DanmakuItem>>,
) {
    private val _items = MutableStateFlow<List<DanmakuItem>>(emptyList())

    /** 当前视频已加载的全部弹幕（未过滤、未排序）。 */
    val items: StateFlow<List<DanmakuItem>> = _items.asStateFlow()

    private var cid = 0L
    private val requested = mutableSetOf<Int>()
    private var job: Job? = null

    /** 播放进度变化时调用。只在「全屏 + 弹幕开 + 看模式」下由调用方驱动。 */
    fun update(cid: Long, positionMs: Long, durationMs: Long) {
        if (cid <= 0L) return
        if (cid != this.cid) {
            job?.cancel()
            job = null
            this.cid = cid
            requested.clear()
            _items.value = emptyList()
        }
        if (job?.isActive == true) return
        val segments = resolveDanmakuSegmentsToLoad(positionMs, durationMs, requested)
        if (segments.isEmpty()) return
        requested += segments
        val forCid = cid
        job = scope.launch {
            for (segment in segments) {
                val result = fetch(forCid, segment)
                if (forCid != this@DanmakuLoader.cid) return@launch
                result.onSuccess { page -> _items.value = _items.value + page }
                // 失败：段号已记在 requested 里，不再请求；弹幕缺一段，播放不受影响
                if (result.isFailure) return@launch
            }
        }
    }
}
