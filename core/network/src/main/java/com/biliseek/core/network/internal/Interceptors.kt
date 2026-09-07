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
        val req = chain.request()
        // 「缺则补」：写请求可用 @Header("Referer") 指定视频页 Referer，不能被无条件覆盖。
        // 读请求不带 Referer，仍补 www 根——行为不变。
        val builder = req.newBuilder().header("User-Agent", BILI_USER_AGENT)
        if (req.header("Referer").isNullOrBlank()) builder.header("Referer", "$BILI_ORIGIN/")
        if (req.header("Origin").isNullOrBlank()) builder.header("Origin", BILI_ORIGIN)
        return chain.proceed(builder.build())
    }
}

/**
 * 注入风控 cookie。凭据从 [credentialsProvider] 同步读取，不阻塞请求。
 */
internal class BiliCookieJar(
    private val credentialsProvider: () -> BiliCredentials,
    private val sessionProvider: () -> com.biliseek.core.network.BiliSession,
) : CookieJar {

    private val serverCookies = mutableMapOf<String, Cookie>()

    /**
     * b_lsid 会话级：整个进程（= 一次会话）复用一份，比每请求现算更像真实浏览器。
     * B2 只注入已确证安全的 b_nut / b_lsid；_uuid / buvid_fp 未做字节级取证，不上线（宁缺毋错）。
     */
    private val sessionLsid: String by lazy {
        FingerprintCookies.bLsid(System.currentTimeMillis(), java.security.SecureRandom())
    }

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
        // B2 指纹 cookie（仅安全项）：b_nut 稳定随 buvid 落库；b_lsid 会话级
        put("b_nut", credentials.bNut)
        put("b_lsid", sessionLsid)

        // 账号凭据。未登录时为空，不影响搜索与播放——「登录非强制」。
        val session = sessionProvider()
        put("SESSDATA", session.sessData)
        put("bili_jct", session.biliJct)
        put("DedeUserID", session.dedeUserId)

        synchronized(serverCookies) {
            serverCookies.values.forEach { cookie ->
                if (result.none { it.name == cookie.name }) result += cookie
            }
        }
        return result
    }
}
