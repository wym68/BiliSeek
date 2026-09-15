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

/** `x/web-interface/nav` —— WBI 密钥的备用来源，登录后也含用户名与头像。 */
@Serializable
data class NavData(
    @SerialName("isLogin") val isLogin: Boolean = false,
    @SerialName("wbi_img") val wbiImg: WbiImg? = null,
    /** 登录态下才有：B 站用户名（标题栏显示）。 */
    val uname: String = "",
    /** 登录态下才有：头像 URL，`//i0.hdslb.com/...` 需补协议。 */
    val face: String = "",
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

// ── 搜索 ──────────────────────────────────────────────

@Serializable
data class SearchData(
    val page: Int = 0,
    @SerialName("pagesize") val pageSize: Int = 0,
    @SerialName("numResults") val numResults: Int = 0,
    @SerialName("numPages") val numPages: Int = 0,
    val result: List<SearchVideo>? = null,
)

@Serializable
data class SearchVideo(
    val bvid: String = "",
    val aid: Long = 0,
    /** 含 `<em>` 高亮标签，展示前需剥离 */
    val title: String = "",
    val author: String = "",
    val mid: Long = 0,
    /** 形如 "3:51" */
    val duration: String = "",
    /** 封面，返回的是 //i0.hdslb.com/... 需补协议 */
    val pic: String = "",
    val play: Long = 0,
    val danmaku: Long = 0,
    val pubdate: Long = 0,
    @SerialName("ugc_pay")
    val ugcPay: Int = 0,
)

// ── 登录 ──────────────────────────────────────────────

@Serializable
data class QrGenerateData(
    val url: String = "",
    @SerialName("qrcode_key") val qrcodeKey: String = "",
)

@Serializable
data class QrPollData(
    val url: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    val timestamp: Long = 0,
    /** 0 成功 / 86101 未扫码 / 86090 已扫未确认 / 86038 已失效 */
    val code: Int = 0,
    val message: String = "",
)

@Serializable
data class CookieInfoData(
    val refresh: Boolean = false,
    val timestamp: Long = 0,
)

@Serializable
data class RefreshData(
    val status: Int = 0,
    val message: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
)

// ── 稍后再看 ──────────────────────────────────────────

/** `x/v2/history/toview` —— 稍后再看列表。需登录 Cookie。 */
@Serializable
data class WatchLaterData(
    val count: Int = 0,
    val list: List<WatchLaterItem> = emptyList(),
)

@Serializable
data class WatchLaterItem(
    val aid: Long = 0,
    val bvid: String = "",
    val cid: Long = 0,
    val title: String = "",
    val pic: String = "",
    /** 秒。 */
    val duration: Long = 0,
    /** 已播进度（秒），-1 = 未开播。 */
    val progress: Long = -1,
    val owner: WatchLaterOwner? = null,
)

@Serializable
data class WatchLaterOwner(
    val mid: Long = 0,
    val name: String = "",
)

// ── 合集（M13-N）───────────────────────────────────

/** `x/series/series` —— 我订阅的合集列表。需登录。 */
@Serializable
data class SeriesListData(
    val count: Int = 0,
    val list: List<SeriesItem> = emptyList(),
)

@Serializable
data class SeriesItem(
    val id: Long = 0,
    val title: String = "",
    val cover: String = "",
    val description: String = "",
)

/** `x/series/archives` —— 合集内容（分页）。需登录。 */
@Serializable
data class SeriesArchivesData(
    val count: Int = 0,
    val list: List<SeriesArchive> = emptyList(),
    val page: SeriesPage = SeriesPage(),
)

@Serializable
data class SeriesArchive(
    val bvid: String = "",
    val cid: Long = 0,
    val title: String = "",
    val cover: String = "",
    val duration: Long = 0,
    val author: String = "",
)

@Serializable
data class SeriesPage(
    val pn: Int = 1,
    val ps: Int = 20,
    val count: Int = 0,
)

// ── 订阅合集（x/v3/fav/folder/collected/list）────────────

/** `x/v3/fav/folder/collected/list` —— 用户订阅的合集/收藏夹追更列表。需登录。 */
@Serializable
data class CollectedListData(
    val count: Int = 0,
    val list: List<CollectedItem> = emptyList(),
    val has_more: Boolean = false,
)

@Serializable
data class CollectedItem(
    val id: Long = 0,
    val title: String = "",
    val cover: String = "",
    val upper: CollectedUpper = CollectedUpper(),
    val media_count: Int = 0,
    val ctime: Long = 0,
    val intro: String = "",
    val attr: Int = 0,
    val state: Int = 0,
)

@Serializable
data class CollectedUpper(
    val mid: Long = 0,
    val name: String = "",
)

/** `x/space/fav/season/list` —— 追更合集内容。需登录。 */
@Serializable
data class CollectionSeasonData(
    val info: CollectionSeasonInfo = CollectionSeasonInfo(),
    val medias: List<CollectionMediaItem>? = null,
)

@Serializable
data class CollectionSeasonInfo(
    val id: Long = 0,
    val season_type: Int = 0,
    val title: String = "",
    val cover: String = "",
    val upper: CollectionUpper = CollectionUpper(),
    val cnt_info: CollectionCntInfo = CollectionCntInfo(),
    val media_count: Int = 0,
    val intro: String = "",
)

@Serializable
data class CollectionUpper(
    val mid: Long = 0,
    val name: String = "",
)

@Serializable
data class CollectionCntInfo(
    val collect: Int = 0,
    val play: Int = 0,
    val danmaku: Int = 0,
)

@Serializable
data class CollectionMediaItem(
    val id: Long = 0,
    val title: String = "",
    val cover: String = "",
    val duration: Int = 0,
    val pubtime: Long = 0,
    val bvid: String = "",
    val upper: CollectionUpper = CollectionUpper(),
    val cnt_info: CollectionCntInfo = CollectionCntInfo(),
)
