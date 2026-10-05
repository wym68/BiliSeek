package com.biliseek.core.network

import com.biliseek.core.network.internal.chargeDebugLines
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 调试日志：从接口返回里挑出每个视频和充电有关的字段，一个视频一行（只在 debug 包打）。 */
class ChargeDebugLinesTest {

    private fun lines(raw: String) = chargeDebugLines(Json.parseToJsonElement(raw))

    @Test
    fun `带 bvid 的对象各打一行 只列和充电有关的字段`() {
        val out = lines(
            """{"code":0,"data":{"list":{"vlist":[
              {"bvid":"BV1a","title":"普通视频","play":1,"length":"3:00","is_charging_arc":false,"elec_arc_type":0,"elec_arc_badge":""},
              {"bvid":"BV1b","title":"充电视频","is_charging_arc":true,"elec_arc_type":1,"elec_arc_badge":"充电专属"}
            ]}}}""",
        )
        assertEquals(
            listOf(
                "BV1a 「普通视频」 is_charging_arc=false elec_arc_type=0 elec_arc_badge=\"\"",
                "BV1b 「充电视频」 is_charging_arc=true elec_arc_type=1 elec_arc_badge=\"充电专属\"",
            ),
            out,
        )
    }

    @Test
    fun `没有 bvid 或没有充电字段的对象不打`() {
        assertTrue(lines("""{"data":{"is_charging_arc":true,"items":[{"bvid":"BV1c","title":"x","play":3}]}}""").isEmpty())
    }

    @Test
    fun `嵌套对象压成一行 标题截断 rights 逐个展开不截断`() {
        val out = lines(
            """{"data":{"items":[{"modules":{"module_dynamic":{"major":{"archive":{
              "bvid":"BV1d","title":"一个标题非常非常非常非常非常长的视频",
              "badge":{"text":"投稿视频","color":"#FFFFFF"},
              "rights":{"ugc_pay":0,"arc_pay":0,"pay":0,"free_watch":0,"ugc_pay_preview":0,"no_reprint":1,"autoplay":1,"download":1,"movie":0,"hd5":0,"elec":0,"bp":0,"clean_mode":0,"is_360":0,"no_share":0,"is_cooperation":0,"is_stein_gate":0,"no_background":0}
            }}}}}]}}""",
        )
        val line = out.single()
        assertTrue(line, line.startsWith("BV1d 「一个标题非常非常非常非常非常长的…」 badge={\"text\":\"投稿视频\",\"color\":\"#FFFFFF\"} rights.ugc_pay=0 rights.arc_pay=0"))
        // rights 要逐个看全（限免和已充电的区别可能就在里面），不截断
        assertTrue(line, "rights.free_watch=0" in line)
        assertTrue(line, line.endsWith("rights.no_background=0"))
    }

    @Test
    fun `视频详情顶层的 bvid 也算 子对象里的视频另起一行`() {
        val out = lines(
            """{"data":{"bvid":"BV1e","title":"详情","is_upower_exclusive":true,"is_upower_play":false,"is_upower_preview":true,
              "ugc_season":{"sections":[{"episodes":[{"bvid":"BV1f","title":"合集里的一集","attribute":0,"badge":""}]}]}}}""",
        )
        assertEquals(
            listOf(
                "BV1e 「详情」 is_upower_exclusive=true is_upower_play=false is_upower_preview=true",
                "BV1f 「合集里的一集」 badge=\"\"",
            ),
            out,
        )
    }
}
