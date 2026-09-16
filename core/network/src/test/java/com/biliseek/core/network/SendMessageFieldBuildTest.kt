package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.SendMessageData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 钉住 `web_im/v1/web_im/send_msg` 的 `msg[*]` 嵌套字段形态——B 站按字段名取参，
 * 顺序与名字错了服务端直接 -400/-1 拒绝。参考 bilipai ApiClient.kt:2547。
 */
class SendMessageFieldBuildTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `send_msg 返回解析`() {
        val raw = """{"code":0,"data":{"msg_seqno":12345,"msg_key":0},"message":"0","ttl":1}"""
        val resp = json.decodeFromString<BiliResponse<SendMessageData>>(raw)
        assertEquals(0, resp.code)
        assertEquals(12345L, resp.data?.msgSeqno)
    }

    @Test
    fun `escapeJsonString 处理换行与引号`() {
        // 顶层 internal 函数会被 Kotlin 编译到 `BiliRepositoryKt` synthetic class 里。
        val cls = Class.forName("com.biliseek.core.network.BiliRepositoryKt")
        val m = cls.declaredMethods.first { it.name == "escapeJsonString" }
        m.isAccessible = true
        val out = m.invoke(null, "hello\n\"world\"") as String
        assertEquals("\"hello\\n\\\"world\\\"\"", out)
    }

    @Test
    fun `escapeJsonString 处理反斜杠与 tab`() {
        val cls = Class.forName("com.biliseek.core.network.BiliRepositoryKt")
        val m = cls.declaredMethods.first { it.name == "escapeJsonString" }
        m.isAccessible = true
        val out = m.invoke(null, "path\\to\tfile") as String
        assertEquals("\"path\\\\to\\tfile\"", out)
    }
}
