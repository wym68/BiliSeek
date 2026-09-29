package com.biliseek.core.ai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** transcript.json 的读写（重新生成时复用原文的前提）。 */
class TranscriptSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `原文往返序列化不丢字段`() {
        val transcript = Transcript(
            segments = listOf(
                TranscriptSegment(0, 2_500, "大家好"),
                TranscriptSegment(2_500, 7_000, "今天讲一下离线识别"),
            ),
            chapters = listOf(Chapter(0, 60_000, "开场")),
            language = "zh",
            timing = Transcript.Timing.SEGMENT,
        )
        val text = json.encodeToString(Transcript.serializer(), transcript)
        assertEquals(transcript, json.decodeFromString(Transcript.serializer(), text))
    }

    @Test
    fun `旧文件缺省字段按默认值读入 多余字段忽略`() {
        val text = """{"segments":[{"startMs":0,"endMs":1000,"text":"嗯"}],"future":1}"""
        val transcript = json.decodeFromString(Transcript.serializer(), text)
        assertEquals(emptyList<Chapter>(), transcript.chapters)
        assertEquals(null, transcript.language)
        assertEquals(Transcript.Timing.SEGMENT, transcript.timing)
    }
}
