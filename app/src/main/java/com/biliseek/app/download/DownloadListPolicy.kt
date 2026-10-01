package com.biliseek.app.download

import com.biliseek.app.data.DownloadStatus

/** 「已下载」页的三段：下载中（含排队）、失败、已完成。 */
data class DownloadSections(
    val active: List<DownloadTask>,
    val failed: List<DownloadTask>,
    val done: List<DownloadTask>,
) {
    val isEmpty: Boolean get() = active.isEmpty() && failed.isEmpty() && done.isEmpty()
}

/**
 * 「已下载」页分段。纯函数，配单测。
 *
 * - 下载中：正在下载的排在排队的前面，同类保持原顺序（任务列表本身按时间倒序）；
 * - 失败：单独一段，段头给「全部重试 / 清除」；
 * - 已完成：保持原顺序（新的在前）。
 */
object DownloadListPolicy {
    fun sections(tasks: List<DownloadTask>): DownloadSections = DownloadSections(
        active = tasks.filter { it.status == DownloadStatus.DOWNLOADING } +
            tasks.filter { it.status == DownloadStatus.QUEUED },
        failed = tasks.filter { it.isFailed },
        done = tasks.filter { it.isDone },
    )
}
