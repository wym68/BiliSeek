package com.biliseek.core.network

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.biliseek.core.network.internal.BiliCredentials
import com.biliseek.core.network.internal.DeviceFingerprint
import com.biliseek.core.network.internal.RetryBudget
import com.biliseek.core.network.internal.SeedingProfile
import com.biliseek.core.network.internal.FingerprintCookies
import com.biliseek.core.network.internal.LoginBuvids
import com.biliseek.core.network.internal.buildActivationPayload
import com.biliseek.core.network.internal.hmacSha256Hex
import com.biliseek.core.network.internal.nextBackoffMs
import com.biliseek.core.network.internal.retryBudgetFor
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * 供 [GenWebTicket] 取 csrf(=bili_jct)。登录后 CookieJar 会给这个 POST 带上 SESSDATA，
     * B 站网关随即校验 csrf==bili_jct，空 csrf → -111。未登录时返回空，与匿名请求一致。
     */
    private val csrfProvider: () -> String = { "" },
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Idle)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    /** 供 CookieJar 同步读取，避免每次请求都走挂起函数。 */
    @Volatile
    var current: BiliCredentials = BiliCredentials.EMPTY
        private set

    /** 启动时调用。立即返回，准备工作在后台进行，走慢退避预算。 */
    fun start() {
        scope.launch { ensureReady(SeedingProfile.BACKGROUND) }
    }

    /**
     * 业务请求在发出前调用。凭据就绪前挂起，就绪后立即返回。
     *
     * **失败时抛异常而不是继续挂起。** 早期实现用 `state.first { it is Ready }` 等待，
     * 一旦重试预算耗尽、状态停在 [AuthState.Failed]，该条件永远不成立，
     * 调用方会无限挂起——界面上表现为「取流中」转不完，既无错误也无恢复路径。
     *
     * 每次调用都会重新触发一轮 [ensureReady]，因此网络恢复后用户再点一次即可自愈。
     *
     * 走 [SeedingProfile.FOREGROUND] 预算：**不退避、不重试、短超时**。
     * 用户在等，多试几次只是把「转圈」拉长；报错并给一个「重试」按钮才是有用的。
     */
    suspend fun awaitReady(): BiliCredentials {
        (state.value as? AuthState.Ready)?.let { return it.credentials }
        ensureReady(SeedingProfile.FOREGROUND)
        return when (val s = state.value) {
            is AuthState.Ready -> s.credentials
            is AuthState.Failed -> throw IllegalStateException("风控凭据不可用：${s.reason}")
            else -> throw IllegalStateException("风控凭据未就绪")
        }
    }

    /**
     * B4：采信登录带回的真实 buvid 系，覆盖自种身份（可信度更高）。
     *
     * 保守约束：**仅在凭据已就绪（wbi 已备）时覆盖**，避免覆盖到半截身份触发重种；只覆盖本项目
     * 实际注入的字段（buvid3 / buvid4 / b_nut）。端到端需真人扫码验证（登录完成态历史上未跑通），
     * 标「未验证」。登出清除真登录 buvid 的语义留待登录跑通后再做（现状登出保留设备身份）。
     */
    internal suspend fun adoptLoginCredentials(buvids: LoginBuvids) {
        if (buvids.buvid3.isBlank()) return
        mutex.withLock {
            val base = (_state.value as? AuthState.Ready)?.credentials ?: return@withLock
            if (base.wbiImgKey.isBlank()) return@withLock
            val merged = base.copy(
                buvid3 = buvids.buvid3,
                buvid4 = buvids.buvid4.ifBlank { base.buvid4 },
                bNut = buvids.bNut.ifBlank { base.bNut },
            )
            current = merged
            persist(merged)
            _state.value = AuthState.Ready(merged)
            Log.d(TAG, "已采信登录带回的真实 buvid 系")
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

    /**
     * 按 [profile] 的预算种植凭据。
     *
     * **锁不覆盖退避。** 早期实现把整个 `repeat(5)` 指数退避（合计 31 秒纯等待）
     * 圈在 `mutex.withLock` 里，后台预热一旦进入退避，所有用户请求全被堵死，
     * 错误要约 85 秒才浮现。这里退避一律在锁外。
     *
     * 每次尝试还整体套一层超时——前台请求可能不是卡在网络上，而是卡在
     * 后台预热持有的那把锁上，只限制网络耗时救不了它。
     */
    private suspend fun ensureReady(profile: SeedingProfile) {
        val budget = retryBudgetFor(profile)
        var attempt = 0
        while (true) {
            val error = attemptSeed(budget) ?: return

            Log.w(TAG, "凭据获取失败（第 ${attempt + 1} 次，$profile）：${error.message}")
            // 后台可能刚刚种成，别把已经就绪的状态覆盖回失败
            if (_state.value !is AuthState.Ready) {
                _state.value = AuthState.Failed(error.message ?: "unknown")
            }

            val backoff = nextBackoffMs(budget, attempt) ?: return
            delay(backoff) // ← 锁外
            attempt++
        }
    }

    /** 一次种植尝试。成功返回 `null`，失败返回原因。 */
    private suspend fun attemptSeed(budget: RetryBudget): Throwable? = try {
        val done = withTimeoutOrNull(budget.attemptTimeoutMs) { seedOnce() } != null
        if (done) null else IllegalStateException("凭据种植超时（${budget.attemptTimeoutMs}ms）")
    } catch (e: CancellationException) {
        throw e // 真正的取消要放行，不能当成一次失败吞掉
    } catch (e: Exception) {
        e
    }

    /** 临界区：检查状态 → 取凭据 → 写入。不含任何退避等待。 */
    private suspend fun seedOnce() = mutex.withLock {
        if (_state.value is AuthState.Ready) return@withLock

        restore()?.let { restored ->
            if (restored.isValid()) {
                current = restored
                _state.value = AuthState.Ready(restored)
                Log.d(TAG, "凭据从本地恢复，跳过网络请求")
                return@withLock
            }
        }

        _state.value = AuthState.Preparing
        val credentials = fetchCredentials()
        current = credentials
        persist(credentials)
        _state.value = AuthState.Ready(credentials)
        Log.d(TAG, "风控凭据种植完成")
    }

    /** 两次请求备齐三件套。 */
    private suspend fun fetchCredentials(): BiliCredentials {
        val spi = api.getBuvid()
        require(spi.code == 0 && spi.data != null) { "spi_v2 失败: code=${spi.code} ${spi.message}" }

        // spi_v2 到手的 buvid 立刻经 current 注入 CookieJar，再调 GenWebTicket——
        // 真实浏览器里该请求总是带着 buvid3/buvid4/b_nut 发出。
        // 只写内存 current（其唯一消费者是 BiliCookieJar），不持久化——种到一半
        // 失败也不留半成品脏档，下次种植从头来过。
        val spiBNut = FingerprintCookies.bNut(System.currentTimeMillis())
        current = BiliCredentials(
            buvid3 = spi.data.buvid3,
            buvid4 = spi.data.buvid4,
            ticket = "",
            ticketExpiresAt = 0,
            wbiImgKey = "",
            wbiSubKey = "",
            wbiFetchedAt = 0,
            activated = false,
            bNut = spiBNut,
        )

        // ① GenWebTicket 是 POST。登录后 CookieJar 会给它带上 SESSDATA，B 站网关随即
        //    校验 csrf==bili_jct，空 csrf → -111。故显式带上 csrf(=bili_jct)；未登录时为空。
        // ② 非致命：ticket 只是风控加分项（bili_ticket 缺失仅提高验证概率），不是搜索/取流的
        //    必要条件。失败就跳过 ticket，WBI 改从 nav 取，照常放行——单个端点失败不再拖垮全 app。
        val ts = System.currentTimeMillis() / 1000
        val ticketData = runCatching {
            val resp = api.genWebTicket(
                keyId = TICKET_KEY_ID,
                hexsign = hmacSha256Hex(key = TICKET_HMAC_KEY, message = "ts$ts"),
                ts = ts,
                csrf = csrfProvider(),
            )
            if (resp.code == 0) resp.data
            else { Log.w(TAG, "GenWebTicket 失败(code=${resp.code} ${resp.message})，回退 nav 取 WBI、跳过 bili_ticket"); null }
        }.getOrElse {
            Log.w(TAG, "GenWebTicket 异常，回退 nav 取 WBI、跳过 bili_ticket：${it.message}")
            null
        }

        // buvid 激活。刚下发的 buvid 未经激活时服务端不信任，
        // 搜索只会返回 v_voucher 风控凭据而非结果。失败不阻断——playurl 仍可用。
        val fingerprint = restoreOrGenerateFingerprint()
        val activated = runCatching {
            val body = buildActivationPayload(spi.data.buvid3, fingerprint)
                .toRequestBody("application/json".toMediaType())
            val response = api.activateBuvid(body)
            Log.d(TAG, "buvid 激活: code=${response.code}")
            response.code == 0
        }.getOrElse {
            Log.w(TAG, "buvid 激活失败：${it.message}")
            false
        }

        val nav = ticketData?.nav
        // GenWebTicket 通常顺带返回 WBI 密钥；缺失或整步失败则回退 nav 接口
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
            ticket = ticketData?.ticket.orEmpty(),
            ticketExpiresAt = ticketData?.let { (it.createdAt + it.ttl) * 1000 } ?: 0L,
            wbiImgKey = imgKey,
            wbiSubKey = subKey,
            wbiFetchedAt = System.currentTimeMillis(),
            activated = activated,
            // b_nut 与 buvid3 同刻生成（种植时刻），保持语义一致
            bNut = spiBNut,
        )
    }

    /**
     * B1：取 per-install 指纹档案，无则生成一次并落库（JSON 存单个 key，避免拆成十几个）。
     * 同一安装每次返回同一份，跨安装不同——打散聚类。
     */
    private suspend fun restoreOrGenerateFingerprint(): DeviceFingerprint {
        context.authDataStore.data.first()[KEY_FINGERPRINT]?.let { stored ->
            runCatching { json.decodeFromString<DeviceFingerprint>(stored) }.getOrNull()?.let { return it }
        }
        val fresh = DeviceFingerprint.generate()
        context.authDataStore.edit { it[KEY_FINGERPRINT] = json.encodeToString(fresh) }
        return fresh
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
            p[KEY_BNUT] = c.bNut
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
            bNut = p[KEY_BNUT].orEmpty(),
        )
    }

    private companion object {
        const val TAG = "BiliSeekAuth"

        /** GenWebTicket 的固定常量。 */
        const val TICKET_KEY_ID = "ec02"
        const val TICKET_HMAC_KEY = "XgwSnGZ1p"

        val KEY_BUVID3 = stringPreferencesKey("buvid3")
        val KEY_BUVID4 = stringPreferencesKey("buvid4")
        val KEY_TICKET = stringPreferencesKey("ticket")
        val KEY_TICKET_EXP = longPreferencesKey("ticket_expires_at")
        val KEY_WBI_IMG = stringPreferencesKey("wbi_img_key")
        val KEY_WBI_SUB = stringPreferencesKey("wbi_sub_key")
        val KEY_WBI_AT = longPreferencesKey("wbi_fetched_at")
        val KEY_ACTIVATED = booleanPreferencesKey("buvid_activated")
        val KEY_FINGERPRINT = stringPreferencesKey("device_fingerprint")
        val KEY_BNUT = stringPreferencesKey("b_nut")
    }
}
