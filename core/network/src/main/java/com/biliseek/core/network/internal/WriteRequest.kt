package com.biliseek.core.network.internal

import com.biliseek.core.network.RefreshOutcome
import com.biliseek.core.network.WriteError
import com.biliseek.core.network.WriteException
import com.biliseek.core.network.dto.BiliResponse
import java.io.IOException

/**
 * 写操作统一编排：注入 csrf(=bili_jct) 到 body、就绪风控凭据、统一错误分流、-111 / -101 续期后重试一次。
 *
 * **协作者全部以函数入参注入**（session/awaitReady/refresh/invalidate/currentJct），使本函数无 Android
 * 依赖、可在纯 JVM 单测里离线覆盖工单 §5.1 的每条判据（无需 Context / MockWebServer）。
 * [com.biliseek.core.network.BiliRepository.writeRequest] 是它绑定 authGate/sessionStore/cookieRefresher 的薄封装。
 *
 * 与读路径 wbiRequest 对称，两点不同：
 *  1. csrf 在此**唯一**注入——调用点无法遗漏，结构性杜绝 -111。
 *  2. 成功不要求 data 非空——写接口成功常返回空 data。
 */
internal suspend fun <T> runWriteRequest(
    payload: Map<String, String>,
    idempotentCodes: Set<Int>,
    referer: String?,
    loggedIn: Boolean,
    initialJct: String,
    awaitReady: suspend () -> Unit,
    refresh: suspend () -> RefreshOutcome,
    invalidate: suspend () -> Unit,
    /** passport 确认登录态已失效（[RefreshOutcome.Expired]）时自动登出。 */
    logOut: suspend () -> Unit,
    currentJct: () -> String,
    /** -111 调试日志的注入点，默认空实现保持本函数无 Android 依赖、可在纯 JVM 单测里离线跑。 */
    log: (String) -> Unit = {},
    block: suspend (fields: Map<String, String>, referer: String?) -> BiliResponse<T>,
): T? {
    if (!loggedIn || initialJct.isBlank()) {
        throw WriteException(WriteError.NeedLogin, "写操作需要登录")
    }
    // 保证 buvid/ticket/WBI 就绪，降低 -352（写操作同样吃风控三件套）
    try {
        awaitReady()
    } catch (e: Exception) {
        throw WriteException(WriteError.Network(e.message.orEmpty()), e.message ?: "风控凭据不可用")
    }

    suspend fun fire(csrf: String) = try {
        block(payload + ("csrf" to csrf), referer).also { resp ->
            // 调试期：仅在 -111 时打印原始响应，定位"双码"是哪个端点、csrf 是否进了 body
            if (resp.code == -111) {
                log(
                    "code=-111  payloadKeys=${payload.keys} csrfInPayload=${payload.containsKey("csrf")} " +
                        "msg='${resp.message}' referer=$referer",
                )
            }
        }
    } catch (e: IOException) {
        throw WriteException(WriteError.Network(e.message.orEmpty()), e.message ?: "网络不可达")
    }

    var response = fire(initialJct)

    // -111（jct 轮换）/ -101（登录态过期）：续期一次，续上了再打一次——两者共用这一次，绝不循环
    val first = resolveWriteOutcome(response.code, idempotentCodes)
    if (first is WriteOutcome.CsrfInvalid || first is WriteOutcome.NeedLogin) {
        when (resolveRefreshFollowUp(refresh())) {
            RefreshFollowUp.RETRY_ONCE -> response = fire(currentJct())
            RefreshFollowUp.LOG_OUT -> {
                logOut()
                throw WriteException(WriteError.NeedLogin, "登录已失效，请重新登录")
            }
            // 刷新撞了风控：按 -352 处理，writeRequest 据此记入全局冷却
            RefreshFollowUp.COOL_DOWN ->
                throw WriteException(WriteError.Blocked, "触发风控(-352)，已进入退避")
            RefreshFollowUp.GIVE_UP -> Unit
        }
    }

    return when (val outcome = resolveWriteOutcome(response.code, idempotentCodes)) {
        WriteOutcome.Success, WriteOutcome.AlreadyDone -> response.data
        WriteOutcome.CsrfInvalid ->
            throw WriteException(WriteError.CsrfInvalid, "csrf 校验失败(-111)，请重新登录")
        WriteOutcome.NeedLogin ->
            throw WriteException(WriteError.NeedLogin, "未登录(-101)")
        WriteOutcome.Blocked ->
            throw WriteException(WriteError.Blocked, "触发风控(-352)，已进入退避")
        WriteOutcome.ReseedCredentials -> {
            invalidate()
            throw WriteException(WriteError.CredentialsInvalid, "请求被拦截(-412)，已重置设备凭据")
        }
        is WriteOutcome.Failed ->
            throw WriteException(
                WriteError.Unknown(outcome.code, response.message),
                "操作失败：code=${outcome.code} ${response.message}",
            )
    }
}
