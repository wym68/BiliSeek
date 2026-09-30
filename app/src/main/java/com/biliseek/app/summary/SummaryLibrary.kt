package com.biliseek.app.summary

import com.biliseek.app.data.AiSummaryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    scope: CoroutineScope,
    private val manager: SummaryManager,
) {
    val state: StateFlow<SummaryLibraryState> = manager.observeEvery()
        .map { rows ->
            val groups = SummaryLibraryPolicy.group(rows.map { it.toInfo() })
            // 目录扫描是磁盘 IO，放到 IO 线程
            val sized = withContext(Dispatchers.IO) {
                groups.map { it.copy(sizeBytes = manager.files(it.bvid, it.cid).sizeBytes()) }
            }
            SummaryLibraryState(sized, sized.sumOf { it.sizeBytes })
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), SummaryLibraryState())

    /** 删除这条视频的全部版本（含原文）。 */
    fun delete(bvid: String, cid: Long) = manager.deleteAll(bvid, cid)

    /** 清空所有总结。 */
    fun clearAll() {
        state.value.groups.forEach { manager.deleteAll(it.bvid, it.cid) }
    }

    private fun AiSummaryEntity.toInfo() = SummaryRowInfo(
        bvid = bvid, cid = cid, title = title, pageTitle = pageTitle, author = author,
        coverUrl = coverUrl, durationMs = durationMs, status = status, updatedAt = updatedAt,
    )
}
