package com.biliseek.core.network

import android.util.Log
import com.biliseek.core.network.internal.RetryAction
import com.biliseek.core.network.internal.correspondPath
import com.biliseek.core.network.internal.extractRefreshCsrf
import com.biliseek.core.network.internal.resolveRetryAction
import java.io.IOException

/** 一次刷新尝试的结果。全部是终态，调用方不必再判断。 */
sealed interface RefreshOutcome {

    /** 未登录，无事可做。 */
    data object NotLoggedIn : RefreshOutcome

    /** 服务端说不需要刷新。 */
    data object NotNeeded : RefreshOutcome

    /** 刷新成功，新会话已落库。 */
    data object Refreshed : RefreshOutcome

    /**
     * 触发风控。**绝不重试**，保持现有登录态。
     *
     * 这是整条链路上唯一一处「做错会主动伤害用户账号」的地方：
     * 拿着可能已经半失效的凭据反复打刷新接口，是最容易被判定为异常的行为之一。
     */
    data object Blocked : RefreshOutcome

    /** 公钥未配置，功能未启用。见 [CookieRefresher.CORRESPOND_PUBLIC_KEY]。 */
    data object NotConfigured : RefreshOutcome

    data class Failed(val reason: String) : RefreshOutcome
}

/**
 * 登录态续期。
 *
 * 完整链路（缺一步都会静默失效）：
 * ```
 * cookie/info（refresh == true？）
 *   → correspondPath = RSA-OAEP(SHA-256)("refresh_{ts}") 的 hex
 *   → GET /correspond/1/{path} 从 HTML 里抠出 refresh_csrf
 *   → cookie/refresh（带旧 refresh_token）→ 响应头里是新 Cookie
 *   → 落库
 *   → confirm/refresh（带**旧** refresh_token）作废旧凭据
 * ```
 * 最后一步不能省：不确认的话旧 refresh_token 仍然有效，等于多留了一份可用凭据。
 */
class CookieRefresher(
    private val api: BiliApi,
    private val sessionStore: SessionStore,
) {

    suspend fun refreshIfNeeded(): RefreshOutcome {
        val session = sessionStore.current
        if (!session.isLoggedIn) return RefreshOutcome.NotLoggedIn

        val info = try {
            api.cookieInfo(csrf = session.biliJct)
        } catch (e: IOException) {
            return RefreshOutcome.Failed(e.message ?: "cookie/info 网络失败")
        }
        if (resolveRetryAction(info.code) == RetryAction.BACKOFF_NO_RETRY) {
            Log.w(TAG, "cookie/info 触发风控，放弃刷新并保持现有登录态")
            return RefreshOutcome.Blocked
        }
        if (info.code != 0) {
            return RefreshOutcome.Failed("cookie/info code=${info.code} ${info.message}")
        }
        val data = info.data ?: return RefreshOutcome.Failed("cookie/info 无 data")
        if (!data.refresh) return RefreshOutcome.NotNeeded

        if (CORRESPOND_PUBLIC_KEY.isBlank()) {
            Log.w(TAG, "correspondPath 公钥未配置，跳过刷新")
            return RefreshOutcome.NotConfigured
        }

        // 时间戳用服务端给的，避免本地时钟偏移导致 correspondPath 算错
        val timestamp = data.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
        val path = try {
            correspondPath(timestamp, CORRESPOND_PUBLIC_KEY)
        } catch (e: Exception) {
            return RefreshOutcome.Failed("correspondPath 计算失败：${e.message}")
        }

        val html = try {
            val response = api.correspond(CORRESPOND_BASE + path)
            if (!response.isSuccessful) {
                return RefreshOutcome.Failed("correspond HTTP ${response.code()}")
            }
            response.body()?.string().orEmpty()
        } catch (e: IOException) {
            return RefreshOutcome.Failed(e.message ?: "correspond 网络失败")
        }
        val refreshCsrf = extractRefreshCsrf(html)
            ?: return RefreshOutcome.Failed("HTML 中未找到 refresh_csrf")

        val refreshed = try {
            api.cookieRefresh(
                csrf = session.biliJct,
                refreshCsrf = refreshCsrf,
                refreshToken = session.refreshToken,
            )
        } catch (e: IOException) {
            return RefreshOutcome.Failed(e.message ?: "cookie/refresh 网络失败")
        }
        val body = refreshed.body()
        if (body == null || body.code != 0) {
            if (body != null && resolveRetryAction(body.code) == RetryAction.BACKOFF_NO_RETRY) {
                return RefreshOutcome.Blocked
            }
            return RefreshOutcome.Failed("cookie/refresh code=${body?.code}")
        }

        // 新 Cookie 在响应头里，不在 body——与扫码登录同一处坑，复用同一个解析器
        val newSession = parseSessionFromCookies(
            refreshed.headers().values("Set-Cookie"),
            body.data?.refreshToken.orEmpty(),
        )
        if (!newSession.isLoggedIn) {
            return RefreshOutcome.Failed("cookie/refresh 响应缺少 SESSDATA")
        }
        sessionStore.save(newSession)

        // 用**新** csrf 确认，作废**旧** refresh_token
        runCatching {
            api.confirmRefresh(
                csrf = newSession.biliJct,
                oldRefreshToken = session.refreshToken,
            )
        }.onFailure { Log.w(TAG, "confirm/refresh 失败（新凭据已生效）：${it.message}") }

        Log.d(TAG, "登录态已续期")
        return RefreshOutcome.Refreshed
    }

    companion object {
        private const val TAG = "BiliSeekRefresh"

        const val CORRESPOND_BASE = "https://www.bilibili.com/correspond/1/"

        /**
         * `correspondPath` 用的 RSA 公钥（PEM，SPKI）。
         *
         * **当前留空，刷新功能因此处于未启用状态**（[RefreshOutcome.NotConfigured]），
         * 其余链路已完整实现并有单测覆盖。
         *
         * 留空而不是填一个「大概是这个」的值，是因为填错不会报错——
         * OAEP 加密照样成功，只是服务端解不开，表现为刷新永远静默失败、
         * 用户过一阵子莫名其妙掉登录，且极难归因。这类错误比功能缺失更糟。
         *
         * 填入后本类无需改动即可生效。
         */
        const val CORRESPOND_PUBLIC_KEY = ""
    }
}
