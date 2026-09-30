package com.biliseek.core.player

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放会话持久化的序列化与转换。
 *
 * 守住两件已经踩过的坑：
 * - `:core:player` 曾漏 kotlinx-serialization 插件导致 `PersistedSession` 序列化崩溃（55acbc7）。
 * - 队列条目转换必须字段无损，否则恢复出来的曲目缺 UP 主 / 时长，历史与界面就残缺。
 */
class PersistedSessionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleRequest() = PlaybackRequest.Bili(
        bvid = "BV1xx411c7mD",
        cid = 42L,
        title = "示例曲目",
        coverUrl = "https://i0.hdslb.com/cover.jpg",
        author = "示例UP",
        durationSec = 272,
    )

    @Test
    fun `PersistedTrack 与 Bili 请求字段无损往返`() {
        val request = sampleRequest()
        val back = PersistedTrack.fromBiliRequest(request).toBiliRequest()
        assertEquals(request, back)
    }

    @Test
    fun `cid 为空的请求往返后落为 0`() {
        val request = sampleRequest().copy(cid = null)
        val back = PersistedTrack.fromBiliRequest(request).toBiliRequest()
        // toBiliRequest 永远给出非空 cid（0 表示未定，取流层会补第一个分 P）
        assertEquals(0L, back.cid)
    }

    @Test
    fun `PersistedSession JSON 往返相等`() {
        val session = PersistedSession(
            queue = listOf(
                PersistedTrack.fromBiliRequest(sampleRequest()),
                PersistedTrack.fromBiliRequest(sampleRequest().copy(bvid = "BV2", cid = 7L, title = "第二首")),
            ),
            queueIndex = 1,
            positionMs = 123_456L,
            playMode = PlayMode.REPEAT_ALL,
            presentationMode = PresentationMode.INLINE,
        )
        val decoded = json.decodeFromString<PersistedSession>(json.encodeToString(session))
        assertEquals(session, decoded)
    }

    @Test
    fun `枚举以名字而非序号序列化，重排枚举也能解码`() {
        val session = PersistedSession(
            queue = listOf(PersistedTrack.fromBiliRequest(sampleRequest())),
            queueIndex = 0,
            positionMs = 0L,
            playMode = PlayMode.SHUFFLE,
            presentationMode = PresentationMode.PIP,
        )
        val encoded = json.encodeToString(session)
        // kotlinx-serialization 默认按枚举常量名序列化——JSON 里应出现名字
        assert(encoded.contains("SHUFFLE"))
        assert(encoded.contains("PIP"))
    }

    @Test
    fun `两层队列标记随会话往返`() {
        val session = PersistedSession(
            queue = listOf(
                PersistedTrack.fromBiliRequest(sampleRequest(), pinned = true),
                PersistedTrack.fromBiliRequest(sampleRequest().copy(bvid = "BV2"), pinned = false, expandParts = true),
            ),
            queueIndex = 0,
            positionMs = 0L,
            playMode = PlayMode.SEQUENTIAL,
            presentationMode = PresentationMode.NONE,
        )
        val decoded = json.decodeFromString<PersistedSession>(json.encodeToString(session))
        assertEquals(session, decoded)
    }

    /** 旧版本存下的队列没有两层标记：恢复后一律当固定，点击播放不能把它们冲掉。 */
    @Test
    fun `旧版本会话没有标记时默认固定、不展开`() {
        val legacy = """
            {"queue":[{"bvid":"BV1","cid":42,"title":"t","author":"a","coverUrl":"","durationSec":10}],
             "queueIndex":0,"positionMs":0,"playMode":"SEQUENTIAL","presentationMode":"NONE"}
        """.trimIndent()
        val track = json.decodeFromString<PersistedSession>(legacy).queue.single()
        assertEquals(true, track.pinned)
        assertEquals(false, track.expandParts)
    }
}
