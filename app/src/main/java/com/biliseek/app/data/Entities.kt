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

/** 搜索历史。首页空状态的主要内容，上限 10 条。 */
@Entity(tableName = "search_history", indices = [Index(value = ["keyword"], unique = true)])
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val searchedAt: Long,
)
