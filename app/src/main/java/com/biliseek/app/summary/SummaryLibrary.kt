package com.biliseek.app.summary

import com.biliseek.app.data.AiSummaryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「AI 总结」本地列表的状态。 */
data class SummaryLibraryState(
    val groups: List<SummaryGroup> = emptyList(),
    val totalBytes: Long = 0,
) {
    val isEmpty: Boolean get() = groups.isEmpty()
}

/**
 * 「AI 总结」本地列表（放在 播放列表 页里，和「已下载」并列）：列出所有存在本机的总结，
 * 显示各自占用的空间，能进去看、能删，防止总结越攒越多占满存储。
 */
class SummaryLibrary(
    private val scope: CoroutineScope,
    private val manager: SummaryManager,
) {
    /** 右滑删除后、撤销期内藏起来的条目（[SummaryLibraryPolicy.key]）。 */
    private val hidden = MutableStateFlow<Set<String>>(emptySet())

    val state: StateFlow<SummaryLibraryState> = combine(
        manager.observeEvery()
            .map { rows ->
                val groups = SummaryLibraryPolicy.group(rows.map { it.toInfo() })
                // 目录扫描是磁盘 IO，放到 IO 线程
                val sized = withContext(Dispatchers.IO) {
                    groups.map { it.copy(sizeBytes = manager.files(it.bvid, it.cid).sizeBytes()) }
                }
                SummaryLibraryState(sized, sized.sumOf { it.sizeBytes })
            },
        hidden,
    ) { state, hiddenKeys -> SummaryLibraryPolicy.withoutHidden(state, hiddenKeys) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), SummaryLibraryState())

    /**
     * 右滑删除这条视频的全部版本（含原文；用户 2026-10-04 要求，同稍后再看、已下载）：先从列表拿掉，提示里可撤销，
     * 过 [REMOVAL_COMMIT_MS] 才真的删文件。
     */
    fun requestDelete(group: SummaryGroup) {
        val key = SummaryLibraryPolicy.key(group)
        hidden.value = hidden.value + key
        val removal = PendingRemoval(key)
        lastRemoval = removal
        removal.job = scope.launch {
            delay(REMOVAL_COMMIT_MS)
            if (lastRemoval === removal) lastRemoval = null
            manager.deleteAll(group.bvid, group.cid)
            // 库里真没了再放出名单（deleteAll 是异步的，早放会闪回来）：以后给同一个视频重新生成总结还要显示
            manager.observeEvery().first { rows -> rows.none { it.bvid == group.bvid && it.cid == group.cid } }
            hidden.value = hidden.value - key
        }
        _removalNotice.tryEmit("已移除「${group.title.ifBlank { "未命名" }}」的总结")
    }

    /** 撤销最近一次右滑删除：还没提交就放回列表。 */
    fun undoDelete() {
        val removal = lastRemoval ?: return
        lastRemoval = null
        removal.job?.cancel()
        hidden.value = hidden.value - removal.key
    }

    private class PendingRemoval(val key: String) {
        var job: Job? = null
    }

    /** 最近一次还没提交的删除，只有它能撤销；更早的照常到点删。 */
    private var lastRemoval: PendingRemoval? = null
    private val _removalNotice = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val removalNotice: SharedFlow<String> = _removalNotice.asSharedFlow()

    /** 清空所有总结。 */
    fun clearAll() {
        state.value.groups.forEach { manager.deleteAll(it.bvid, it.cid) }
    }

    private companion object {
        /** 比撤销提示多留 1 秒才真删（同 RemoteLibraryController）。 */
        const val REMOVAL_COMMIT_MS = com.biliseek.app.remote.RemoteLibraryController.REMOVAL_UNDO_MS + 1_000L
    }

    private fun AiSummaryEntity.toInfo() = SummaryRowInfo(
        bvid = bvid, cid = cid, title = title, pageTitle = pageTitle, author = author,
        coverUrl = coverUrl, durationMs = durationMs, status = status, updatedAt = updatedAt,
    )
}
