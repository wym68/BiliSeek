package com.biliseek.app.download

/**
 * 下载完成的浮框提示：比较前后两次任务列表，找出「刚下完」的任务并给出文案。纯函数，配单测。
 *
 * 只认「上一次列表里存在、且不是已完成」→「这一次已完成」的转变：
 * App 启动时首次加载出的已完成记录不算（没有上一次），已完成的记录被刷新也不算。
 */
object DownloadNoticePolicy {

    fun newlyCompleted(previous: List<DownloadTask>, current: List<DownloadTask>): List<DownloadTask> {
        val wasPending = previous.filter { !it.isDone }.mapTo(HashSet()) { it.id }
        return current.filter { it.isDone && it.id in wasPending }
    }

    /** 一条：「下载完成：标题」；多条同时完成：「N 个下载已完成」；没有：null。 */
    fun completionMessage(previous: List<DownloadTask>, current: List<DownloadTask>): String? {
        val done = newlyCompleted(previous, current)
        return when (done.size) {
            0 -> null
            1 -> "下载完成：${done.first().title.ifBlank { "未命名" }}"
            else -> "${done.size} 个下载已完成"
        }
    }
}
