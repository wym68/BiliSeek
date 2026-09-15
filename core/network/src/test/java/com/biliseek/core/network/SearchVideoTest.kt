package com.biliseek.core.network

import com.biliseek.core.network.dto.SearchVideo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchVideoTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `badgePay defaults to false when absent`() {
        val raw = """{"bvid":"BV1xx411c7mD","title":"test","author":"UP","mid":123}"""
        val video = json.decodeFromString<SearchVideo>(raw)
        assertEquals(false, video.badgePay)
    }

    @Test
    fun `badgePay parses value of true`() {
        val raw = """{"bvid":"BV1xx411c7mD","title":"test","author":"UP","mid":123,"badgepay":true}"""
        val video = json.decodeFromString<SearchVideo>(raw)
        assertEquals(true, video.badgePay)
    }

    @Test
    fun `badgePay parses value of false`() {
        val raw = """{"bvid":"BV1xx411c7mD","title":"test","author":"UP","mid":123,"badgepay":false}"""
        val video = json.decodeFromString<SearchVideo>(raw)
        assertEquals(false, video.badgePay)
    }
}
