package com.biliseek.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        TrackEntity::class,
        PlaylistTrackEntity::class,
        PlayHistoryEntity::class,
        SearchHistoryEntity::class,
        DownloadEntity::class,
        FavoriteFolderEntity::class,
        UpVisitEntity::class,
        AiSummaryEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class BiliSeekDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun favoriteFolderDao(): FavoriteFolderDao
    abstract fun upVisitDao(): UpVisitDao
    abstract fun aiSummaryDao(): AiSummaryDao

    companion object {
        @Volatile
        private var instance: BiliSeekDatabase? = null

        fun get(context: Context): BiliSeekDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): BiliSeekDatabase =
            Room.databaseBuilder(context, BiliSeekDatabase::class.java, "biliseek.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()

        /**
         * v1 → v2：给 track 加封面字段。
         *
         * 这条迁移本身价值不大，真正的目的是**在第一版就把迁移机制跑通**。
         * BBPlayer 积累了 6 个迁移文件（含两次排序键重构），说明歌单/排序/历史的
         * schema 一定会演进——等需要时才补迁移会很痛。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track ADD COLUMN coverUrl TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v2 → v3：新增下载表（M9）。
         *
         * 表行记录保存位置与文件引用，离线库据此知道去哪找文件、
         * 删表行时是否要连文件一起删（删除语义按位置区分）。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bvid` TEXT NOT NULL,
                        `cid` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `author` TEXT NOT NULL,
                        `coverUrl` TEXT NOT NULL,
                        `durationSec` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        `location` TEXT NOT NULL,
                        `safTreeUri` TEXT NOT NULL,
                        `audioFile` TEXT NOT NULL,
                        `videoFile` TEXT NOT NULL,
                        `fileSize` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `error` TEXT NOT NULL,
                        `downloadedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_download_bvid_cid` ON `download` (`bvid`, `cid`)",
                )
            }
        }

        /**
         * v3 → v4：新增收藏夹目录快照表（M11）。
         *
         * 只有目录落库，内容分页拉取（《架构设计篇》§6.2）。
         * 同步语义是整体替换（拉取 list-all 后 clear + 全量 upsert），
         * mediaId 唯一索引保证同一收藏夹只占一行。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorite_folder` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `mediaId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `coverUrl` TEXT NOT NULL,
                        `mediaCount` INTEGER NOT NULL,
                        `syncedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_favorite_folder_mediaId` ON `favorite_folder` (`mediaId`)",
                )
            }
        }

        /**
         * v4 → v5：新增 UP 主访问计数表。
         *
         * 动态页「最常访问」栏的排名依据：筛选/打开空间页/看视频时计数，
         * 同日去重。只存 mid + 计数，展示资料从关注列表取。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `up_visit` (
                        `mid` INTEGER PRIMARY KEY NOT NULL,
                        `visitCount` INTEGER NOT NULL,
                        `lastVisitMs` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v5 → v6：新增 AI 总结索引表（工单 AI-5）。
         *
         * 正文与原文在私有目录的文件里，表里只存索引与任务状态；(bvid, cid) 唯一，重新生成即覆盖。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_summary` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bvid` TEXT NOT NULL,
                        `cid` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `pageTitle` TEXT NOT NULL,
                        `author` TEXT NOT NULL,
                        `upMid` INTEGER NOT NULL,
                        `coverUrl` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `style` TEXT NOT NULL,
                        `customPrompt` TEXT NOT NULL,
                        `sourceKind` TEXT NOT NULL,
                        `subtitleUrl` TEXT NOT NULL,
                        `subtitleLan` TEXT NOT NULL,
                        `slot` TEXT NOT NULL,
                        `models` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `failureKind` TEXT NOT NULL,
                        `hasTranscript` INTEGER NOT NULL,
                        `error` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_summary_bvid_cid` ON `ai_summary` (`bvid`, `cid`)",
                )
            }
        }
    }
}
