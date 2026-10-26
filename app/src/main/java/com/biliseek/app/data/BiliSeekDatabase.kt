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
        ListenDayEntity::class,
        ListenEntryEntity::class,
    ],
    version = 11,
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
    abstract fun listenDayDao(): ListenDayDao
    abstract fun listenEntryDao(): ListenEntryDao

    companion object {
        @Volatile
        private var instance: BiliSeekDatabase? = null

        fun get(context: Context): BiliSeekDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): BiliSeekDatabase =
            Room.databaseBuilder(context, BiliSeekDatabase::class.java, "biliseek.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
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
         * 正文与原文在私有目录的文件里，表里只存索引与任务状态；同一 (bvid, cid) 可有多个版本（v6→v7 起）。
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

        /**
         * v6 → v7：AI 总结支持同一视频保存多个版本，(bvid, cid) 的索引由唯一改为普通索引。
         * 索引名不变（Room 按名字校验）；表结构与数据都不动。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_ai_summary_bvid_cid`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_summary_bvid_cid` ON `ai_summary` (`bvid`, `cid`)")
            }
        }

        /**
         * v7 → v8：新增每天的真实收听时长表（日播放记录页的「当日听歌时长」）。
         *
         * 之前那个数是把当天播过的条目的完整时长相加；`play_history` 每个视频只记最后一次播放，
         * 算不出真的听了多久，只能从这一版起开始记。旧的日子没有数据，页面显示「未统计」。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `listen_day` " +
                        "(`epochDay` INTEGER NOT NULL, `listenedMs` INTEGER NOT NULL, PRIMARY KEY(`epochDay`))",
                )
            }
        }

        /**
         * v8 → v9：本地列表加自定义图标（存预设图标的 key，空串 = 默认图标）。
         * 写法同 [MIGRATION_1_2]：带默认值加列，旧列表都是默认图标。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlist ADD COLUMN icon TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v9 → v10：按视频、按天记收听时长（listen_entry）。只建表；listen_day 保留给 10-07 以前的日子回退。
         * 建表语句与导出的 `10.json` 一致。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `listen_entry` (`epochDay` INTEGER NOT NULL, `bvid` TEXT NOT NULL, " +
                        "`cid` INTEGER NOT NULL, `listenedMs` INTEGER NOT NULL, `lastListenedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`epochDay`, `bvid`, `cid`))",
                )
            }
        }

        /**
         * v10 → v11：下载记录加画质（qn）。写法同 [MIGRATION_8_9]：带默认值加列，旧记录都是自动。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download ADD COLUMN videoQn INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
