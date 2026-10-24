package com.biliseek.core.network

import com.biliseek.core.network.dto.UploadedImage
import com.biliseek.core.network.internal.buildReplyPictures
import com.biliseek.core.network.internal.imageFilePart
import com.biliseek.core.network.internal.textParts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * 评论配图：`upload_bfs` 的 multipart 形态 + `reply/add` 的 `pictures` 字段。
 * 用本地拦截器直接回假响应，请求不出本机（AGENTS.md：自动化不碰 B 站）。字段名参考 bilipai CommentRepository.kt:822。
 */
class CommentImageUploadTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `pictures 字段按上传结果顺序拼成 JSON 数组`() {
        val out = buildReplyPictures(
            listOf(
                UploadedImage(url = "https://i0.hdslb.com/bfs/new_dyn/a.jpg", width = 1080, height = 1920, sizeKb = 123.4),
                UploadedImage(url = "https://i0.hdslb.com/bfs/new_dyn/b.png", width = 640, height = 480, sizeKb = 50.0),
            ),
        )
        assertEquals(
            """[{"img_src":"https://i0.hdslb.com/bfs/new_dyn/a.jpg","img_width":1080,"img_height":1920,"img_size":123.4},""" +
                """{"img_src":"https://i0.hdslb.com/bfs/new_dyn/b.png","img_width":640,"img_height":480,"img_size":50.0}]""",
            out,
        )
    }

    @Test
    fun `没有图时不带 pictures`() {
        assertNull(buildReplyPictures(emptyList()))
    }

    @Test
    fun `upload_bfs 发 multipart：file_up 加 biz category csrf，返回解析成 UploadedImage`() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                captured = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"code":0,"message":"0","data":{"image_url":"https://i0.hdslb.com/bfs/new_dyn/x.jpg","image_width":800,"image_height":600,"img_size":88.5}}"""
                            .toResponseBody("application/json".toMediaType()),
                    )
                    .build()
            })
            .build()
        val api = Retrofit.Builder()
            .baseUrl("https://api.bilibili.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BiliApi::class.java)

        val resp = api.uploadBfs(
            imageFilePart(byteArrayOf(1, 2, 3), "image/jpeg", "a.jpg"),
            textParts(mapOf("biz" to "new_dyn", "category" to "daily", "csrf" to "JCT")),
        )

        assertEquals(UploadedImage("https://i0.hdslb.com/bfs/new_dyn/x.jpg", 800, 600, 88.5), resp.data)
        val req = requireNotNull(captured)
        assertEquals("POST", req.method)
        assertEquals("/x/dynamic/feed/draw/upload_bfs", req.url.encodedPath)
        val body = req.body as MultipartBody
        val parts = body.parts.associate { part ->
            val disposition = part.headers?.get("Content-Disposition").orEmpty()
            val name = Regex("name=\"([^\"]+)\"").find(disposition)!!.groupValues[1]
            name to part
        }
        assertEquals(setOf("file_up", "biz", "category", "csrf"), parts.keys)
        assertTrue(parts.getValue("file_up").headers!!.get("Content-Disposition")!!.contains("filename=\"a.jpg\""))
        assertEquals("image/jpeg", parts.getValue("file_up").body.contentType().toString())
        fun text(name: String) = Buffer().also { parts.getValue(name).body.writeTo(it) }.readUtf8()
        assertEquals("new_dyn", text("biz"))
        assertEquals("daily", text("category"))
        assertEquals("JCT", text("csrf"))
    }
}
