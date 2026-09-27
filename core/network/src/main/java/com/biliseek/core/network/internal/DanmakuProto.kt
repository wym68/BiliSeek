package com.biliseek.core.network.internal

import com.biliseek.core.network.dto.DanmakuItem

/**
 * 弹幕分段（`DmSegMobileReply`）的 protobuf 解码。
 *
 * **手写 wire format 解码，不引 protobuf 依赖**：只需要读一个消息、十来个字段，
 * 引 protobuf-lite + 代码生成插件的代价（构建配置、R8 keep 规则、方法数）远大于
 * 这百来行；手写的解码器也能直接喂字节做离线单测。
 *
 * ```
 * message DmSegMobileReply { repeated DanmakuElem elems = 1; ... }
 * message DanmakuElem {
 *   int64 id = 1; int32 progress = 2; int32 mode = 3; int32 fontsize = 4;
 *   uint32 color = 5; string midHash = 6; string content = 7; int64 ctime = 8;
 *   int32 weight = 9; string action = 10; int32 pool = 11; string idStr = 12; ...
 * }
 * ```
 * 未知字段按 wire type 跳过，接口加字段不会让解析失败。
 */
internal object DanmakuProto {

    /** 解码一段弹幕。坏数据（截断、wire type 不认识）抛 [IllegalArgumentException]。 */
    fun parseSegment(bytes: ByteArray): List<DanmakuItem> {
        val reader = ProtoReader(bytes, 0, bytes.size)
        val items = mutableListOf<DanmakuItem>()
        while (reader.hasMore()) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            if (field == 1 && wireType == WIRE_LENGTH_DELIMITED) {
                val length = reader.readVarint().toInt()
                items += parseElem(reader.sub(length))
            } else {
                reader.skip(wireType)
            }
        }
        return items
    }

    private fun parseElem(reader: ProtoReader): DanmakuItem {
        var id = 0L
        var progress = 0L
        var mode = 1
        var fontSize = 25
        var color = DanmakuItem.DEFAULT_COLOR
        var content = ""
        var weight = 0
        var pool = 0
        while (reader.hasMore()) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            when {
                wireType == WIRE_VARINT && field == 1 -> id = reader.readVarint()
                wireType == WIRE_VARINT && field == 2 -> progress = reader.readVarint()
                wireType == WIRE_VARINT && field == 3 -> mode = reader.readVarint().toInt()
                wireType == WIRE_VARINT && field == 4 -> fontSize = reader.readVarint().toInt()
                wireType == WIRE_VARINT && field == 5 -> color = (reader.readVarint() and 0xFFFFFF).toInt()
                wireType == WIRE_LENGTH_DELIMITED && field == 7 -> content = reader.readString()
                wireType == WIRE_VARINT && field == 9 -> weight = reader.readVarint().toInt()
                wireType == WIRE_VARINT && field == 11 -> pool = reader.readVarint().toInt()
                else -> reader.skip(wireType)
            }
        }
        return DanmakuItem(id, progress, mode, fontSize, color, content, weight, pool)
    }

    /**
     * 出错时接口回的是 JSON（如 `{"code":-352,...}`）而不是 protobuf。
     * 是 JSON 就取出 code，不是返回 null。
     *
     * 靠首字节区分：本消息合法的首字节是字段 1 的 tag（0x0A），
     * 而 `{`（0x7B）解作 tag 是字段 15、wire type 3（已废弃的 group），不可能出现。
     */
    fun jsonErrorCode(bytes: ByteArray): Int? {
        val first = bytes.firstOrNull { it.toInt().toChar() !in " \t\r\n" } ?: return null
        if (first.toInt().toChar() != '{') return null
        val text = String(bytes, Charsets.UTF_8)
        return CODE_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: UNKNOWN_JSON_CODE
    }

    /** JSON 里找不到 code 字段时给的码：当作未知错误。 */
    const val UNKNOWN_JSON_CODE = Int.MIN_VALUE

    private val CODE_REGEX = Regex(""""code"\s*:\s*(-?\d+)""")

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    private class ProtoReader(private val bytes: ByteArray, private var pos: Int, private val end: Int) {

        fun hasMore(): Boolean = pos < end

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                require(pos < end) { "varint 截断" }
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("varint 过长")
        }

        fun sub(length: Int): ProtoReader {
            require(length >= 0 && pos + length <= end) { "长度越界" }
            val reader = ProtoReader(bytes, pos, pos + length)
            pos += length
            return reader
        }

        fun readString(): String {
            val length = readVarint().toInt()
            require(length >= 0 && pos + length <= end) { "字符串越界" }
            val s = String(bytes, pos, length, Charsets.UTF_8)
            pos += length
            return s
        }

        fun skip(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> advance(8)
                WIRE_LENGTH_DELIMITED -> advance(readVarint().toInt())
                WIRE_FIXED32 -> advance(4)
                else -> throw IllegalArgumentException("不支持的 wire type $wireType")
            }
        }

        private fun advance(n: Int) {
            require(n >= 0 && pos + n <= end) { "跳过越界" }
            pos += n
        }
    }
}
