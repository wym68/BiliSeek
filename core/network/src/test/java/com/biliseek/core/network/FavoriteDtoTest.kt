package com.biliseek.core.network

import com.biliseek.core.network.dto.FavoriteFolderListData
import com.biliseek.core.network.dto.FavoriteResourceData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏夹 DTO 解析（M11-B）。
 *
 * 与 [BiliNetwork] 同配置的 Json（ignoreUnknownKeys / coerceInputValues / isLenient），
 * 钉住的是「接口字段名映射」与「脏数据回退」——收藏夹接口字段多且文档不全，
 * 解析错了不会崩，只会显示空/错数据，极难排查。
 */
class FavoriteDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `目录解析_字段名正确`() {
        val data = json.decodeFromString<FavoriteFolderListData>(
            """
            {
              "count": 2,
              "list": [
                {
                  "id": 3001,
                  "fid": 3001,
                  "mid": 123456,
                  "title": "默认收藏夹",
                  "cover": "http://i0.hdslb.com/bfs/archive/abc.jpg",
                  "media_count": 3,
                  "attr": 0
                }
              ]
            }
            """.trimIndent(),
        )
        assertEquals(2, data.count)
        assertEquals(1, data.list.size)
        val folder = data.list[0]
        assertEquals(3001L, folder.id)
        assertEquals("默认收藏夹", folder.title)
        assertEquals("http://i0.hdslb.com/bfs/archive/abc.jpg", folder.cover)
        assertEquals(3, folder.mediaCount)
    }

    @Test
    fun `目录解析_缺字段回退默认值`() {
        val data = json.decodeFromString<FavoriteFolderListData>(
            """{"list": [{"id": 3001}]}""",
        )
        assertEquals(1, data.list.size)
        assertEquals(3001L, data.list[0].id)
        assertEquals("", data.list[0].title)
        assertEquals(0, data.list[0].mediaCount)
    }

    @Test
    fun `内容解析_字段名正确`() {
        val data = json.decodeFromString<FavoriteResourceData>(
            """
            {
              "info": { "id": 3001 },
              "medias": [
                {
                  "id": 328991940,
                  "type": 2,
                  "title": "测试视频",
                  "cover": "http://i0.hdslb.com/bfs/archive/x.jpg",
                  "duration": 644,
                  "upper": { "mid": 686127, "name": "籽岷" },
                  "bvid": "BV1CZ4y1T7gC"
                }
              ],
              "has_more": true
            }
            """.trimIndent(),
        )
        assertTrue(data.hasMore)
        assertEquals(1, data.medias.size)
        val media = data.medias[0]
        assertEquals("BV1CZ4y1T7gC", media.bvid)
        assertEquals("测试视频", media.title)
        assertEquals(644L, media.duration)
        assertEquals(2, media.type)
        assertEquals("籽岷", media.upper?.name)
        assertEquals(686127L, media.upper?.mid)
    }

    @Test
    fun `内容解析_upper缺失与脏数据回退`() {
        val data = json.decodeFromString<FavoriteResourceData>(
            """
            {
              "medias": [
                { "bvid": "BV1x", "type": 2, "title": "无UP", "duration": 1 },
                { "bvid": "BV2x", "type": 2 },
                { "type": 2, "title": "无bvid" }
              ],
              "has_more": false
            }
            """.trimIndent(),
        )
        assertFalse(data.hasMore)
        assertNull(data.medias[0].upper)
        assertEquals("", data.medias[1].title)
        assertEquals(0L, data.medias[1].duration)
        assertEquals("", data.medias[2].bvid)
    }

    @Test
    fun `内容解析_列表为null不崩`() {
        val data = json.decodeFromString<FavoriteResourceData>(
            """{"has_more": false}""",
        )
        assertTrue(data.medias.isEmpty())
    }

    /** 10-21 真实返回（官方公开收藏夹「视频播客」，不带 Cookie 读到），裁剪成 3 条。 */
    @Test
    fun `视频播客收藏夹真实样例_未登录可读的结构`() {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("fav_video_podcast_sample.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val r = json.decodeFromString<com.biliseek.core.network.dto.BiliResponse<FavoriteResourceData>>(text)
        assertEquals(0, r.code)
        val data = r.data!!
        assertTrue(data.hasMore)
        assertEquals(listOf("BV1dYaJ6PE2z", "BV1Fhad6GEQL", "BV1j4af6rEN7"), data.medias.map { it.bvid })
        assertTrue(data.medias.all { it.type == 2 })
        assertEquals("于谦", data.medias[0].upper?.name)
    }
}
