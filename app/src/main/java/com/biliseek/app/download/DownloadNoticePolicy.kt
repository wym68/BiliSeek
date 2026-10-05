package com.biliseek.app.download

/**
 * 下载完成的浮框提示：比较前后两次任务列表，找出「刚下完」的任务并给出文案。纯函数，配单测。
 *
 * 只认「上一次列表里存在、且不是已完成」→「这一次已完成」的转变：
 * App 启动时首次加载出的已完成记录不算（没有上一次），已完成的记录被刷新也不算。
 *
 * 按视频汇总（用户 2026-10-04 选定）：一个视频还有分 P 在排队、下载或暂停时先不提醒，
 * 它的最后一个分 P 完成时提醒一次「下载完成：视频标题（共 N 个分 P）」。
 */
object DownloadNoticePolicy {

    fun newlyCompleted(previous: List<DownloadTask>, current: List<DownloadTask>): List<DownloadTask> {
        val wasPending = previous.filter { !it.isDone }.mapTo(HashSet()) { it.id }
        return current.filter { it.isDone && it.id in wasPending }
    }

    /**
     * 一个视频：「下载完成：标题」，多个分 P 时「下载完成：视频标题（共 N 个分 P）」；
     * 几个视频同时完成：「N 个下载已完成」；没有：null。
     */
    fun completionMessage(previous: List<DownloadTask>, current: List<DownloadTask>): String? {
        val done = newlyCompleted(previous, current)
        if (done.isEmpty()) return null
        val pending = current.filter { it.isActive || it.isPaused }.mapTo(HashSet()) { it.bvid }
        val finished = done.map { it.bvid }.distinct().filter { it !in pending }
        return when (finished.size) {
            0 -> null
            1 -> {
                val bvid = finished.single()
                val parts = current.filter { it.isDone && it.bvid == bvid }
                if (parts.size > 1) {
                    "下载完成：${splitPartTitle(parts.first().title).first}（共 ${parts.size} 个分 P）"
                } else {
                    "下载完成：${done.first { it.bvid == bvid }.title.ifBlank { "未命名" }}"
                }
            }
            else -> "${finished.size} 个下载已完成"
        }
    }
}
