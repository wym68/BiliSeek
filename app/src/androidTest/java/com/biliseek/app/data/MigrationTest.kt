package com.biliseek.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库迁移测试。
 *
 * **在第一版就把迁移机制跑通**，而不是等真正需要时才补——
 * 歌单/排序/播放历史的 schema 一定会演进（BBPlayer 积累了 6 个迁移文件），
 * 那时再补迁移的代价包括真实的用户数据丢失风险。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BiliSeekDatabase::class.java,
    )

    @Test
    fun migrate1To2_保留既有数据并新增封面列() {
        // 建 v1 库并写入一条数据
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO track (id, bvid, cid, title, author, durationSec)
                VALUES (1, 'BV1xx411c7mD', 100, '测试标题', '测试UP', 233)
                """.trimIndent(),
            )
        }

        // 执行迁移
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            BiliSeekDatabase.MIGRATION_1_2,
        )

        // 旧数据完整保留
        db.query("SELECT bvid, cid, title, author, durationSec, coverUrl FROM track WHERE id = 1")
            .use { cursor ->
                assertTrue("迁移后旧数据丢失", cursor.moveToFirst())
                assertEquals("BV1xx411c7mD", cursor.getString(0))
                assertEquals(100L, cursor.getLong(1))
                assertEquals("测试标题", cursor.getString(2))
                assertEquals("测试UP", cursor.getString(3))
                assertEquals(233L, cursor.getLong(4))
                // 新列有默认值，不是 null
                assertEquals("", cursor.getString(5))
            }
    }

    @Test
    fun migrate2To3_保留既有数据并新增下载表() {
        // 建 v2 库并写入一条歌单数据
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO playlist (id, name, createdAt, updatedAt)
                VALUES (1, '测试歌单', 1000, 1000)
                """.trimIndent(),
            )
        }

        // 执行迁移
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            BiliSeekDatabase.MIGRATION_2_3,
        )

        // 旧数据完整保留
        db.query("SELECT name, createdAt FROM playlist WHERE id = 1")
            .use { cursor ->
                assertTrue("迁移后旧数据丢失", cursor.moveToFirst())
                assertEquals("测试歌单", cursor.getString(0))
                assertEquals(1000L, cursor.getLong(1))
            }

        // 新表结构正确，且能写入一条记录
        db.execSQL(
            """
            INSERT INTO download (id, bvid, cid, title, author, coverUrl, durationSec,
                content, location, safTreeUri, audioFile, videoFile, fileSize,
                status, error, downloadedAt)
            VALUES (1, 'BV1xx411c7mD', 100, '测试下载', '测试UP', '', 233,
                'AUDIO', 'PRIVATE', '', 'file:///x/1.m4a', '', 12345,
                'DONE', '', 1000)
            """.trimIndent(),
        )
        db.query("SELECT bvid, cid, status, location, audioFile FROM download WHERE id = 1")
            .use { cursor ->
                assertTrue("下载表写入失败", cursor.moveToFirst())
                assertEquals("BV1xx411c7mD", cursor.getString(0))
                assertEquals(100L, cursor.getLong(1))
                assertEquals("DONE", cursor.getString(2))
                assertEquals("PRIVATE", cursor.getString(3))
                assertEquals("file:///x/1.m4a", cursor.getString(4))
            }

        // 唯一索引生效：同一 bvid+cid 不能重复
        db.execSQL(
            """
            INSERT OR IGNORE INTO download (id, bvid, cid, title, author, coverUrl, durationSec,
                content, location, safTreeUri, audioFile, videoFile, fileSize,
                status, error, downloadedAt)
            VALUES (2, 'BV1xx411c7mD', 100, '重复', '', '', 0,
                'AUDIO', 'PRIVATE', '', '', '', 0,
                'DONE', '', 0)
            """.trimIndent(),
        )
        db.query("SELECT COUNT(*) FROM download")
            .use { cursor ->
                cursor.moveToFirst()
                assertEquals("唯一索引未生效", 1L, cursor.getLong(0))
            }
    }

    @Test
    fun migrate3To4_保留既有数据并新增收藏夹目录表() {
        // 建 v3 库并写入一条下载记录（v3 的典型用户数据）
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO download (id, bvid, cid, title, author, coverUrl, durationSec,
                    content, location, safTreeUri, audioFile, videoFile, fileSize,
                    status, error, downloadedAt)
                VALUES (1, 'BV1xx411c7mD', 100, '测试下载', '测试UP', '', 233,
                    'AUDIO', 'PRIVATE', '', 'file:///x/1.m4a', '', 12345,
                    'DONE', '', 1000)
                """.trimIndent(),
            )
        }

        // 执行迁移
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            BiliSeekDatabase.MIGRATION_3_4,
        )

        // 旧数据完整保留
        db.query("SELECT bvid, cid, status FROM download WHERE id = 1")
            .use { cursor ->
                assertTrue("迁移后旧数据丢失", cursor.moveToFirst())
                assertEquals("BV1xx411c7mD", cursor.getString(0))
                assertEquals(100L, cursor.getLong(1))
                assertEquals("DONE", cursor.getString(2))
            }

        // 新表结构正确，且能写入一条目录快照
        db.execSQL(
            """
            INSERT INTO favorite_folder (id, mediaId, title, coverUrl, mediaCount, syncedAt)
            VALUES (1, 3001, '我的收藏', '//i0.hdslb.com/x.jpg', 42, 1000)
            """.trimIndent(),
        )
        db.query("SELECT mediaId, title, mediaCount, syncedAt FROM favorite_folder WHERE id = 1")
            .use { cursor ->
                assertTrue("收藏夹目录写入失败", cursor.moveToFirst())
                assertEquals(3001L, cursor.getLong(0))
                assertEquals("我的收藏", cursor.getString(1))
                assertEquals(42, cursor.getInt(2))
                assertEquals(1000L, cursor.getLong(3))
            }

        // 唯一索引生效：同一 mediaId 不能重复
        db.execSQL(
            """
            INSERT OR IGNORE INTO favorite_folder (id, mediaId, title, coverUrl, mediaCount, syncedAt)
            VALUES (2, 3001, '重复', '', 0, 0)
            """.trimIndent(),
        )
        db.query("SELECT COUNT(*) FROM favorite_folder")
            .use { cursor ->
                cursor.moveToFirst()
                assertEquals("mediaId 唯一索引未生效", 1L, cursor.getLong(0))
            }
    }

    @Test
    fun migrate4To5_保留既有数据并新增访问计数表() {
        // 建 v4 库并写入一条收藏夹目录数据（v4 的典型用户数据）
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO favorite_folder (id, mediaId, title, coverUrl, mediaCount, syncedAt)
                VALUES (1, 3001, '我的收藏', '//i0.hdslb.com/x.jpg', 42, 1000)
                """.trimIndent(),
            )
        }

        // 执行迁移
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            BiliSeekDatabase.MIGRATION_4_5,
        )

        // 旧数据完整保留
        db.query("SELECT mediaId, title FROM favorite_folder WHERE id = 1")
            .use { cursor ->
                assertTrue("迁移后旧数据丢失", cursor.moveToFirst())
                assertEquals(3001L, cursor.getLong(0))
                assertEquals("我的收藏", cursor.getString(1))
            }

        // 新表结构正确，且能写入一条访问记录
        db.execSQL(
            """
            INSERT INTO up_visit (mid, visitCount, lastVisitMs)
            VALUES (12345, 7, 1000)
            """.trimIndent(),
        )
        db.query("SELECT mid, visitCount, lastVisitMs FROM up_visit WHERE mid = 12345")
            .use { cursor ->
                assertTrue("访问表写入失败", cursor.moveToFirst())
                assertEquals(12345L, cursor.getLong(0))
                assertEquals(7L, cursor.getLong(1))
                assertEquals(1000L, cursor.getLong(2))
            }
    }

    @Test
    fun migrate5To6_保留既有数据并新增AI总结表() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL("INSERT INTO up_visit (mid, visitCount, lastVisitMs) VALUES (1, 2, 3)")
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            6,
            true,
            BiliSeekDatabase.MIGRATION_5_6,
        )

        db.query("SELECT visitCount FROM up_visit WHERE mid = 1").use { cursor ->
            assertTrue("迁移后旧数据丢失", cursor.moveToFirst())
            assertEquals(2L, cursor.getLong(0))
        }

        val insert = """
            INSERT OR REPLACE INTO ai_summary (bvid, cid, title, pageTitle, author, upMid, coverUrl, durationMs,
                style, customPrompt, sourceKind, subtitleUrl, subtitleLan, slot, models, status, failureKind,
                hasTranscript, error, createdAt, updatedAt)
            VALUES ('BV1x', 10, 't', '', 'a', 0, '', 60000, 'OUTLINE', '', 'SUBTITLE', '', '', 'PRIMARY', '',
                'QUEUED', '', 0, '', 1, 1)
        """.trimIndent()
        db.execSQL(insert)
        db.execSQL(insert)
        db.query("SELECT COUNT(*) FROM ai_summary").use { cursor ->
            cursor.moveToFirst()
            assertEquals("(bvid, cid) 唯一索引未生效", 1L, cursor.getLong(0))
        }
    }

    @Test
    fun migrate6To7_保留AI总结并允许同一视频多个版本() {
        val insert = """
            INSERT INTO ai_summary (bvid, cid, title, pageTitle, author, upMid, coverUrl, durationMs,
                style, customPrompt, sourceKind, subtitleUrl, subtitleLan, slot, models, status, failureKind,
                hasTranscript, error, createdAt, updatedAt)
            VALUES ('BV1x', 10, 't', '', 'a', 0, '', 60000, 'OUTLINE', '', 'SUBTITLE', '', '', 'PRIMARY', '',
                'DONE', '', 1, '', 1, 1)
        """.trimIndent()
        helper.createDatabase(TEST_DB, 6).use { db -> db.execSQL(insert) }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            7,
            true,
            BiliSeekDatabase.MIGRATION_6_7,
        )

        db.query("SELECT COUNT(*) FROM ai_summary").use { cursor ->
            cursor.moveToFirst()
            assertEquals("迁移后旧总结丢失", 1L, cursor.getLong(0))
        }
        db.execSQL(insert)
        db.query("SELECT COUNT(*) FROM ai_summary WHERE bvid = 'BV1x' AND cid = 10").use { cursor ->
            cursor.moveToFirst()
            assertEquals("(bvid, cid) 仍是唯一索引，多版本写不进去", 2L, cursor.getLong(0))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
