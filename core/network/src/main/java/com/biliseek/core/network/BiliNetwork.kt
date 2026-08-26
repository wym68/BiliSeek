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
        .apply {
            if (BuildConfig.DEBUG) {
                addNetworkInterceptor(
                    okhttp3.logging.HttpLoggingInterceptor { line ->
                        android.util.Log.d("BiliSeekHttp", line)
                    }.apply { level = okhttp3.logging.HttpLoggingInterceptor.Level.HEADERS },
                )
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

    val authGate: AuthGate = AuthGate(context.applicationContext, api)

    val repository: BiliRepository = BiliRepository(api, authGate)

    val loginManager: LoginManager = LoginManager(api, sessionStore)

    val cookieRefresher: CookieRefresher = CookieRefresher(api, sessionStore)

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
        @Volatile
        private var instance: BiliNetwork? = null

        fun get(context: Context): BiliNetwork =
            instance ?: synchronized(this) {
                instance ?: BiliNetwork(context).also { instance = it }
            }
    }
}
