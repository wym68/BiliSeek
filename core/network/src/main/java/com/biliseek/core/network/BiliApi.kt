package com.biliseek.core.network

import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.NavData
import com.biliseek.core.network.dto.PageItem
import com.biliseek.core.network.dto.PlayUrlData
import com.biliseek.core.network.dto.SpiData
import com.biliseek.core.network.dto.TicketData
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap

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
}
