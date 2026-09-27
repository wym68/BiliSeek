package com.biliseek.core.network

import com.biliseek.core.network.dto.DanmakuItem
import com.biliseek.core.network.internal.DanmakuProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 弹幕分段 protobuf 解码。样本在测试里按 wire format 手工编码，不碰网络。
 */
class DanmakuProtoTest {

    // ── 最小 protobuf 编码器（仅测试用） ──

    private class Encoder {
        val out = ByteArrayOutputStream()
        fun varint(value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.write(v.toInt()); return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }
        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun int(field: Int, value: Long) { tag(field, 0); varint(value) }
        fun bytes(field: Int, value: ByteArray) { tag(field, 2); varint(value.size.toLong()); out.write(value) }
        fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
        fun fixed32(field: Int) { tag(field, 5); out.write(ByteArray(4)) }
        fun fixed64(field: Int) { tag(field, 1); out.write(ByteArray(8)) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun elem(
        id: Long = 1,
        progress: Long = 1000,
        mode: Long = 1,
        fontSize: Long = 25,
        color: Long = 0xFFFFFF,
        content: String = "前方高能",
        weight: Long = 5,
        pool: Long = 0,
        extra: Encoder.() -> Unit = {},
    ): ByteArray = Encoder().apply {
        int(1, id)
        int(2, progress)
        int(3, mode)
        int(4, fontSize)
        int(5, color)
        string(6, "abcdef12")       // midHash，不关心
        string(7, content)
        int(8, 1_700_000_000)       // ctime，不关心
        int(9, weight)
        int(11, pool)
        string(12, id.toString())   // idStr，不关心
        extra()
    }.toByteArray()

    private fun segment(vararg elems: ByteArray, extra: Encoder.() -> Unit = {}): ByteArray =
        Encoder().apply {
            elems.forEach { bytes(1, it) }
            extra()
        }.toByteArray()

    @Test
    fun `解出全部字段`() {
        val items = DanmakuProto.parseSegment(
            segment(elem(id = 42, progress = 83_500, mode = 5, fontSize = 36, color = 0xFE0302, content = "好耶", weight = 8, pool = 1)),
        )
        assertEquals(
            listOf(DanmakuItem(42, 83_500, 5, 36, 0xFE0302, "好耶", 8, 1)),
            items,
        )
    }

    @Test
    fun `多条弹幕保持顺序`() {
        val items = DanmakuProto.parseSegment(
            segment(elem(id = 1, content = "a"), elem(id = 2, content = "b"), elem(id = 3, content = "c")),
        )
        assertEquals(listOf("a", "b", "c"), items.map { it.content })
    }

    @Test
    fun `大 id 与多字节 UTF-8 正确`() {
        val items = DanmakuProto.parseSegment(segment(elem(id = 1_234_567_890_123_456L, content = "哈哈哈😂")))
        assertEquals(1_234_567_890_123_456L, items.single().id)
        assertEquals("哈哈哈😂", items.single().content)
    }

    @Test
    fun `未知字段与其他 wire type 被跳过`() {
        val items = DanmakuProto.parseSegment(
            segment(
                elem(content = "保留") { fixed32(20); fixed64(21); string(22, "colorful"); int(23, 7) },
                extra = { int(2, 1); string(3, "segment-level field") },
            ),
        )
        assertEquals("保留", items.single().content)
    }

    @Test
    fun `缺省字段取默认值`() {
        val bare = Encoder().apply { string(7, "只有内容") }.toByteArray()
        val item = DanmakuProto.parseSegment(segment(bare)).single()
        assertEquals(1, item.mode)
        assertEquals(25, item.fontSize)
        assertEquals(DanmakuItem.DEFAULT_COLOR, item.color)
        assertEquals(0L, item.timeMs)
    }

    @Test
    fun `空分段解出空列表`() {
        assertTrue(DanmakuProto.parseSegment(ByteArray(0)).isEmpty())
    }

    @Test
    fun `截断的数据抛异常而不是返回半截`() {
        val full = segment(elem())
        val truncated = full.copyOf(full.size - 3)
        assertThrows(IllegalArgumentException::class.java) { DanmakuProto.parseSegment(truncated) }
    }

    @Test
    fun `风控时返回的 JSON 能取出 code`() {
        assertEquals(-352, DanmakuProto.jsonErrorCode("""{"code":-352,"message":"-352"}""".toByteArray()))
        assertEquals(-412, DanmakuProto.jsonErrorCode(""" { "code" : -412 }""".toByteArray()))
        assertEquals(0, DanmakuProto.jsonErrorCode("""{"code":0}""".toByteArray()))
    }

    @Test
    fun `JSON 里没有 code 时给未知码`() {
        assertEquals(DanmakuProto.UNKNOWN_JSON_CODE, DanmakuProto.jsonErrorCode("""{"msg":"x"}""".toByteArray()))
    }

    @Test
    fun `protobuf 数据不会被当成 JSON`() {
        assertEquals(null, DanmakuProto.jsonErrorCode(segment(elem())))
        assertEquals(null, DanmakuProto.jsonErrorCode(ByteArray(0)))
    }

    @Test
    fun `颜色只取低 24 位`() {
        val item = DanmakuProto.parseSegment(segment(elem(color = 0xFF123456))).single()
        assertEquals(0x123456, item.color)
    }
}
