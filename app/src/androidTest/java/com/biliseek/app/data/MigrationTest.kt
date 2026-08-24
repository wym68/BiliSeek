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

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
