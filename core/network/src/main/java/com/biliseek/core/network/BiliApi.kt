package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.NavData
import com.biliseek.core.network.dto.PageItem
import com.biliseek.core.network.dto.PlayUrlData
import com.biliseek.core.network.dto.SpiData
import com.biliseek.core.network.dto.TicketData
import com.biliseek.core.network.dto.CookieInfoData
import com.biliseek.core.network.dto.QrGenerateData
import com.biliseek.core.network.dto.QrPollData
import com.biliseek.core.network.dto.RefreshData
import com.biliseek.core.network.dto.SearchData
import kotlinx.serialization.json.JsonElement
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Url

interface BiliApi {

    // ── 风控接入三件套 ──────────────────────────────────

    /** 服务端下发 buvid3/buvid4。比本地生成 UUID 更不容易被风控识别。 */
    @GET("x/frontend/finger/spi_v2")
    suspend fun getBuvid(): BiliResponse<SpiData>

    /**
     * bili_ticket（3 天 TTL），**响应里顺带带回 WBI 密钥**。
     * 因此冷启动只需两次请求即可备齐 buvid + ticket + wbi。
     */
    @POST("bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket")
    suspend fun genWebTicket(
        @Query("key_id") keyId: String,
        @Query("hexsign") hexsign: String,
        @Query("context[ts]") ts: Long,
        @Query("csrf") csrf: String = "",
    ): BiliResponse<TicketData>

    /**
     * **buvid 激活**。刚由 spi_v2 下发的 buvid 未经激活时服务端不信任，
     * 搜索接口只会返回 `v_voucher` 风控凭据而非结果。
     * playurl 对信任度要求较低，因此这一步在 M1 阶段没有暴露。
     */
    @POST("x/internal/gaia-gateway/ExClimbWuzhi")
    @Headers("Content-Type: application/json")
    suspend fun activateBuvid(@Body body: RequestBody): BiliResponse<JsonElement>

    /** WBI 密钥的备用来源（GenWebTicket 失败时回退）。 */
    @GET("x/web-interface/nav")
    suspend fun getNav(): BiliResponse<NavData>

    // ── 视频与取流 ──────────────────────────────────────

    /** 分 P 列表。cid 未知时先调此接口取第一个 P。 */
    @GET("x/player/pagelist")
    suspend fun getPageList(@Query("bvid") bvid: String): BiliResponse<List<PageItem>>

    /**
     * 取流地址。参数需 WBI 签名，用 [WbiSigner.sign] 在调用点签好后整表传入。
     *
     * 关键参数（见《架构设计篇》§4.1）：
     * `fnval=4048`（DASH+8K+HDR+杜比+Hi-Res 位掩码）、`fnver=0`、`fourk=1`、
     * `voice_balance=1`（索取响度数据）。
     */
    @GET("x/player/wbi/playurl")
    suspend fun getPlayUrl(@QueryMap params: Map<String, String>): BiliResponse<PlayUrlData>

    // ── 搜索 ────────────────────────────────────────────

    /** 分类搜索。参数需 WBI 签名。MVP 只用 `search_type=video`。 */
    @GET("x/web-interface/wbi/search/type")
    suspend fun searchType(@QueryMap params: Map<String, String>): BiliResponse<SearchData>

    /**
     * 展开 b23.tv 短链。OkHttp 默认跟随重定向，最终地址从 `response.raw().request.url` 取。
     *
     * 返回体是最终页面的 HTML，用不上，取到 URL 就关掉。
     */
    @GET
    suspend fun resolveShortLink(@Url url: String): Response<ResponseBody>

    // ── 登录（passport 域，用全 URL）────────────────────

    @GET
    suspend fun generateQrCode(@Url url: String = QR_GENERATE): BiliResponse<QrGenerateData>

    @GET
    suspend fun pollQrCode(
        @Url url: String = QR_POLL,
        @Query("qrcode_key") key: String,
    ): Response<BiliResponse<QrPollData>>

    // ── Cookie 刷新 ────────────────────────────────────

    /**
     * `refresh_csrf` 藏在这个页面的 HTML 里，不是 JSON。
     * 返回原始 [ResponseBody] 由调用方解析——Retrofit 原生支持，
     * 不必为**一个**接口引入 HTML 转换器。
     */
    @GET
    suspend fun correspond(@Url url: String): Response<ResponseBody>

    @GET
    suspend fun cookieInfo(
        @Url url: String = COOKIE_INFO,
        @Query("csrf") csrf: String,
    ): BiliResponse<CookieInfoData>

    @FormUrlEncoded
    @POST
    suspend fun cookieRefresh(
        @Url url: String = COOKIE_REFRESH,
        @Field("csrf") csrf: String,
        @Field("refresh_csrf") refreshCsrf: String,
        @Field("source") source: String = "main_web",
        @Field("refresh_token") refreshToken: String,
    ): Response<BiliResponse<RefreshData>>

    @FormUrlEncoded
    @POST
    suspend fun confirmRefresh(
        @Url url: String = CONFIRM_REFRESH,
        @Field("csrf") csrf: String,
        @Field("refresh_token") oldRefreshToken: String,
    ): BiliResponse<JsonElement>

    companion object {
        const val QR_GENERATE = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        const val QR_POLL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
        const val COOKIE_INFO = "https://passport.bilibili.com/x/passport-login/web/cookie/info"
        const val COOKIE_REFRESH = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh"
        const val CONFIRM_REFRESH = "https://passport.bilibili.com/x/passport-login/web/confirm/refresh"
    }
}
