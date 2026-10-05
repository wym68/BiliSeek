package com.biliseek.core.network

import com.biliseek.core.network.internal.ChargeDebugInterceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/** 拦截器整条链路：后面的拦截器直接回一个本地 JSON（不联网），看能不能打出充电日志。 */
class ChargeDebugInterceptorTest {

    private val searchJson = """{"code":0,"data":{"result":[{"bvid":"BV1u1a662Ewa","title":"t","badgepay":false,"is_charge_video":1}]}}"""

    private fun run(contentType: String, body: String): List<String> {
        val lines = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(ChargeDebugInterceptor { lines += it })
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody(contentType.toMediaType()))
                    .build()
            }
            .build()
        client.newCall(Request.Builder().url("https://example.invalid/x/web-interface/wbi/search/type").build())
            .execute().use { it.body.string() }
        return lines
    }

    @Test
    fun `JSON 返回打出一行`() {
        assertEquals(
            listOf("/x/web-interface/wbi/search/type BV1u1a662Ewa 「t」 badgepay=false is_charge_video=1"),
            run("application/json; charset=utf-8", searchJson),
        )
    }
}
