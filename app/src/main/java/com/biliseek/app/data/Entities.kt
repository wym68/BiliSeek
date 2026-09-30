package com.biliseek.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地歌单。
 *
 * 远程列表（收藏夹 / 合集 / 搜索结果）**不落库**——见《架构设计篇》§6.2。
 * 只有用户主动收藏进本地歌单才写数据库。
 */
@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 一个可播放条目。以 bvid+cid 唯一标识一个分 P。 */
@Entity(
    tableName = "track",
    indices = [Index(value = ["bvid", "cid"], unique = true)],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bvid: String,
    val cid: Long,
    val title: String,
    val author: String,
    val coverUrl: String,
    val durationSec: Long,
)

/** 歌单与条目的关联。`sortKey` 用浮点，插入中间位置时无需重排整表。 */
@Entity(
    tableName = "playlist_track",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId"), Index("playlistId")],
)
data class PlaylistTrackEntity(
    val playlistId: Long,
    val trackId: Long,
    val sortKey: Double,
)

/** 播放历史。首页「最近播放」与续播都依赖它。 */
@Entity(tableName = "play_history", indices = [Index(value = ["bvid", "cid"], unique = true)])
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bvid: String,
    val cid: Long,
    val title: String,
    val author: String,
    val coverUrl: String,
    val durationSec: Long,
    val positionMs: Long,
    val playedAt: Long,
)

/**
 * 收藏夹目录快照（M11）。
 *
 * 只有**目录**落库（v4），收藏夹**内容**不落库、分页拉取——
 * 见《架构设计篇》§6.2 落库边界。目录很小，落库换来音乐库页秒开、
 * 显示上次同步时间、断网时目录仍可见；内容量大且易变，落库要处理
 * 增量/删除/冲突，收益近零。
 */
@Entity(tableName = "favorite_folder", indices = [Index(value = ["mediaId"], unique = true)])
data class FavoriteFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** B 站收藏夹 media_id。 */
    val mediaId: Long,
    val title: String,
    val coverUrl: String,
    /** 收藏夹内媒体数（展示用）。 */
    val mediaCount: Int,
    /** 上次同步时间戳。 */
    val syncedAt: Long,
)

/** 搜索历史。首页空状态的主要内容，上限 10 条。 */
@Entity(tableName = "search_history", indices = [Index(value = ["keyword"], unique = true)])
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val searchedAt: Long,
)

/**
 * UP 主访问计数（动态页「最常访问」栏的排名依据）。
 *
 * 只存 mid + 计数；展示用的昵称/头像始终从关注列表（FollowingUp）取。
 * 同一天内重复访问只更新 [lastVisitMs] 不 +1（同日去重，见 [UpVisitPolicy]）。
 */
@Entity(tableName = "up_visit")
data class UpVisitEntity(
    @PrimaryKey val mid: Long,
    val visitCount: Long,
    val lastVisitMs: Long,
)

/**
 * 下载记录（M9）。一个 bvid+cid 唯一对应一条下载。
 *
 * 删除语义按 [location] 区分（M9 §9.5）：
 * - `PRIVATE`：私有目录，删记录时**连文件一起删**
 * - `PUBLIC` / `SAF`：用户自己的文件，删记录**不碰文件**
 */
@Entity(tableName = "download", indices = [Index(value = ["bvid", "cid"], unique = true)])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bvid: String,
    val cid: Long,
    val title: String,
    val author: String,
    val coverUrl: String,
    val durationSec: Long,
    /** 下载内容：AUDIO / VIDEO */
    val content: String,
    /** 保存位置：PRIVATE / PUBLIC / SAF */
    val location: String,
    /** SAF 授权树的 uri（[location] 为 SAF 时非空，离线库靠它找文件） */
    val safTreeUri: String,
    /** 播放用 URI：私有目录为 file://，公共 / SAF 为 content:// */
    val audioFile: String,
    /** 视频文件。只下了音频时为空字符串 */
    val videoFile: String,
    val fileSize: Long,
    /** 状态机：QUEUED / DOWNLOADING / DONE / FAILED */
    val status: String,
    /** 失败原因，仅 FAILED 时非空 */
    val error: String,
    val downloadedAt: Long,
)

/** 下载状态常量。存字符串便于 SQL 过滤（status = 'DONE'）。 */
object DownloadStatus {
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"

    /** 中断 / 失败后可重试的状态。 */
    val RETRYABLE = setOf(QUEUED, FAILED)
}

/** 下载内容类型。 */
object DownloadContent {
    const val AUDIO = "AUDIO"
    const val VIDEO = "VIDEO"
}

/** 保存位置类型。 */
object DownloadLocation {
    const val PRIVATE = "PRIVATE"
    const val PUBLIC = "PUBLIC"
    const val SAF = "SAF"
}

/**
 * AI 总结索引（工单 AI-5）。正文与原文存在 `filesDir/ai_summary/{bvid}_{cid}/`，表里只存索引与任务状态。
 *
 * 同一 (bvid, cid) 可以有多个「版本」（多行）：重新生成不覆盖旧版本，用户自己决定保留哪几个、删掉哪几个。
 * 每个版本自己的 summary.md 与总结断点在 `v{id}/` 子目录；原文（transcript.json）与识别断点、临时音频
 * 是这条视频共用的原料，放在视频目录，所有版本删光时才整目录删除。
 */
@Entity(tableName = "ai_summary", indices = [Index(value = ["bvid", "cid"])])
data class AiSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bvid: String,
    val cid: Long,
    val title: String,
    /** 分 P 标题（单 P 为空）。 */
    val pageTitle: String,
    val author: String,
    val upMid: Long,
    val coverUrl: String,
    val durationMs: Long,
    /** [com.biliseek.app.summary.StyleChoice] 的 name。 */
    val style: String,
    val customPrompt: String,
    /** 原文来源：[AiSummarySource]。 */
    val sourceKind: String,
    /** 字幕来源时的字幕地址与语言（断点续做时重新取字幕用）。 */
    val subtitleUrl: String,
    val subtitleLan: String,
    /** 用哪套模型配置：PRIMARY / BACKUP。 */
    val slot: String,
    /** 实际参与生成的模型，换行分隔。 */
    val models: String,
    /** 状态机：[AiSummaryStatus]。 */
    val status: String,
    /** 失败类别：[com.biliseek.app.summary.FailureKind] 的 name，未失败为空。 */
    val failureKind: String,
    /** 原文是否已落盘（「原文已就绪、总结失败」时可看原文、可生成原文笔记）。 */
    val hasTranscript: Boolean,
    /** 给用户看的失败说明（截断）。 */
    val error: String,
    val createdAt: Long,
    val updatedAt: Long,
)

object AiSummaryStatus {
    const val QUEUED = "QUEUED"
    const val PREPARING = "PREPARING"
    const val TRANSCRIBING = "TRANSCRIBING"
    const val SUMMARIZING = "SUMMARIZING"
    const val WAITING_NETWORK = "WAITING_NETWORK"
    const val DONE = "DONE"
    const val FAILED = "FAILED"

    /** 进程被杀后需要重新入队续做的状态。 */
    val RESUMABLE = listOf(QUEUED, PREPARING, TRANSCRIBING, SUMMARIZING, WAITING_NETWORK)
}

object AiSummarySource {
    /** 执行时再决定（多 P 批量）：先查字幕，没有再本地识别。 */
    const val AUTO = "AUTO"
    const val OFFICIAL = "OFFICIAL"
    const val SUBTITLE = "SUBTITLE"
    const val LOCAL_ASR = "LOCAL_ASR"
}
