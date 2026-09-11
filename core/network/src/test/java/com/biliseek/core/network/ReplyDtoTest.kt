package com.biliseek.core.network

import com.biliseek.core.network.dto.ReplyData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 评论 DTO 解析：楼中楼预览（rcount/replies）与点赞态（action）。
 * B 站评论接口的标准字段，字段缺失时安全降级为 0 / null，不崩。
 */
class ReplyDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `解析带楼中楼预览与点赞态的评论`() {
        val data = json.decodeFromString<ReplyData>(
            """
            {
              "page": { "count": 1, "num": 1, "size": 20 },
              "replies": [{
                "rpid": 100,
                "oid": 200,
                "mid": 300,
                "ctime": 1700000000,
                "like": 5,
                "rcount": 2,
                "action": 1,
                "content": { "message": "一楼" },
                "member": { "mid": "300", "uname": "楼主", "avatar": "//a.jpg" },
                "replies": [
                  { "rpid": 101, "content": { "message": "沙发" }, "member": { "mid": "301", "uname": "小明" } }
                ]
              }]
            }
            """.trimIndent(),
        )
        val reply = data.replies?.single()
        assertEquals(100L, reply?.rpid)
        assertEquals(2, reply?.rcount)
        assertEquals(1, reply?.action)
        assertEquals(1, reply?.replies?.size)
        assertEquals("沙发", reply?.replies?.get(0)?.content?.message)
    }

    @Test
    fun `字段缺失时安全降级`() {
        val data = json.decodeFromString<ReplyData>(
            """{ "replies": [{ "rpid": 1, "content": { "message": "x" }, "member": { "uname": "y" } }] }""",
        )
        val reply = data.replies?.single()
        assertEquals(0, reply?.rcount)
        assertEquals(0, reply?.action)
        assertNull(reply?.replies)
    }
}
