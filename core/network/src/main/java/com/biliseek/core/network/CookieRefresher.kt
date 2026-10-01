package com.biliseek.core.network

import android.util.Log
import com.biliseek.core.network.internal.RefreshDecision
import com.biliseek.core.network.internal.RefreshMemo
import com.biliseek.core.network.internal.correspondPath
import com.biliseek.core.network.internal.extractRefreshCsrf
import com.biliseek.core.network.internal.resolvePassportException
import com.biliseek.core.network.internal.resolvePassportFailure
import com.biliseek.core.network.internal.resolvePassportHttpFailure
import com.biliseek.core.network.internal.resolveRefreshDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * passport 自己也回 -101（`cookie/info` 或 `cookie/refresh`）：这份登录态在服务端已经失效，
     * 续期无从谈起，只能重新扫码。调用方据此自动登出。
     */
    data object Expired : RefreshOutcome

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

    /** 单飞：启动检查、-111、-101 三个入口共用，同一时刻只有一次刷新在跑。 */
    private val mutex = Mutex()

    /** 上一次真去问 passport 的结果，只在 [mutex] 内读写。见 [resolveRefreshDecision]。 */
    private var memo: RefreshMemo? = null

    /** 启动检查：登录态是否需要续期，需要就续。 */
    suspend fun refreshIfNeeded(): RefreshOutcome = refreshGuarded(rejected = null)

    /**
     * 请求被 -101 / -111 拒掉后调用。[rejected] 是那次请求所用的登录态：等锁期间别的请求
     * 已经续上的话直接返回 [RefreshOutcome.Refreshed]（调用方重打），不再问 passport。
     */
    internal suspend fun refreshAfterRejection(rejected: BiliSession): RefreshOutcome =
        refreshGuarded(rejected)

    private suspend fun refreshGuarded(rejected: BiliSession?): RefreshOutcome = mutex.withLock {
        val session = sessionStore.current
        val now = System.currentTimeMillis()
        when (val decision = resolveRefreshDecision(rejected?.sessData, session.sessData, memo, now)) {
            is RefreshDecision.Reuse -> decision.outcome
            RefreshDecision.Ask -> {
                // 异常也折成结果：记进记忆，下一个 -101 才不会再去打 passport
                val outcome = try {
                    refresh(session)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    resolvePassportException(e)
                }
                memo = RefreshMemo(session.sessData, outcome, now)
                outcome
            }
        }
    }

    private suspend fun refresh(session: BiliSession): RefreshOutcome {
        val info = try {
            api.cookieInfo(csrf = session.biliJct)
        } catch (e: IOException) {
            return RefreshOutcome.Failed(e.message ?: "cookie/info 网络失败")
        }
        if (info.code != 0) {
            val outcome = resolvePassportFailure("cookie/info", info.code, info.message)
            if (outcome == RefreshOutcome.Blocked) Log.w(TAG, "cookie/info 触发风控，放弃刷新并保持现有登录态")
            return outcome
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
                return resolvePassportHttpFailure("correspond", response.code())
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
        if (!refreshed.isSuccessful) return resolvePassportHttpFailure("cookie/refresh", refreshed.code())
        val body = refreshed.body() ?: return RefreshOutcome.Failed("cookie/refresh 无响应体")
        if (body.code != 0) return resolvePassportFailure("cookie/refresh", body.code, body.message)

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
         * **来源与验证（2026-08-28）**：B 站 Web 前端 wasm
         * `https://s1.hdslb.com/bfs/static/jinkela/long/wasm/wasm_rsa_encrypt_bg.wasm`
         * 中提取（算法逆向见 bilibili-API-collect §cookie_refresh，原始出处已下架）。
         * 验证方式：解出的模数（去掉前导 0 后）逐字节存在于**当日抓取的线上 wasm** 中，
         * 且与逆向报告的 JWK `n` 一致——不是「大概是这个」的值。
         *
         * 端到端的「一次真实刷新」仍需真人扫码登录后验证（验不了的部分在验收记录里
         * 如实标注为未验证）。填错不会报错——OAEP 照样加密成功，只是服务端解不开，
         * 表现为刷新永远静默失败。本密钥有字节级出处证据，风险已降到最低。
         */
        val CORRESPOND_PUBLIC_KEY = """
            -----BEGIN PUBLIC KEY-----
            MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg
            Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71
            nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40
            JNrRuoEUXpabUzGB8QIDAQAB
            -----END PUBLIC KEY-----
            """.trimIndent()
    }
}
