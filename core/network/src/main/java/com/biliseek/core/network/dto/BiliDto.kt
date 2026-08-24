package com.biliseek.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** B 站接口统一外壳。`code == 0` 才算成功。 */
@Serializable
data class BiliResponse<T>(
    val code: Int = 0,
    val message: String = "",
    val ttl: Int = 0,
    val data: T? = null,
)

// ── 风控凭据 ──────────────────────────────────────────────

/** `x/frontend/finger/spi_v2` —— 服务端下发 buvid。 */
@Serializable
data class SpiData(
    @SerialName("b_3") val buvid3: String = "",
    @SerialName("b_4") val buvid4: String = "",
)

/** `GenWebTicket` —— bili_ticket，且**顺带返回 WBI 密钥**，可省一次 nav 请求。 */
@Serializable
data class TicketData(
    val ticket: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    val ttl: Long = 0,
    val nav: TicketNav? = null,
)

@Serializable
data class TicketNav(
    val img: String = "",
    val sub: String = "",
)

/** `x/web-interface/nav` —— WBI 密钥的备用来源。 */
@Serializable
data class NavData(
    @SerialName("isLogin") val isLogin: Boolean = false,
    @SerialName("wbi_img") val wbiImg: WbiImg? = null,
)

@Serializable
data class WbiImg(
    @SerialName("img_url") val imgUrl: String = "",
    @SerialName("sub_url") val subUrl: String = "",
)

// ── 视频与取流 ────────────────────────────────────────────

/** `x/player/pagelist` —— 分 P 列表。多 P 投稿在音乐区是常态。 */
@Serializable
data class PageItem(
    val cid: Long = 0,
    val page: Int = 0,
    val part: String = "",
    val duration: Long = 0,
)

/** `x/player/wbi/playurl` */
@Serializable
data class PlayUrlData(
    val quality: Int = 0,
    val timelength: Long = 0,
    val dash: Dash? = null,
    val durl: List<Durl>? = null,
    /** 响度数据，`voice_balance=1` 时返回。B 站投稿音量参差，响度均衡靠它。 */
    val volume: VolumeInfo? = null,
)

@Serializable
data class VolumeInfo(
    @SerialName("measured_i") val measuredI: Double = 0.0,
    @SerialName("target_i") val targetI: Double = 0.0,
)

/** dash 缺失时的老式单文件回退。 */
@Serializable
data class Durl(
    val url: String = "",
    val length: Long = 0,
    val size: Long = 0,
)

@Serializable
data class Dash(
    val duration: Long = 0,
    @SerialName("minBufferTime") val minBufferTime: Double = 1.5,
    val video: List<DashTrack> = emptyList(),
    val audio: List<DashTrack>? = null,
    /** 杜比全景声，需大会员 */
    val dolby: Dolby? = null,
    /** Hi-Res 无损，需大会员。注意是单个对象而非数组。 */
    val flac: Flac? = null,
)

@Serializable
data class Dolby(
    val type: Int = 0,
    val audio: List<DashTrack>? = null,
)

@Serializable
data class Flac(
    val display: Boolean = false,
    val audio: DashTrack? = null,
)

@Serializable
data class DashTrack(
    val id: Int = 0,
    @SerialName("baseUrl") val baseUrl: String = "",
    @SerialName("base_url") val baseUrlAlt: String = "",
    @SerialName("backupUrl") val backupUrl: List<String>? = null,
    val bandwidth: Long = 0,
    @SerialName("mimeType") val mimeType: String = "",
    val codecs: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("frameRate") val frameRate: String = "",
    @SerialName("segment_base") val segmentBase: SegmentBase? = null,
    @SerialName("SegmentBase") val segmentBaseAlt: SegmentBase? = null,
) {
    /** 接口在不同路径下字段名不一致，统一取值。 */
    fun url(): String = baseUrl.ifBlank { baseUrlAlt }

    fun segment(): SegmentBase? = segmentBase ?: segmentBaseAlt
}

@Serializable
data class SegmentBase(
    @SerialName("initialization") val initialization: String = "",
    @SerialName("index_range") val indexRange: String = "",
    @SerialName("indexRange") val indexRangeAlt: String = "",
) {
    fun range(): String = indexRange.ifBlank { indexRangeAlt }
}
