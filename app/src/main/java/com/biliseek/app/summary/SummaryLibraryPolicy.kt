package com.biliseek.app.summary

/** 「AI 总结」列表分组用的一行摘要（纯数据，与 Room 实体解耦，便于单测）。 */
data class SummaryRowInfo(
    val bvid: String,
    val cid: Long,
    val title: String,
    val pageTitle: String,
    val author: String,
    val coverUrl: String,
    val durationMs: Long,
    /** [com.biliseek.app.data.AiSummaryStatus] 的值。 */
    val status: String,
    val updatedAt: Long,
)

enum class SummaryGroupState {
    /** 至少有一个版本已生成完成，且没有版本在生成。 */
    READY,

    /** 有版本正在排队 / 生成。 */
    RUNNING,

    /** 没有任何完成的版本，最新的一次失败了。 */
    FAILED,
}

/** 列表里的一条视频（某一 P）：它的全部版本合成一行。 */
data class SummaryGroup(
    val bvid: String,
    val cid: Long,
    val title: String,
    val pageTitle: String,
    val author: String,
    val coverUrl: String,
    val durationMs: Long,
    val versionCount: Int,
    val doneCount: Int,
    val state: SummaryGroupState,
    val updatedAt: Long,
    /** 占用的磁盘空间（总结 + 原文 + 断点等），由调用方扫描目录后填入。 */
    val sizeBytes: Long = 0,
)

/**
 * 「AI 总结」本地列表的规则（纯函数）：所有总结都存在本机私有目录里，这个列表让用户看得到
 * 有哪些、各占多少空间，能进去看也能随手删掉，避免空间悄悄越占越多。
 */
object SummaryLibraryPolicy {

    private const val DONE = "DONE"
    private const val FAILED = "FAILED"

    /** 同一 (bvid, cid) 的各版本合成一条；标题、封面等取最新的一个版本；按最近更新排序。 */
    fun group(rows: List<SummaryRowInfo>): List<SummaryGroup> =
        rows.groupBy { it.bvid to it.cid }.map { (_, versions) ->
            val newest = versions.maxBy { it.updatedAt }
            val running = versions.any { it.status != DONE && it.status != FAILED }
            val done = versions.count { it.status == DONE }
            SummaryGroup(
                bvid = newest.bvid,
                cid = newest.cid,
                title = newest.title,
                pageTitle = newest.pageTitle,
                author = newest.author,
                coverUrl = newest.coverUrl,
                durationMs = newest.durationMs,
                versionCount = versions.size,
                doneCount = done,
                state = when {
                    running -> SummaryGroupState.RUNNING
                    done > 0 -> SummaryGroupState.READY
                    else -> SummaryGroupState.FAILED
                },
                updatedAt = newest.updatedAt,
            )
        }.sortedByDescending { it.updatedAt }

    /** 「12.3 MB」「850 KB」「320 B」。 */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> "%.1f MB".format(java.util.Locale.US, bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /** 列表里一行的第二行说明：`3 个版本 · 12.3 KB`；失败 / 生成中时前置状态。 */
    fun subtitle(group: SummaryGroup): String = buildString {
        when (group.state) {
            SummaryGroupState.RUNNING -> append("生成中 · ")
            SummaryGroupState.FAILED -> append("生成失败 · ")
            SummaryGroupState.READY -> Unit
        }
        append(if (group.versionCount > 1) "${group.versionCount} 个版本" else "1 个版本")
        append(" · ")
        append(formatSize(group.sizeBytes))
    }

    /** 清空前的确认文案里要说明的规模。 */
    fun clearAllMessage(groups: List<SummaryGroup>): String {
        val versions = groups.sumOf { it.versionCount }
        val bytes = groups.sumOf { it.sizeBytes }
        return "将删除全部 ${groups.size} 个视频的 $versions 个总结版本及保存的原文，释放约 ${formatSize(bytes)}。此操作不能撤销。"
    }
}
