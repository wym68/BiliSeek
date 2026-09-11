package com.biliseek.core.network

import com.biliseek.core.network.dto.DynamicItem
import com.biliseek.core.network.internal.resolveDynamicCommentTarget
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Ch4 §4.1.4：评论 oid/type 分流。
 *
 * 规则（参照 BiliPai `DynamicInteractionPolicy.resolveDynamicCommentTarget`）：
 * - DRAW：oid = major.draw.id，type = 11
 * - 其他（OPUS / 转发 / 普通）：oid = id_str，type = 17
 *
 * 这是纯函数，无副作用，可单测。
 */
class DynamicCommentTargetTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun drawItem(idStr: String, drawId: Long): DynamicItem = json.decodeFromString(
        """
        {
          "id_str": "$idStr",
          "type": "DYNAMIC_TYPE_DRAW",
          "modules": {
            "module_author": { "mid": 1, "name": "UP" },
            "module_dynamic": {
              "major": { "draw": { "id": $drawId, "items": [{"src": "//i0/a.jpg"}] } }
            }
          }
        }
        """.trimIndent(),
    )

    private fun opusItem(idStr: String): DynamicItem = json.decodeFromString(
        """
        {
          "id_str": "$idStr",
          "type": "DYNAMIC_TYPE_OPUS",
          "modules": {
            "module_author": { "mid": 1, "name": "UP" },
            "module_dynamic": { "major": { "opus": { "summary": "x", "pics": [] } } }
          }
        }
        """.trimIndent(),
    )

    private fun forwardItem(idStr: String): DynamicItem = json.decodeFromString(
        """
        {
          "id_str": "$idStr",
          "type": "DYNAMIC_TYPE_FORWARD",
          "modules": {
            "module_author": { "mid": 1, "name": "UP" },
            "module_dynamic": { "desc": { "text": "转" } }
          },
          "orig": {
            "id_str": "999", "type": "DYNAMIC_TYPE_DRAW",
            "modules": {
              "module_author": { "mid": 2, "name": "作者" },
              "module_dynamic": { "major": { "draw": { "id": 999, "items": [{"src": "//o.jpg"}] } } }
            }
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `DRAW 动态评论走 type11 oid 用 draw id`() {
        val item = drawItem("dyn1", drawId = 100500)
        val target = resolveDynamicCommentTarget(item)
        assertEquals(100500L, target.first)
        assertEquals(11, target.second)
    }

    @Test
    fun `OPUS 动态评论走 type17 oid 用 id_str`() {
        // id_str 在 B 站是纯数字字符串，oid 解析为 Long
        val item = opusItem("1700000000001")
        val target = resolveDynamicCommentTarget(item)
        assertEquals(1700000000001L, target.first)
        assertEquals(17, target.second)
    }

    @Test
    fun `转发动态评论走 type17 oid 用自己 id_str`() {
        // 转发评论走自己的 id_str/type=17；不被 orig 的 DRAW type=11 误导。
        val item = forwardItem("1700000000003")
        val target = resolveDynamicCommentTarget(item)
        assertEquals(1700000000003L, target.first)
        assertEquals(17, target.second)
        assertNotEquals(999L, target.first)
    }

    @Test
    fun `DRAW draw id 缺失时降级为 id_str 与 type17`() {
        val item = json.decodeFromString<DynamicItem>(
            """
            {
              "id_str": "1700000000004", "type": "DYNAMIC_TYPE_DRAW",
              "modules": {
                "module_author": { "mid": 1, "name": "UP" },
                "module_dynamic": { "major": { "draw": { "items": [{"src": "//i0/a.jpg"}] } } }
              }
            }
            """.trimIndent(),
        )
        val target = resolveDynamicCommentTarget(item)
        assertEquals(1700000000004L, target.first)
        assertEquals(17, target.second)
    }
}