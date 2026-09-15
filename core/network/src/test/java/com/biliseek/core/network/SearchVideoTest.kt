package com.biliseek.core.network

import com.biliseek.core.network.dto.SearchVideo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchVideoTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `ugcPay defaults to 0 when absent`() {
        val raw = """{"bvid":"BV1xx411c7mD","title":"test","author":"UP","mid":123}"""
        val video = json.decodeFromString<SearchVideo>(raw)
        assertEquals(0, video.ugcPay)
    }

    @Test
    fun `ugcPay parses value of 1`() {
        val raw = """{"bvid":"BV1xx411c7mD","title":"test","author":"UP","mid":123,"ugc_pay":1}"""
        val video = json.decodeFromString<SearchVideo>(raw)
        assertEquals(1, video.ugcPay)
    }

    @Test
    fun `ugcPay parses value of 0`() {
        val raw = """{"bvid":"BV1xx411c7mD","title":"test","author":"UP","mid":123,"ugc_pay":0}"""
        val video = json.decodeFromString<SearchVideo>(raw)
        assertEquals(0, video.ugcPay)
    }
}
