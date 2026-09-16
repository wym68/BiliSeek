package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.RelationData
import com.biliseek.core.network.dto.RelationStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RelationStatusSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `attribute 2 means FOLLOWING`() {
        val jsonStr = """{"mid":570064,"attribute":2,"mtime":1787261821,"tag":null,"special":0}"""
        val data = json.decodeFromString<RelationData>(jsonStr)
        assertEquals(RelationStatus.FOLLOWING, data.relationStatus)
    }

    @Test
    fun `attribute 0 means NOT_FOLLOWING`() {
        val jsonStr = """{"mid":570064,"attribute":0,"mtime":0,"tag":null,"special":0}"""
        val data = json.decodeFromString<RelationData>(jsonStr)
        assertEquals(RelationStatus.NOT_FOLLOWING, data.relationStatus)
    }

    @Test
    fun `attribute 6 means FOLLOWING (mutual)`() {
        val jsonStr = """{"mid":570064,"attribute":6,"mtime":1787261821,"tag":"friend","special":0}"""
        val data = json.decodeFromString<RelationData>(jsonStr)
        assertEquals(RelationStatus.FOLLOWING, data.relationStatus)
    }

    @Test
    fun `attribute 1 means UNKNOWN`() {
        val jsonStr = """{"mid":570064,"attribute":1,"mtime":0,"tag":null,"special":0}"""
        val data = json.decodeFromString<RelationData>(jsonStr)
        assertEquals(RelationStatus.UNKNOWN, data.relationStatus)
    }

    @Test
    fun `missing attribute defaults to NOT_FOLLOWING`() {
        val jsonStr = """{"mid":570064}"""
        val data = json.decodeFromString<RelationData>(jsonStr)
        assertEquals(RelationStatus.NOT_FOLLOWING, data.relationStatus)
    }

    @Test
    fun `full BiliResponse with attribute 2`() {
        val jsonStr = """{"code":0,"message":"OK","ttl":1,"data":{"mid":570064,"attribute":2,"mtime":1787261821,"tag":null,"special":0}}"""
        val resp = json.decodeFromString<BiliResponse<RelationData>>(jsonStr)
        assertEquals(0, resp.code)
        assertEquals(RelationStatus.FOLLOWING, resp.data?.relationStatus)
    }

    @Test
    fun `full BiliResponse with attribute 0`() {
        val jsonStr = """{"code":0,"message":"OK","ttl":1,"data":{"mid":570064,"attribute":0,"mtime":0,"tag":null,"special":0}}"""
        val resp = json.decodeFromString<BiliResponse<RelationData>>(jsonStr)
        assertEquals(RelationStatus.NOT_FOLLOWING, resp.data?.relationStatus)
    }

    @Test
    fun `full BiliResponse with attribute 6`() {
        val jsonStr = """{"code":0,"message":"OK","ttl":1,"data":{"mid":570064,"attribute":6,"mtime":1787261821,"tag":"friend","special":0}}"""
        val resp = json.decodeFromString<BiliResponse<RelationData>>(jsonStr)
        assertEquals(RelationStatus.FOLLOWING, resp.data?.relationStatus)
    }
}
