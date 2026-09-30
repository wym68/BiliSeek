package com.biliseek.app.summary

import com.biliseek.app.data.AiSummarySource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 总结的多版本规则（纯函数）。同一条视频（某一 P）可以有多个版本：重新生成不覆盖旧版本，
 * 用户自己决定保留哪几个、删掉哪几个。
 */
object SummaryVersionPolicy {

    /**
     * 阅读页该显示哪个版本。[ids] 新的在前；[requested] 是用户点选的版本，
     * 不在列表里（已被删除）时退回最新的；没有任何版本返回 null。
     */
    fun select(ids: List<Long>, requested: Long?): Long? =
        if (requested != null && requested in ids) requested else ids.firstOrNull()

    /** 版本列表里的一行：`详细笔记 · 09-30 15:02`；官方总结没有风格，直接叫「B 站官方总结」。 */
    fun label(styleName: String, sourceKind: String, createdAt: Long): String {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(createdAt))
        val head = if (sourceKind == AiSummarySource.OFFICIAL) {
            "B 站官方总结"
        } else {
            StyleChoice.entries.firstOrNull { it.name == styleName }?.label ?: styleName
        }
        return "$head · $time"
    }

    /** 「第 n 个 / 共 m 个」，n 从新到旧数（最新的是第 m 个）以匹配用户的时间直觉：越新编号越大。 */
    fun ordinal(ids: List<Long>, id: Long): Int {
        val index = ids.indexOf(id)
        return if (index < 0) 0 else ids.size - index
    }

    /**
     * 新版本生成前，视频共用的原文要不要作废。原文的来源与这次要用的来源不同（例如之前用字幕、
     * 这次改本地识别）才作废；[JobSource.Auto]（批量，执行时才决定来源）一律沿用。
     *
     * @param transcriptSourceKind 现存原文对应版本的来源；没有原文为 null
     */
    fun shouldDiscardTranscript(transcriptSourceKind: String?, newSourceKind: String): Boolean =
        transcriptSourceKind != null &&
            newSourceKind != AiSummarySource.AUTO &&
            transcriptSourceKind != AiSummarySource.AUTO &&
            transcriptSourceKind != newSourceKind

    /**
     * 官方总结的「保存」：这条视频已有官方版本就在原地更新（内容由 B 站决定，重复保存没有意义），
     * 否则新建一个版本。返回要复用的版本 id，null 表示新建。
     */
    fun officialVersionToReplace(versions: List<Pair<Long, String>>): Long? =
        versions.firstOrNull { it.second == AiSummarySource.OFFICIAL }?.first
}

/**
 * 老版本的文件布局迁移：多版本之前 `summary.md` 与 `checkpoint.json` 直接放在视频目录里，
 * 现在归各版本自己的 `v{id}/`。迁移是幂等的：目标已存在就不覆盖，源不存在就什么也不做。
 */
object SummaryLayoutMigration {
    private val FILES = listOf("summary.md", "checkpoint.json")

    /** 返回是否移动了任何文件。 */
    fun moveLegacy(videoDir: File, versionDir: File): Boolean {
        var moved = false
        for (name in FILES) {
            val source = File(videoDir, name)
            val target = File(versionDir, name)
            if (!source.exists() || target.exists()) continue
            versionDir.mkdirs()
            if (source.renameTo(target)) moved = true
        }
        return moved
    }
}
