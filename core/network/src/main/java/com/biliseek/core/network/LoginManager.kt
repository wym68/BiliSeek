package com.biliseek.core.network

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 扫码登录状态机。 */
sealed interface LoginState {
    data object Idle : LoginState

    /** 二维码已生成，[url] 需渲染成二维码图像 */
    data class QrReady(val url: String, val key: String) : LoginState

    /** 已扫描，等待手机端确认 */
    data class Scanned(val url: String) : LoginState

    data object Success : LoginState

    /** 二维码失效，需重新生成 */
    data object Expired : LoginState

    data class Failed(val reason: String) : LoginState
}

/**
 * Web 扫码登录。
 *
 * 选型篇已定：**只做 Web 扫码**，不做 TV 扫码。
 * TV 端的高画质权益对「默认只听」的 BiliSeek 无价值，却要冒用公开的官方 appkey、
 * 承担被单点限流的风险，并制造矛盾的设备画像。
 */
class LoginManager(
    private val api: BiliApi,
    private val sessionStore: SessionStore,
) {
    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    /** 生成二维码。返回后需调用 [poll] 开始轮询。 */
    suspend fun requestQrCode(): Result<LoginState.QrReady> = runCatching {
        val response = api.generateQrCode()
        require(response.code == 0 && response.data != null) {
            "二维码生成失败：code=${response.code} ${response.message}"
        }
        val ready = LoginState.QrReady(response.data.url, response.data.qrcodeKey)
        _state.value = ready
        ready
    }.onFailure { _state.value = LoginState.Failed(it.message ?: "未知错误") }

    /**
     * 轮询扫码结果，直到成功/失效/取消。
     *
     * 状态码：`86101` 未扫码 / `86090` 已扫未确认 / `86038` 已失效 / `0` 成功。
     */
    suspend fun poll(key: String) {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val raw = runCatching { api.pollQrCode(key = key) }.getOrNull()
            val body = raw?.body()
            val data = body?.data

            when (data?.code) {
                0 -> {
                    // Cookie 在响应头里，不在 body
                    val cookies = raw.headers().values("Set-Cookie")
                    val session = parseSessionFromCookies(cookies, data.refreshToken)
                    if (session.isLoggedIn) {
                        sessionStore.save(session)
                        _state.value = LoginState.Success
                        Log.d(TAG, "登录成功 uid=${session.dedeUserId}")
                    } else {
                        _state.value = LoginState.Failed("登录响应缺少 SESSDATA")
                    }
                    return
                }
                SCANNED_NOT_CONFIRMED -> _state.value = LoginState.Scanned(data.url)
                EXPIRED -> {
                    _state.value = LoginState.Expired
                    return
                }
                NOT_SCANNED -> Unit // 保持当前状态
                else -> Unit
            }
            delay(POLL_INTERVAL_MS)
        }
        _state.value = LoginState.Expired
    }

    suspend fun logout() {
        // 只清账号，保留 buvid / ticket —— 那是设备身份，重种反而更像异常
        sessionStore.clear()
        _state.value = LoginState.Idle
    }

    fun reset() {
        _state.value = LoginState.Idle
    }

    internal companion object {
        const val TAG = "BiliSeekLogin"
        const val NOT_SCANNED = 86101
        const val SCANNED_NOT_CONFIRMED = 86090
        const val EXPIRED = 86038
        const val POLL_INTERVAL_MS = 2_000L
        const val POLL_TIMEOUT_MS = 180_000L
    }
}

/** 从 `Set-Cookie` 头里抽取账号凭据。 */
internal fun parseSessionFromCookies(cookies: List<String>, refreshToken: String): BiliSession {
    fun pick(name: String): String = cookies
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter("$name=")
        ?.substringBefore(';')
        .orEmpty()

    return BiliSession(
        sessData = pick("SESSDATA"),
        biliJct = pick("bili_jct"),
        dedeUserId = pick("DedeUserID"),
        refreshToken = refreshToken,
    )
}
