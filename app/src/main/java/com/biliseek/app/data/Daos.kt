package com.biliseek.app.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 歌单里的一条，含关联的排序键。 */
data class PlaylistTrack(
    @Embedded val track: TrackEntity,
    val sortKey: Double,
)

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlist ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlist SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE playlist SET name = :name, icon = :icon, updatedAt = :now WHERE id = :id")
    suspend fun edit(id: Long, name: String, icon: String, now: Long)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM playlist_track WHERE playlistId = :id")
    fun observeCount(id: Long): Flow<Int>

    /** 本地歌单走 Flow（增删改自动刷新）；远程列表走分页，两者不强求统一。 */
    @Transaction
    @Query(
        """
        SELECT t.*, pt.sortKey AS sortKey FROM track t
        INNER JOIN playlist_track pt ON pt.trackId = t.id
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.sortKey ASC
        """,
    )
    fun observeTracks(playlistId: Long): Flow<List<PlaylistTrack>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrack(track: TrackEntity): Long

    @Query("SELECT * FROM track WHERE bvid = :bvid AND cid = :cid")
    suspend fun findTrack(bvid: String, cid: Long): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun link(link: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_track WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun unlink(playlistId: Long, trackId: Long)

    @Query("SELECT MAX(sortKey) FROM playlist_track WHERE playlistId = :playlistId")
    suspend fun maxSortKey(playlistId: Long): Double?

    @Query("UPDATE playlist_track SET sortKey = :sortKey WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun updateSortKey(playlistId: Long, trackId: Long, sortKey: Double)
}

@Dao
interface HistoryDao {

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<PlayHistoryEntity>>

    @Query("SELECT * FROM play_history WHERE bvid = :bvid AND cid = :cid")
    suspend fun find(bvid: String, cid: Long): PlayHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PlayHistoryEntity)

    @Query("DELETE FROM play_history")
    suspend fun clear()

    /** 播放热力图：按天聚合的播放次数。 */
    @Query(
        """
        SELECT playedAt / 86400000 AS day, COUNT(*) AS count
        FROM play_history GROUP BY day ORDER BY day DESC LIMIT :days
        """,
    )
    fun observeHeatmap(days: Int = 90): Flow<List<DayCount>>

    /**
     * 拉取 [cutoffMs] 之后的全部播放记录，供 UI 按**本地日期**聚合热力图。
     *
     * 为什么不用 SQL 直接 GROUP BY：`playedAt / 86400000` 是 UTC 天数，中国用户
     * （UTC+8）凌晨 0–7 点的播放会被归到前一天。改在 Kotlin 层用 `java.time`
     * 按设备时区分组，时区正确且空天可补 0。
     */
    @Query("SELECT * FROM play_history WHERE playedAt >= :cutoffMs ORDER BY playedAt")
    fun observeSince(cutoffMs: Long): Flow<List<PlayHistoryEntity>>

    /** 某天的播放记录（日历史页用）。本地日 [dayStartMs] → [dayStartMs] + 24h。 */
    @Query(
        """
        SELECT * FROM play_history
        WHERE playedAt >= :dayStartMs AND playedAt < :dayEndMs
        ORDER BY playedAt DESC
        """,
    )
    fun observeDay(dayStartMs: Long, dayEndMs: Long): Flow<List<PlayHistoryEntity>>
}

data class DayCount(val day: Long, val count: Int)

/**
 * 每天的真实收听时长。累加分两步（先保证有这一天的行，再加），由仓库包在一个事务里：
 * minSdk 26 的 SQLite（3.18）还不支持 `INSERT … ON CONFLICT DO UPDATE`。
 */
@Dao
interface ListenDayDao {

    @Query("INSERT OR IGNORE INTO listen_day (epochDay, listenedMs) VALUES (:epochDay, 0)")
    suspend fun ensureDay(epochDay: Long)

    @Query("UPDATE listen_day SET listenedMs = listenedMs + :ms WHERE epochDay = :epochDay")
    suspend fun addListened(epochDay: Long, ms: Long)

    /** 某天的收听毫秒数；这天没有记录为 null。 */
    @Query("SELECT listenedMs FROM listen_day WHERE epochDay = :epochDay")
    fun observe(epochDay: Long): Flow<Long?>
}

@Dao
interface DownloadDao {

    /** 「已下载」分区：只列完成的。 */
    @Query("SELECT * FROM download WHERE status = 'DONE' ORDER BY downloadedAt DESC")
    fun observeDone(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download WHERE bvid = :bvid AND cid = :cid")
    suspend fun find(bvid: String, cid: Long): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity): Long

    @Query("DELETE FROM download WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM download")
    suspend fun clear()

    /** 服务重启后捞回中断的任务（续传 / 重试）。 */
    @Query("SELECT * FROM download WHERE status = :status")
    suspend fun byStatus(status: String): List<DownloadEntity>

    @Query("SELECT * FROM download")
    suspend fun all(): List<DownloadEntity>
}

@Dao
interface AiSummaryDao {

    /** 某条视频（某一 P）的全部版本，新的在前。 */
    @Query("SELECT * FROM ai_summary WHERE bvid = :bvid AND cid = :cid ORDER BY createdAt DESC, id DESC")
    fun observeAll(bvid: String, cid: Long): Flow<List<AiSummaryEntity>>

    @Query("SELECT * FROM ai_summary WHERE bvid = :bvid AND cid = :cid ORDER BY createdAt DESC, id DESC")
    suspend fun findAll(bvid: String, cid: Long): List<AiSummaryEntity>

    /** 最新的一个版本。 */
    @Query("SELECT * FROM ai_summary WHERE bvid = :bvid AND cid = :cid ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun find(bvid: String, cid: Long): AiSummaryEntity?

    /** 这条视频（某一 P）是否已有生成完成的总结——播放页「总结」按钮据此显示为已激活。 */
    @Query("SELECT EXISTS(SELECT 1 FROM ai_summary WHERE bvid = :bvid AND cid = :cid AND status = 'DONE')")
    fun observeHasDone(bvid: String, cid: Long): Flow<Boolean>

    @Query("SELECT COUNT(*) FROM ai_summary WHERE bvid = :bvid AND cid = :cid")
    suspend fun count(bvid: String, cid: Long): Int

    @Query("SELECT * FROM ai_summary")
    suspend fun all(): List<AiSummaryEntity>

    /** 「AI 总结」本地列表用：全部版本，最近更新的在前。 */
    @Query("SELECT * FROM ai_summary ORDER BY updatedAt DESC")
    fun observeEvery(): Flow<List<AiSummaryEntity>>

    /** 原文是这条视频共用的：写入 / 作废时同步所有版本的标记。 */
    @Query("UPDATE ai_summary SET hasTranscript = :value WHERE bvid = :bvid AND cid = :cid")
    suspend fun setHasTranscript(bvid: String, cid: Long, value: Boolean)

    @Query("DELETE FROM ai_summary WHERE bvid = :bvid AND cid = :cid")
    suspend fun deleteByVideo(bvid: String, cid: Long)

    @Query("SELECT * FROM ai_summary WHERE id = :id")
    suspend fun byId(id: Long): AiSummaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AiSummaryEntity): Long

    @Query("DELETE FROM ai_summary WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 队列里下一个要跑的（按入队先后）。 */
    @Query("SELECT * FROM ai_summary WHERE status = 'QUEUED' ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextQueued(): AiSummaryEntity?

    @Query("SELECT * FROM ai_summary WHERE status IN (:statuses)")
    suspend fun byStatuses(statuses: List<String>): List<AiSummaryEntity>
}

@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = SEARCH_HISTORY_LIMIT): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE keyword = :keyword")
    suspend fun delete(keyword: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()

    /** 只保留最近 N 条，多余的裁掉。 */
    @Query(
        """
        DELETE FROM search_history WHERE id NOT IN
        (SELECT id FROM search_history ORDER BY searchedAt DESC LIMIT :limit)
        """,
    )
    suspend fun trim(limit: Int = SEARCH_HISTORY_LIMIT)
}

/**
 * 收藏夹目录快照（M11）。
 *
 * 同步语义是**整体替换**：目录小（几十行），拉取 list-all 后 clear + 全量 upsert，
 * 不做增量——增量要处理删除检测与冲突合并，收益配不上复杂度。
 */
@Dao
interface FavoriteFolderDao {

    @Query("SELECT * FROM favorite_folder ORDER BY syncedAt DESC")
    fun observeAll(): Flow<List<FavoriteFolderEntity>>

    @Query("SELECT * FROM favorite_folder")
    suspend fun all(): List<FavoriteFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteFolderEntity): Long

    /** 从收藏夹里移出一条后就地减一（不为它重新同步整个目录）。 */
    @Query("UPDATE favorite_folder SET mediaCount = MAX(mediaCount - 1, 0) WHERE mediaId = :mediaId")
    suspend fun decrementCount(mediaId: Long)

    @Query("DELETE FROM favorite_folder")
    suspend fun clear()
}

/** 首页空状态的搜索历史上限。 */
const val SEARCH_HISTORY_LIMIT = 15

@Dao
interface UpVisitDao {

    @Query("SELECT * FROM up_visit WHERE mid = :mid")
    suspend fun find(mid: Long): UpVisitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UpVisitEntity)

    @Query("SELECT * FROM up_visit ORDER BY visitCount DESC, lastVisitMs DESC")
    suspend fun all(): List<UpVisitEntity>
}
