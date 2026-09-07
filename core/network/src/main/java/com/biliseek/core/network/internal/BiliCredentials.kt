package com.biliseek.core.network.internal

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 风控三件套。
 *
 * 这三样缺任何一样都会导致业务接口失败：
 * - `buvid3/4` —— 设备指纹，缺失 -412
 * - `bili_ticket` —— 近年新增的风控 cookie，缺失显著提高触发验证概率
 * - WBI 密钥 —— 签名基础，缺失 -412
 */
data class BiliCredentials(
    val buvid3: String,
    val buvid4: String,
    val ticket: String,
    val ticketExpiresAt: Long,
    val wbiImgKey: String,
    val wbiSubKey: String,
    val wbiFetchedAt: Long,
    /** buvid 是否已通过 ExClimbWuzhi 激活。未激活的 buvid 搜索只会拿到 v_voucher。 */
    val activated: Boolean = false,
    /**
     * B2：buvid 种植时刻的 unix 秒（= `b_nut` cookie）。默认空以兼容旧档（不进 [isValid]，
     * 免得升级用户被强制重新种植）；空则 CookieJar 不注入。
     */
    val bNut: String = "",
) {
    fun isValid(now: Long = System.currentTimeMillis()): Boolean {
        if (buvid3.isBlank() || wbiImgKey.isBlank() || wbiSubKey.isBlank()) return false
        // 未激活的 buvid 搜索不可用，视为无效以触发重新种植
        if (!activated) return false
        // ticket 留 6 小时余量提前续；WBI 密钥每日轮换，按自然日缓存
        if (ticket.isNotBlank() && now > ticketExpiresAt - TICKET_RENEW_MARGIN_MS) return false
        if (now - wbiFetchedAt > WBI_MAX_AGE_MS) return false
        return true
    }

    companion object {
        val EMPTY = BiliCredentials("", "", "", 0, "", "", 0, false, "")

        private const val TICKET_RENEW_MARGIN_MS = 6 * 60 * 60 * 1000L
        private const val WBI_MAX_AGE_MS = 12 * 60 * 60 * 1000L
    }
}

internal fun hmacSha256Hex(key: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
    return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
}
