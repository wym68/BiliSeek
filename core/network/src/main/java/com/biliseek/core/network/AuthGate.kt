package com.biliseek.core.network

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.biliseek.core.network.internal.BiliCredentials
import com.biliseek.core.network.internal.buildActivationPayload
import com.biliseek.core.network.internal.hmacSha256Hex
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AuthState {
    data object Idle : AuthState
    data object Preparing : AuthState
    data class Ready(val credentials: BiliCredentials) : AuthState
    data class Failed(val reason: String) : AuthState
}

private val Context.authDataStore by preferencesDataStore(name = "biliseek_auth")

/**
 * 风控接入 gate —— 所有业务请求的前置条件。
 *
 * 冷启动流程（《架构设计篇》§3.1）：
 * ```
 * 恢复持久化凭据 → 有效则放行
 *                → 失效/缺失 → spi_v2 取 buvid → GenWebTicket 取 ticket + WBI 密钥 → 持久化 → 放行
 * ```
 *
 * **关键约束：不阻塞 UI。** 搜索框必须立刻可输入；请求排队等 [awaitReady] 放行即可。
 * gate 失败不弹错误，静默指数退避重试；只有用户主动发起的请求最终失败才提示。
 */
class AuthGate(
    private val context: Context,
    private val api: BiliApi,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Idle)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 供 CookieJar 同步读取，避免每次请求都走挂起函数。 */
    @Volatile
    var current: BiliCredentials = BiliCredentials.EMPTY
        private set

    /** 启动时调用。立即返回，准备工作在后台进行。 */
    fun start() {
        scope.launch { ensureReady() }
    }

    /**
     * 业务请求在发出前调用。凭据就绪前挂起，就绪后立即返回。
     *
     * **失败时抛异常而不是继续挂起。** 早期实现用 `state.first { it is Ready }` 等待，
     * 一旦重试预算耗尽、状态停在 [AuthState.Failed]，该条件永远不成立，
     * 调用方会无限挂起——界面上表现为「取流中」转不完，既无错误也无恢复路径。
     *
     * 每次调用都会重新触发一轮 [ensureReady]，因此网络恢复后用户再点一次即可自愈。
     */
    suspend fun awaitReady(): BiliCredentials {
        (state.value as? AuthState.Ready)?.let { return it.credentials }
        ensureReady()
        return when (val s = state.value) {
            is AuthState.Ready -> s.credentials
            is AuthState.Failed -> throw IllegalStateException("风控凭据不可用：${s.reason}")
            else -> throw IllegalStateException("风控凭据未就绪")
        }
    }

    /** 清空凭据，下次请求会重新种植。用于 debug 面板与收到 -412 时。 */
    suspend fun invalidate() {
        mutex.withLock {
            current = BiliCredentials.EMPTY
            _state.value = AuthState.Idle
            context.authDataStore.edit { it.clear() }
        }
    }

    private suspend fun ensureReady() {
        mutex.withLock {
            if (_state.value is AuthState.Ready) return

            restore()?.let { restored ->
                if (restored.isValid()) {
                    current = restored
                    _state.value = AuthState.Ready(restored)
                    Log.d(TAG, "凭据从本地恢复，跳过网络请求")
                    return
                }
            }

            _state.value = AuthState.Preparing
            var backoffMs = INITIAL_BACKOFF_MS
            repeat(MAX_ATTEMPTS) { attempt ->
                runCatching { fetchCredentials() }
                    .onSuccess { credentials ->
                        current = credentials
                        persist(credentials)
                        _state.value = AuthState.Ready(credentials)
                        Log.d(TAG, "风控凭据种植完成")
                        return
                    }
                    .onFailure { error ->
                        Log.w(TAG, "凭据获取失败（第 ${attempt + 1} 次）：${error.message}")
                        _state.value = AuthState.Failed(error.message ?: "unknown")
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    }
            }
        }
    }

    /** 两次请求备齐三件套。 */
    private suspend fun fetchCredentials(): BiliCredentials {
        val spi = api.getBuvid()
        require(spi.code == 0 && spi.data != null) { "spi_v2 失败: code=${spi.code} ${spi.message}" }

        val ts = System.currentTimeMillis() / 1000
        val ticket = api.genWebTicket(
            keyId = TICKET_KEY_ID,
            hexsign = hmacSha256Hex(key = TICKET_HMAC_KEY, message = "ts$ts"),
            ts = ts,
        )
        require(ticket.code == 0 && ticket.data != null) {
            "GenWebTicket 失败: code=${ticket.code} ${ticket.message}"
        }

        // buvid 激活。刚下发的 buvid 未经激活时服务端不信任，
        // 搜索只会返回 v_voucher 风控凭据而非结果。失败不阻断——playurl 仍可用。
        val activated = runCatching {
            val body = buildActivationPayload(spi.data.buvid3)
                .toRequestBody("application/json".toMediaType())
            val response = api.activateBuvid(body)
            Log.d(TAG, "buvid 激活: code=${response.code}")
            response.code == 0
        }.getOrElse {
            Log.w(TAG, "buvid 激活失败：${it.message}")
            false
        }

        val nav = ticket.data.nav
        // GenWebTicket 通常顺带返回 WBI 密钥；万一没有则回退 nav 接口
        val (imgKey, subKey) = if (nav != null && nav.img.isNotBlank()) {
            WbiSigner.extractKey(nav.img) to WbiSigner.extractKey(nav.sub)
        } else {
            val navResp = api.getNav()
            val wbi = navResp.data?.wbiImg
            require(wbi != null) { "nav 未返回 wbi_img" }
            WbiSigner.extractKey(wbi.imgUrl) to WbiSigner.extractKey(wbi.subUrl)
        }

        return BiliCredentials(
            buvid3 = spi.data.buvid3,
            buvid4 = spi.data.buvid4,
            ticket = ticket.data.ticket,
            ticketExpiresAt = (ticket.data.createdAt + ticket.data.ttl) * 1000,
            wbiImgKey = imgKey,
            wbiSubKey = subKey,
            wbiFetchedAt = System.currentTimeMillis(),
            activated = activated,
        )
    }

    private suspend fun persist(c: BiliCredentials) {
        context.authDataStore.edit { p ->
            p[KEY_BUVID3] = c.buvid3
            p[KEY_BUVID4] = c.buvid4
            p[KEY_TICKET] = c.ticket
            p[KEY_TICKET_EXP] = c.ticketExpiresAt
            p[KEY_WBI_IMG] = c.wbiImgKey
            p[KEY_WBI_SUB] = c.wbiSubKey
            p[KEY_WBI_AT] = c.wbiFetchedAt
            p[KEY_ACTIVATED] = c.activated
        }
    }

    private suspend fun restore(): BiliCredentials? {
        val p = context.authDataStore.data.first()
        val buvid3 = p[KEY_BUVID3] ?: return null
        return BiliCredentials(
            buvid3 = buvid3,
            buvid4 = p[KEY_BUVID4].orEmpty(),
            ticket = p[KEY_TICKET].orEmpty(),
            ticketExpiresAt = p[KEY_TICKET_EXP] ?: 0L,
            wbiImgKey = p[KEY_WBI_IMG].orEmpty(),
            wbiSubKey = p[KEY_WBI_SUB].orEmpty(),
            wbiFetchedAt = p[KEY_WBI_AT] ?: 0L,
            activated = p[KEY_ACTIVATED] ?: false,
        )
    }

    private companion object {
        const val TAG = "BiliSeekAuth"

        /** GenWebTicket 的固定常量。 */
        const val TICKET_KEY_ID = "ec02"
        const val TICKET_HMAC_KEY = "XgwSnGZ1p"

        const val MAX_ATTEMPTS = 5
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L

        val KEY_BUVID3 = stringPreferencesKey("buvid3")
        val KEY_BUVID4 = stringPreferencesKey("buvid4")
        val KEY_TICKET = stringPreferencesKey("ticket")
        val KEY_TICKET_EXP = longPreferencesKey("ticket_expires_at")
        val KEY_WBI_IMG = stringPreferencesKey("wbi_img_key")
        val KEY_WBI_SUB = stringPreferencesKey("wbi_sub_key")
        val KEY_WBI_AT = longPreferencesKey("wbi_fetched_at")
        val KEY_ACTIVATED = booleanPreferencesKey("buvid_activated")
    }
}
