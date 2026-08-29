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
    ],
    version = 3,
    exportSchema = true,
)
abstract class BiliSeekDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var instance: BiliSeekDatabase? = null

        fun get(context: Context): BiliSeekDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): BiliSeekDatabase =
            Room.databaseBuilder(context, BiliSeekDatabase::class.java, "biliseek.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
    }
}
