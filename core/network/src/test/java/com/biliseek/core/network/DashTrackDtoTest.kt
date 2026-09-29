package com.biliseek.core.network

import com.biliseek.core.network.dto.DashTrack
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DASH 轨道的备用线路解析。
 *
 * playurl 同一个字段常同时给驼峰与下划线两种写法（`baseUrl` / `base_url`、`backupUrl` / `backup_url`），
 * 不同接口路径下只给其中一种。备用线路解析错了不会崩，只会在主线路挂掉时没有退路。
 */
class DashTrackDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `驼峰写法的备用线路`() {
        val track = json.decodeFromString<DashTrack>(
            """{"id":30280,"baseUrl":"https://a/1.m4s","backupUrl":["https://b/1.m4s","https://c/1.m4s"]}""",
        )
        assertEquals(listOf("https://b/1.m4s", "https://c/1.m4s"), track.backupUrls())
    }

    @Test
    fun `只有下划线写法时也能拿到`() {
        val track = json.decodeFromString<DashTrack>(
            """{"id":30280,"base_url":"https://a/1.m4s","backup_url":["https://b/1.m4s"]}""",
        )
        assertEquals("https://a/1.m4s", track.url())
        assertEquals(listOf("https://b/1.m4s"), track.backupUrls())
    }

    @Test
    fun `缺失或为 null 时是空列表，空串被滤掉`() {
        assertEquals(emptyList<String>(), json.decodeFromString<DashTrack>("""{"id":1}""").backupUrls())
        assertEquals(emptyList<String>(), json.decodeFromString<DashTrack>("""{"id":1,"backupUrl":null}""").backupUrls())
        assertEquals(
            listOf("https://b/1.m4s"),
            json.decodeFromString<DashTrack>("""{"id":1,"backupUrl":["","https://b/1.m4s"]}""").backupUrls(),
        )
    }
}
