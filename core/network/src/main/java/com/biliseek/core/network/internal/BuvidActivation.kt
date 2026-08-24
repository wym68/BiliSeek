package com.biliseek.core.network.internal

import org.json.JSONObject

/**
 * 构造 `ExClimbWuzhi` 的激活载荷。
 *
 * 这些字段是 B 站前端采集的浏览器指纹，服务端据此把 buvid 标记为「已激活」。
 * 未激活的 buvid 调搜索接口只会拿到 `v_voucher`（风控凭据）而非结果。
 *
 * 取值刻意保持成一台普通 Windows Chrome 桌面机器的样子，与 [BILI_USER_AGENT] 一致。
 */
internal fun buildActivationPayload(buvid3: String): String {
    val inner = JSONObject().apply {
        put("3064", 1)
        put("5062", System.currentTimeMillis().toString())
        put("03bf", "https://www.bilibili.com/")
        put("39c8", "333.1007.fp.risk")
        put("34f1", "")
        put("d402", "")
        put("654a", "")
        put("6e7c", "1177x2094")
        put(
            "3c43",
            JSONObject().apply {
                put("2673", 0)
                put("5766", 24)
                put("6527", 0)
                put("7003", 1)
                put("807e", 1)
                put("b8ce", BILI_USER_AGENT)
                put("641c", 0)
                put("07a4", "zh-CN")
                put("1c57", 8)
                put("0bd0", 8)
                put("748e", listOf(1080, 1920))
                put("d61f", listOf(1032, 1920))
                put("fc9d", -480)
                put("6aa9", "Asia/Shanghai")
                put("75b8", 1)
                put("3b21", 1)
                put("8a1c", 0)
                put("d52f", "not available")
            },
        )
        put("54ef", "{}")
        put("8b94", "")
        put("df35", buvid3)
        put("07a4", "zh-CN")
        put("db46", 0)
    }
    return JSONObject().put("payload", inner.toString()).toString()
}
