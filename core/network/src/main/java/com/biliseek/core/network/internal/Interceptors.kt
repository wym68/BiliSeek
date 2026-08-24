package com.biliseek.core.network.internal

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** B 站 Web 端的标准请求特征。行为画像要与「普通用户开网页」一致。 */
internal const val BILI_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

internal const val BILI_ORIGIN = "https://www.bilibili.com"

/**
 * 统一注入 UA / Referer / Origin。
 *
 * **Referer 不是可选项**：B 站的音视频 CDN 会校验它，不带直接 403。
 * 播放与下载请求同样要带（见《架构设计篇》§3.2）。
 */
internal class HeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", BILI_USER_AGENT)
            .header("Referer", "$BILI_ORIGIN/")
            .header("Origin", BILI_ORIGIN)
            .build()
        return chain.proceed(request)
    }
}

/**
 * 注入风控 cookie。凭据从 [credentialsProvider] 同步读取，不阻塞请求。
 */
internal class BiliCookieJar(
    private val credentialsProvider: () -> BiliCredentials,
) : CookieJar {

    private val serverCookies = mutableMapOf<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(serverCookies) {
            cookies.forEach { serverCookies[it.name] = it }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val credentials = credentialsProvider()
        val result = mutableListOf<Cookie>()

        fun put(name: String, value: String) {
            if (value.isBlank()) return
            result += Cookie.Builder()
                .domain("bilibili.com")
                .path("/")
                .name(name)
                .value(value)
                .build()
        }

        put("buvid3", credentials.buvid3)
        put("buvid4", credentials.buvid4)
        put("bili_ticket", credentials.ticket)

        synchronized(serverCookies) {
            serverCookies.values.forEach { cookie ->
                if (result.none { it.name == cookie.name }) result += cookie
            }
        }
        return result
    }
}
