package com.biliseek.core.network

import android.content.Context
import com.biliseek.core.network.internal.BILI_ORIGIN
import com.biliseek.core.network.internal.BILI_USER_AGENT
import com.biliseek.core.network.internal.BiliCookieJar
import com.biliseek.core.network.internal.HeaderInterceptor
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * `:core:network` 的装配入口，也是本模块对外的门面。
 *
 * 单例。M1 阶段手工装配，等引入 DI 框架再迁移。
 */
class BiliNetwork private constructor(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    val sessionStore: SessionStore = SessionStore(context.applicationContext)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HeaderInterceptor())
        // 评论配图上传（multipart）：几 MB 的图在手机网络上，下面默认的读 20 秒 / 写 10 秒不够（10-24 真机超时）。
        // 只放宽等待时间，不重试
        .addInterceptor { chain ->
            if (chain.request().body is okhttp3.MultipartBody) {
                chain.withWriteTimeout(UPLOAD_TIMEOUT_S, TimeUnit.SECONDS)
                    .withReadTimeout(UPLOAD_TIMEOUT_S, TimeUnit.SECONDS)
                    .proceed(chain.request())
            } else {
                chain.proceed(chain.request())
            }
        }
        .apply {
            if (BuildConfig.DEBUG) {
                // 应用拦截器：先读取原始 gzip 字节并打日志，再将 body 替换为解压后的明文，
                // 让后续的 logging 拦截器看到可读的 JSON。
                addInterceptor { chain ->
                    val request = chain.request()
                    val response = chain.proceed(request)
                    val body = response.body ?: return@addInterceptor response
                    if (response.header("Content-Encoding")?.lowercase() == "gzip") {
                        val bufferedSource = body.source()
                        bufferedSource.request(Long.MAX_VALUE)
                        val gzippedBytes = bufferedSource.readByteArray()
                        val plainBytes = GZIPInputStream(gzippedBytes.inputStream()).readBytes()
                        android.util.Log.d("BiliSeekHttp", "<< compressed " + gzippedBytes.size + "B → " + plainBytes.size + "B")
                        val plainBody = okhttp3.ResponseBody.create(
                            body.contentType(), plainBytes
                        )
                        response.newBuilder()
                            .header("Content-Encoding", "identity")
                            .body(plainBody)
                            .build()
                    } else {
                        response
                    }
                }
                val logLine = okhttp3.logging.HttpLoggingInterceptor.Logger { line -> android.util.Log.d("BiliSeekHttp", line) }
                val bodyLogger = okhttp3.logging.HttpLoggingInterceptor(logLine)
                    .apply { level = okhttp3.logging.HttpLoggingInterceptor.Level.BODY }
                // multipart（评论配图上传）只记请求行和结果：整张图的字节打进 logcat 会把缓冲区冲掉、上传也被拖慢
                val basicLogger = okhttp3.logging.HttpLoggingInterceptor(logLine)
                    .apply { level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC }
                addInterceptor { chain ->
                    if (chain.request().body is okhttp3.MultipartBody) basicLogger.intercept(chain) else bodyLogger.intercept(chain)
                }
            }
        }
        .cookieJar(BiliCookieJar({ authGate.current }, { sessionStore.current }))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val api: BiliApi = Retrofit.Builder()
        .baseUrl("https://api.bilibili.com/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(BiliApi::class.java)

    val authGate: AuthGate =
        AuthGate(context.applicationContext, api, csrfProvider = { sessionStore.current.biliJct })

    val cookieRefresher: CookieRefresher = CookieRefresher(api, sessionStore)

    /** B3 全局风控闸门。进程单例，跨所有请求共享冷却/限速状态。 */
    private val riskCooldown = com.biliseek.core.network.internal.RiskCooldown()
    private val requestThrottle = com.biliseek.core.network.internal.RequestThrottle()

    val loginManager: LoginManager = LoginManager(api, sessionStore, authGate)

    val repository: BiliRepository = BiliRepository(
        api, authGate, sessionStore, cookieRefresher, riskCooldown, requestThrottle,
        onSessionExpired = { loginManager.expire() },
    )

    /**
     * 播放器取流时要用的请求头。
     * B 站 CDN 校验 Referer，ExoPlayer 的 DataSource 必须带上，否则 403。
     */
    val mediaRequestHeaders: Map<String, String> = mapOf(
        "User-Agent" to BILI_USER_AGENT,
        "Referer" to "$BILI_ORIGIN/",
        "Origin" to BILI_ORIGIN,
    )

    companion object {
        private const val UPLOAD_TIMEOUT_S = 60

        @Volatile
        private var instance: BiliNetwork? = null

        fun get(context: Context): BiliNetwork =
            instance ?: synchronized(this) {
                instance ?: BiliNetwork(context).also { instance = it }
            }
    }
}
