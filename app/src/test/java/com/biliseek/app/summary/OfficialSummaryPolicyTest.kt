package com.biliseek.app.summary

import com.biliseek.core.network.ConclusionResult
import com.biliseek.core.network.dto.ConclusionData
import com.biliseek.core.network.dto.ConclusionModelResult
import com.biliseek.core.network.dto.ConclusionOutline
import com.biliseek.core.network.dto.ConclusionPartOutline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工单 AI-4b 状态表逐行对照。 */
class OfficialSummaryPolicyTest {

    private val model = ConclusionModelResult(
        resultType = 2,
        summary = "一句话",
        outline = listOf(ConclusionOutline("第一节", 12, listOf(ConclusionPartOutline(15, "要点")))),
    )

    private fun ok(data: ConclusionData?) = Result.success(ConclusionResult(0, data))
    private fun reason(r: OfficialSummary) = (r as OfficialSummary.Unavailable).reason

    @Test
    fun `未登录不查询`() {
        assertFalse(OfficialSummaryPolicy.shouldQuery(isLoggedIn = false))
        assertTrue(OfficialSummaryPolicy.shouldQuery(isLoggedIn = true))
    }

    @Test
    fun `有摘要或大纲即可用`() {
        assertEquals(OfficialSummary.Available(model), OfficialSummaryPolicy.diagnose(ok(ConclusionData(0, model, "123"))))
        val onlyOutline = model.copy(summary = "")
        assertTrue(OfficialSummaryPolicy.diagnose(ok(ConclusionData(0, onlyOutline))) is OfficialSummary.Available)
    }

    @Test
    fun `各种不可用状态`() {
        assertEquals(OfficialSummary.Reason.NO_SUMMARY, reason(OfficialSummaryPolicy.diagnose(ok(ConclusionData(0, ConclusionModelResult())))))
        assertEquals(OfficialSummary.Reason.QUEUED, reason(OfficialSummaryPolicy.diagnose(ok(ConclusionData(1, null, "0")))))
        assertEquals(OfficialSummary.Reason.NO_SPEECH, reason(OfficialSummaryPolicy.diagnose(ok(ConclusionData(1, null, "")))))
        assertEquals(OfficialSummary.Reason.NO_SUMMARY, reason(OfficialSummaryPolicy.diagnose(ok(ConclusionData(1, null, "999")))))
        assertEquals(OfficialSummary.Reason.UNSUPPORTED, reason(OfficialSummaryPolicy.diagnose(ok(ConclusionData(-1)))))
        assertEquals(OfficialSummary.Reason.NO_SUMMARY, reason(OfficialSummaryPolicy.diagnose(ok(null))))
    }

    @Test
    fun `外层错误码与请求失败`() {
        assertEquals(
            OfficialSummary.Reason.NOT_LOGGED_IN,
            reason(OfficialSummaryPolicy.diagnose(Result.success(ConclusionResult(-101, null)))),
        )
        assertEquals(OfficialSummary.Reason.FAILED, reason(OfficialSummaryPolicy.diagnose(Result.success(ConclusionResult(-400, null)))))
        assertEquals(OfficialSummary.Reason.FAILED, reason(OfficialSummaryPolicy.diagnose(Result.failure(RuntimeException("x")))))
    }

    @Test
    fun `转成本项目格式的 md`() {
        val md = OfficialSummaryMarkdownPolicy.markdown("视频", linkedMapOf("UP" to "某人"), model, durationMs = 600_000)
        assertTrue(md, md.startsWith("# 视频\n\n> UP：某人 · 来源：B 站官方 AI 总结\n\n## 一句话总结"))
        assertTrue(md, md.contains("### [00:12] 第一节"))
        assertTrue(md, md.contains("- [00:15] 要点"))
    }

    @Test
    fun `超过一小时的视频时间戳带小时`() {
        val body = OfficialSummaryMarkdownPolicy.body(model, durationMs = 4_000_000)
        assertTrue(body, body.contains("[0:00:12]"))
    }
}
