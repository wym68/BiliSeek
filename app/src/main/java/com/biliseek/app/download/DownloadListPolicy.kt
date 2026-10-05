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

/** 「已完成」段的一项：单条，或同一个视频下完的多个分 P 合成一组（点开进分 P 页）。 */
sealed interface DoneEntry {
    data class Single(val task: DownloadTask) : DoneEntry

    data class Group(
        val bvid: String,
        /** 视频标题（分 P 标题去掉「P几 分P名」）。 */
        val title: String,
        val coverUrl: String,
        /** 按 P 几排序。 */
        val parts: List<DownloadTask>,
    ) : DoneEntry {
        val totalSize: Long get() = parts.sumOf { it.fileSize }
    }
}

/** 分 P 下载的标题是「视频标题 P几 分P名」（`downloadTitleFor`）：取最后一个「 P数字」起的部分。 */
private val PART_SUFFIX = Regex("^(.*\\S)\\s+(P(\\d+)(?:\\s.*)?)$")

/** 分 P 下载的标题拆成（视频标题, 「P几 分P名」）；不是分 P 标题时第二项为 null。 */
fun splitPartTitle(title: String): Pair<String, String?> {
    val match = PART_SUFFIX.find(title.trim()) ?: return title to null
    return match.groupValues[1] to match.groupValues[2]
}

/** 分 P 标题里的 P 几；不是分 P 标题时为 null。 */
private fun partNumber(title: String): Int? = PART_SUFFIX.find(title.trim())?.groupValues?.get(3)?.toIntOrNull()

/** 「下载中」段头的批量操作：有在下或排队的是「全部暂停」，全是暂停的是「全部继续」。 */
enum class ActiveBulkAction { PAUSE_ALL, RESUME_ALL }

/**
 * 「已下载」页分段。纯函数，配单测。
 *
 * - 下载中：正在下载的在前，然后是排队的、暂停的，同类保持原顺序（任务列表本身按时间倒序）；
 * - 失败：单独一段，段头给「全部重试 / 清除」；
 * - 已完成：保持原顺序（新的在前）。
 */
object DownloadListPolicy {
    fun sections(tasks: List<DownloadTask>): DownloadSections = DownloadSections(
        active = tasks.filter { it.status == DownloadStatus.DOWNLOADING } +
            tasks.filter { it.status == DownloadStatus.QUEUED } +
            tasks.filter { it.isPaused },
        failed = tasks.filter { it.isFailed },
        done = tasks.filter { it.isDone },
    )

    /**
     * 「已完成」段的条目：同一个视频下完 2 个及以上分 P 合成一组，组内按 P 几排序；
     * 组放在它最新完成的那一条的位置（列表本身新的在前）。只下了一个分 P 的仍是单条。
     */
    fun doneEntries(done: List<DownloadTask>): List<DoneEntry> =
        done.groupBy { it.bvid }.map { (bvid, parts) ->
            if (parts.size < 2) {
                DoneEntry.Single(parts.single())
            } else {
                val sorted = parts.sortedWith(compareBy(nullsLast()) { partNumber(it.title) })
                DoneEntry.Group(
                    bvid = bvid,
                    title = splitPartTitle(sorted.first().title).first,
                    coverUrl = sorted.first().coverUrl,
                    parts = sorted,
                )
            }
        }

    fun bulkAction(active: List<DownloadTask>): ActiveBulkAction? = when {
        active.any { it.isActive } -> ActiveBulkAction.PAUSE_ALL
        active.any { it.isPaused } -> ActiveBulkAction.RESUME_ALL
        else -> null
    }
}
