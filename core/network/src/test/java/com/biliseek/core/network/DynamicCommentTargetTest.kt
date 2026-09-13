package com.biliseek.core.network

import com.biliseek.core.network.dto.DynamicItem
import com.biliseek.core.network.internal.resolveDynamicCommentTarget
import com.biliseek.core.network.internal.resolveDynamicCommentTargetFromEntry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Ch4 §4.1.4：评论 oid/type 分流。
 *
 * 规则（参照 BiliPai `DynamicInteractionPolicy.resolveDynamicCommentTarget`）：
 * - 服务端权威字段 `basic.comment_id_str`/`comment_type`（`comment_type>0` 时）：直接采用。
 * - 缺失时按类型猜：DRAW：oid = major.draw.id，type = 11；其他（OPUS / 转发 / 普通）：
 *   oid = id_str，type = 17。
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

    /** 真机抓包证实：视频动态的 basic.comment_type=1，oid 是视频 avid，与 id_str（动态id）不同。 */
    private fun avItemWithBasic(idStr: String, commentIdStr: String, commentType: Int): DynamicItem =
        json.decodeFromString(
            """
            {
              "id_str": "$idStr",
              "type": "DYNAMIC_TYPE_AV",
              "modules": {
                "module_author": { "mid": 1, "name": "UP" },
                "module_dynamic": { "major": { "archive": { "bvid": "BV1", "title": "t" } } }
              },
              "basic": { "comment_id_str": "$commentIdStr", "comment_type": $commentType }
            }
            """.trimIndent(),
        )

    /**
     * 真机抓包证实：转发视频动态顶层 basic 是权威的（comment_type=17），
     * orig.basic 常为空（comment_type=0）——不能被 orig 的 basic 或 draw id 误导。
     */
    private fun forwardVideoItemWithBasic(idStr: String, commentIdStr: String, commentType: Int): DynamicItem =
        json.decodeFromString(
            """
            {
              "id_str": "$idStr",
              "type": "DYNAMIC_TYPE_FORWARD",
              "modules": {
                "module_author": { "mid": 1, "name": "UP" },
                "module_dynamic": { "desc": { "text": "转" } }
              },
              "basic": { "comment_id_str": "$commentIdStr", "comment_type": $commentType },
              "orig": {
                "id_str": "999", "type": "DYNAMIC_TYPE_AV",
                "modules": {
                  "module_author": { "mid": 2, "name": "作者" },
                  "module_dynamic": { "major": { "archive": { "bvid": "BV2", "title": "o" } } }
                },
                "basic": { "comment_id_str": "", "comment_type": 0 }
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
    fun `视频动态有 basic 时走服务端下发的 avid 与 type1，不猜 id_str 与 type17`() {
        val item = avItemWithBasic("1700000000005", commentIdStr = "112981396619958", commentType = 1)
        val target = resolveDynamicCommentTarget(item)
        assertEquals(112981396619958L, target.first)
        assertEquals(1, target.second)
        assertNotEquals(1700000000005L, target.first)
    }

    @Test
    fun `转发视频动态优先用顶层 basic，不被 orig 的 basic 或 draw id 误导`() {
        val item = forwardVideoItemWithBasic("1700000000006", commentIdStr = "966692349750018048", commentType = 17)
        val target = resolveDynamicCommentTarget(item)
        assertEquals(966692349750018048L, target.first)
        assertEquals(17, target.second)
    }

    @Test
    fun `basic 的 comment_type 为 0 时忽略 basic，按原规则降级`() {
        // OPUS 动态没有 basic 字段（json 默认 null），行为应与旧版一致
        val item = opusItem("1700000000007")
        val target = resolveDynamicCommentTarget(item)
        assertEquals(1700000000007L, target.first)
        assertEquals(17, target.second)
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

    @Test
    fun `FromEntry 有 commentOid 与 commentType 时优先采用，不看 rawType 或 drawId`() {
        val target = resolveDynamicCommentTargetFromEntry(
            idStr = "1700000000008",
            rawType = DynamicItemType.AV,
            drawId = 0,
            commentOid = 112981396619958L,
            commentType = 1,
        )
        assertEquals(112981396619958L, target.first)
        assertEquals(1, target.second)
    }

    @Test
    fun `FromEntry 无 commentOid 与 commentType 时行为与旧版一致`() {
        val draw = resolveDynamicCommentTargetFromEntry(idStr = "dyn1", rawType = DynamicItemType.DRAW, drawId = 100500)
        assertEquals(100500L, draw.first)
        assertEquals(11, draw.second)

        val other = resolveDynamicCommentTargetFromEntry(idStr = "1700000000009", rawType = DynamicItemType.OPUS)
        assertEquals(1700000000009L, other.first)
        assertEquals(17, other.second)
    }
}