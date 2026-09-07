package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.internal.runWriteRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B6 jsonWriteRequest 的核心判据：**csrf 结构性注入 query，且 WBI 签名覆盖 csrf**（工单 §4.1）。
 *
 * jsonWriteRequest 复用已单测的 [runWriteRequest] 做编排；此处验证其组合语义——block 收到的
 * query 含 csrf，签名后仍含 csrf 且带 wts / w_rid（即签名建立在含 csrf 的最终参数集上）。
 * 用真实 [WbiSigner]（已有 WbiSignerTest 佐证签名本身）。
 */
class JsonWriteRequestTest {

    // 长度须为 32/64 的假密钥，喂给 WbiSigner 的混淆表即可（不校验真伪，只验流程）
    private val imgKey = "7cd084941338484aae1ad9425b84077c"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"

    @Test
    fun `csrf 注入 query 后被 WBI 签名覆盖`() = runBlocking {
        var signed: Map<String, String>? = null
        runWriteRequest<JsonElement>(
            payload = mapOf("platform" to "web"),
            idempotentCodes = emptySet(),
            referer = "https://t.bilibili.com/",
            loggedIn = true,
            initialJct = "JCT777",
            awaitReady = {},
            refresh = { RefreshOutcome.NotNeeded },
            invalidate = {},
            currentJct = { "JCT777" },
            block = { fields, _ ->
                // jsonWriteRequest 的 block 正是这样：拿注入 csrf 后的 query 去 WBI 签名
                signed = WbiSigner.sign(fields, imgKey, subKey)
                BiliResponse(code = 0)
            },
        )
        val s = requireNotNull(signed)
        assertEquals("csrf 进入了 query", "JCT777", s["csrf"])
        assertTrue("WBI 已签（含 wts）", s.containsKey("wts"))
        assertTrue("WBI 已签（含 w_rid）", s.containsKey("w_rid"))
        // 原 query 参数保留
        assertEquals("web", s["platform"])
    }
}
