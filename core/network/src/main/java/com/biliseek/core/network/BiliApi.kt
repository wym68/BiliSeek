package com.biliseek.core.network

import com.biliseek.core.network.dto.AddReplyData
import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.NavData
import com.biliseek.core.network.dto.NewlistRankData
import com.biliseek.core.network.dto.ReplyData
import com.biliseek.core.network.dto.PageItem
import com.biliseek.core.network.dto.VideoViewInfo
import com.biliseek.core.network.dto.PlayUrlData
import com.biliseek.core.network.dto.PlayerV2Data
import com.biliseek.core.network.dto.ConclusionData
import com.biliseek.core.network.dto.SubtitleBody
import com.biliseek.core.network.dto.SpiData
import com.biliseek.core.network.dto.TicketData
import com.biliseek.core.network.dto.CookieInfoData
import com.biliseek.core.network.dto.QrGenerateData
import com.biliseek.core.network.dto.QrPollData
import com.biliseek.core.network.dto.RefreshData
import com.biliseek.core.network.dto.SearchData
import com.biliseek.core.network.dto.FavoriteFolderData
import com.biliseek.core.network.dto.FavoriteFolderListData
import com.biliseek.core.network.dto.FavoriteResourceData
import com.biliseek.core.network.dto.DynamicFeedData
import com.biliseek.core.network.dto.DynamicDetailData
import com.biliseek.core.network.dto.DynThumbRequest
import com.biliseek.core.network.dto.FollowingData
import com.biliseek.core.network.dto.RelationData
import com.biliseek.core.network.dto.UserSearchData
import com.biliseek.core.network.dto.UserCardData
import com.biliseek.core.network.dto.SpaceArchiveData
import com.biliseek.core.network.dto.WatchLaterData
import com.biliseek.core.network.dto.SeriesArchivesData
import com.biliseek.core.network.dto.SeriesArchive
import com.biliseek.core.network.dto.SeriesItem
import com.biliseek.core.network.dto.SeriesListData
import com.biliseek.core.network.dto.SeriesPage
import com.biliseek.core.network.dto.CollectedListData
import com.biliseek.core.network.dto.CollectionSeasonData
import com.biliseek.core.network.dto.MusicListData
import com.biliseek.core.network.dto.MusicPeriodsData
import com.biliseek.core.network.dto.PopularVideo
import com.biliseek.core.network.dto.PreciousData
import com.biliseek.core.network.dto.RankingData
import com.biliseek.core.network.dto.WeeklySeriesListData
import com.biliseek.core.network.dto.WeeklySeriesOneData
import kotlinx.serialization.json.JsonElement
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Url

/**
 * 动态流（feed/all、feed/space）的 features 声明，**与官方 web 端对齐**。
 *
 * 背景（bilipai 实践，ref/bilipai ApiClient.kt 同常量）：不带这些参数时 B 站返回降级形态，
 * 新版图文（DYNAMIC_TYPE_OPUS）不下发 summary/正文，动态卡片只剩图片没有文字。
 * 声明后 feed 直接携带 opus.summary/rich_text_nodes，无需逐条拉详情。
 */
internal const val DYNAMIC_FEED_FEATURES =
    "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote,decorationCard," +
        "onlyfansAssetsV2,onlyfansQaCard,forwardListHidden,ugcDelete"

/** web detail 的 features（对齐 bilipai DYNAMIC_DETAIL_FEATURES）。 */
internal const val DYNAMIC_DETAIL_FEATURES =
    "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote,endFooterHidden,decorationCard," +
        "onlyfansAssetsV2,ugcDelete,onlyfansQaCard,commentsNewVersion,forwardListHidden,htmlNewStyle"

/** opus/detail 只带 htmlNewStyle（bilipai 注：这样旧专栏才返回完整 paragraphs）。 */
internal const val OPUS_DETAIL_FEATURES = "htmlNewStyle"

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

    /** 视频详情。公开只读接口，无需 WBI 签名。 */
    @GET("x/web-interface/view")
    suspend fun getVideoView(@Query("bvid") bvid: String): BiliResponse<VideoViewInfo>

    /**
     * 取流地址。参数需 WBI 签名，用 [WbiSigner.sign] 在调用点签好后整表传入。
     *
     * 关键参数（见《架构设计篇》§4.1）：
     * `fnval=4048`（DASH+8K+HDR+杜比+Hi-Res 位掩码）、`fnver=0`、`fourk=1`、
     * `voice_balance=1`（索取响度数据）。
     */
    @GET("x/player/wbi/playurl")
    suspend fun getPlayUrl(@QueryMap params: Map<String, String>): BiliResponse<PlayUrlData>

    /** 播放器信息：字幕列表 + UP 主章节（AI 总结用，工单 AI-4）。bvid、cid + WBI 签名，调用处签。 */
    @GET("x/player/wbi/v2")
    suspend fun playerV2(@QueryMap params: Map<String, String>): BiliResponse<PlayerV2Data>

    /** 字幕文件本体。[url] 是 `subtitle_url`（服务端已签好 auth_key 的完整地址）。 */
    @GET
    suspend fun subtitleBody(@Url url: String): SubtitleBody

    /** B 站官方 AI 总结（工单 AI-4b）。bvid、cid、up_mid（有则带）+ WBI 签名，调用处签。 */
    @GET("x/web-interface/view/conclusion/get")
    suspend fun viewConclusion(@QueryMap params: Map<String, String>): BiliResponse<ConclusionData>

    /**
     * 弹幕分段（protobuf，6 分钟一段，`segment_index` 从 1 起）。参数需 WBI 签名。
     * 返回原始字节由调用方解码；出错时返回的是 JSON（见 `DanmakuProto.jsonErrorCode`）。
     */
    @GET("x/v2/dm/wbi/web/seg.so")
    suspend fun danmakuSegment(@QueryMap params: Map<String, String>): Response<ResponseBody>

    // ── 搜索 ────────────────────────────────────────────

    /** 分类搜索。参数需 WBI 签名。MVP 只用 `search_type=video`。 */
    @GET("x/web-interface/wbi/search/type")
    suspend fun searchType(@QueryMap params: Map<String, String>): BiliResponse<SearchData>

    /** UP 主分类搜索（`search_type=bili_user`，M11-D）。同 URL 不同 DTO。 */
    @GET("x/web-interface/wbi/search/type")
    suspend fun searchUsers(@QueryMap params: Map<String, String>): BiliResponse<UserSearchData>

    /** 用户卡片信息（头像、粉丝数等）。无需 WBI 签名。 */
    @GET("x/web-interface/card")
    suspend fun userCard(@Query("mid") mid: Long): BiliResponse<UserCardData>

    /**
     * UP 主空间投稿（M11-D）。
     *
     * ⚠️ **必须 WBI 签名**——不加实测返回 -403（chen310/plugin.video.bili#8 同坑），
     * 参数表整表签名后传入。
     */
    @GET("x/space/wbi/arc/search")
    suspend fun spaceArchives(@QueryMap params: Map<String, String>): BiliResponse<SpaceArchiveData>

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

    // ── 收藏夹（M11）──────────────────────────────────────

    /** 我创建的收藏夹目录。`up_mid` = 当前登录用户 mid，需登录态 Cookie。 */
    @GET("x/v3/fav/folder/created/list-all")
    suspend fun favoriteFolders(
        @Query("up_mid") upMid: Long,
    ): BiliResponse<FavoriteFolderListData>

    /** 收藏夹内容（分页）。`ps` 定义域 1–20。 */
    @GET("x/v3/fav/resource/list")
    suspend fun favoriteResources(
        @Query("media_id") mediaId: Long,
        @Query("pn") pn: Int,
        @Query("ps") ps: Int = 20,
        @Query("order") order: String = "mtime",
    ): BiliResponse<FavoriteResourceData>

    // ── 动态（M11-C）──────────────────────────────────────

    /**
     * 关注动态流。`type=all`（含图文/转发）或 `type=video`（仅视频投稿）。
     * 需登录态 Cookie；分页用返回的 `offset`。
     * features/platform 对齐官方 web 端，保证 OPUS 图文下发 summary 正文。
     */
    @GET("x/polymer/web-dynamic/v1/feed/all")
    suspend fun dynamicFeed(
        @Query("type") type: String = "all",
        @Query("offset") offset: String = "",
        @Query("features") features: String = DYNAMIC_FEED_FEATURES,
        @Query("platform") platform: String = "web",
    ): BiliResponse<DynamicFeedData>

    /** 单个 UP 的空间动态（「按 UP 筛选」与 UP 空间页共用）。未登录必 -352。 */
    @GET("x/polymer/web-dynamic/v1/feed/space")
    suspend fun dynamicSpace(
        @Query("host_mid") hostMid: Long,
        @Query("offset") offset: String = "",
        @Query("features") features: String = DYNAMIC_FEED_FEATURES,
        @Query("platform") platform: String = "web",
    ): BiliResponse<DynamicFeedData>

    /** 关注列表（UP 筛选 chips 的数据源）。`ps ≤ 50`。 */
    @GET("x/relation/followings")
    suspend fun followings(
        @Query("vmid") vmid: Long,
        @Query("pn") pn: Int = 1,
        @Query("ps") ps: Int = 50,
        @Query("order") order: String = "desc",
    ): BiliResponse<FollowingData>

    // ── 图文动态详情（点开看全文，bilipai 四级降级）────────────────

    /**
     * 单条动态详情（第 1 级主路径 / 第 4 级 rid 兜底）。
     * 走 QueryMap + WBI 签名（[BiliRepository] 的 wbiRequest），参数：
     * id 或 (rid + type=2)、features=[DYNAMIC_DETAIL_FEATURES]、platform=web。
     */
    @GET("x/polymer/web-dynamic/v1/detail")
    suspend fun dynamicDetail(
        @QueryMap params: Map<String, String>,
    ): BiliResponse<DynamicDetailData>

    /** 桌面端详情（第 3 级降级）：部分卡片 web 接口字段更完整，desktop 反而空内容。 */
    @GET("x/polymer/web-dynamic/desktop/v1/detail")
    suspend fun desktopDynamicDetail(
        @QueryMap params: Map<String, String>,
    ): BiliResponse<DynamicDetailData>

    /** 长图文/专栏详情（第 2 级）：需 WBI 签名 + features=htmlNewStyle。 */
    @GET("x/polymer/web-dynamic/v1/opus/detail")
    suspend fun opusDetail(
        @QueryMap params: Map<String, String>,
    ): BiliResponse<DynamicDetailData>

    // ── 合集（M13-N）───────────────────────────────────

    /** 我订阅的合集列表。必须登录。 */
    @GET("x/series/series")
    suspend fun seriesList(@Query("mid") mid: Long): BiliResponse<SeriesListData>

    /** 订阅的合集/收藏夹追更列表（分页）。必须登录。 */
    @GET("x/v3/fav/folder/collected/list")
    suspend fun collectedList(
        @Query("up_mid") upMid: Long,
        @Query("pn") pn: Int = 1,
        @Query("ps") ps: Int = 20,
        @Query("platform") platform: String = "web",
    ): BiliResponse<CollectedListData>

    /** 合集内容（分页）。 */
    @GET("x/series/archives")
    suspend fun seriesArchives(
        @Query("series_id") seriesId: Long,
        @Query("pn") pn: Int,
        @Query("ps") ps: Int = 20,
    ): BiliResponse<SeriesArchivesData>

    /** 追更合集内容（分页）。 */
    @GET("x/space/fav/season/list")
    suspend fun collectionSeasonList(
        @Query("season_id") seasonId: Long,
        @Query("pn") pn: Int = 1,
        @Query("ps") ps: Int = 20,
    ): BiliResponse<CollectionSeasonData>

    // ── 稍后再看 ────────────────────────────────────────

    /** 稍后再看列表。需登录 Cookie（未登录返回 -101）。 */
    @GET("x/v2/history/toview")
    suspend fun watchLater(): BiliResponse<WatchLaterData>

    // ── 榜单（首页快捷入口）──────────────────────────────
    // 均为公开接口，不需要登录，也不需要 WBI 签名。

    /** 每周必看：全部期数。 */
    @GET("x/web-interface/popular/series/list")
    suspend fun weeklySeriesList(): BiliResponse<WeeklySeriesListData>

    /** 每周必看：某一期的视频。 */
    @GET("x/web-interface/popular/series/one")
    suspend fun weeklySeriesOne(@Query("number") number: Int): BiliResponse<WeeklySeriesOneData>

    /** 入站必刷。 */
    @GET("x/web-interface/popular/precious")
    suspend fun preciousVideos(): BiliResponse<PreciousData>

    /** 全站排行榜。`rid=0` 全站，`type=all` 全部稿件。10-21 实测不签名返回正常。 */
    @GET("x/web-interface/ranking/v2")
    suspend fun rankingV2(
        @Query("rid") rid: Int = 0,
        @Query("type") type: String = "all",
    ): BiliResponse<RankingData>

    /**
     * 分区列表（时光机式）：某个**旧版（v1）分区号**在 [timeFrom]–[timeTo]（`yyyyMMdd`）之间的投稿，按 [order] 排序。
     * 后台是搜索服务；10-21 实测不签名、不带 Cookie 返回正常。`pagesize` 必填。
     */
    @GET("x/web-interface/newlist_rank")
    suspend fun newlistRank(
        @Query("cate_id") cateId: Int,
        @Query("order") order: String,
        @Query("page") page: Int,
        @Query("pagesize") pageSize: Int,
        @Query("time_from") timeFrom: String,
        @Query("time_to") timeTo: String,
        @Query("main_ver") mainVer: String = "v3",
        @Query("search_type") searchType: String = "video",
        @Query("view_type") viewType: String = "hot_rank",
        @Query("copy_right") copyRight: Int = -1,
        @Query("new_web_tag") newWebTag: Int = 1,
    ): BiliResponse<NewlistRankData>

    /** 音频榜单每期列表。`list_type`：1 = 热榜（热歌榜），2 = 原创榜。 */
    @GET("x/copyright-music-publicity/toplist/all_period")
    suspend fun musicToplistPeriods(@Query("list_type") listType: Int): BiliResponse<MusicPeriodsData>

    /** 音频榜单某一期的歌曲。 */
    @GET("x/copyright-music-publicity/toplist/music_list")
    suspend fun musicToplistSongs(@Query("list_id") listId: Long): BiliResponse<MusicListData>

    /**
     * 取 B 站活动页（`blackboard/era`）的 HTML。页面是运营搭建的，内嵌 JSON 里有按期分组的视频 BV 号。
     * 返回体是整页 HTML，调用方自己读。
     */
    @GET
    suspend fun eraPage(@Url url: String): Response<ResponseBody>

    /**
     * 批量取视频卡片：`ids` 用逗号分隔，可填 BV / av 号。返回以每个 id 为键的对象，
     * 只要求 `Referer` 在 bilibili.com 下，不需要 WBI。热门播客用它给一期的 BV 号补标题、封面、
     * UP 主、时长、播放量。取不到的 id 可能没有键，或值为 null。
     */
    @GET("x/article/cards")
    suspend fun articleCards(@Query("ids") ids: String): BiliResponse<Map<String, PopularVideo?>>

    // ── 评论读（B5）───────────────────────────────────────
    /** 评论列表 · WBI 主链路。参数需 WBI 签名。 */
    @GET("x/v2/reply/wbi/main")
    suspend fun replyMainWbi(@QueryMap params: Map<String, String>): BiliResponse<ReplyData>

    /** 评论列表 · 兼容链路（不带 WBI）。主链路遇 -111/风控时降级用。 */
    @GET("x/v2/reply/main")
    suspend fun replyMain(@QueryMap params: Map<String, String>): BiliResponse<ReplyData>

    /** 楼中楼。 */
    @GET("x/v2/reply/reply")
    suspend fun replyReply(@QueryMap params: Map<String, String>): BiliResponse<ReplyData>

    // ── 写操作（Phase A）──────────────────────────────────
    //
    // 统一 @FormUrlEncoded @POST + @FieldMap，**签名里绝不出现 csrf**——由
    // BiliRepository.writeRequest 唯一注入，调用点物理上无法遗漏（结构性杜绝 -111）。
    // 可选 @Header("Referer") 让写请求带视频页 Referer（body 里的 bvid 拦截器看不到）。

    // ── 视频互动（点赞 / 投币 / 收藏 / 三连）───────────────────
    @FormUrlEncoded
    @POST("x/web-interface/archive/like")
    suspend fun likeVideo(
        @FieldMap fields: Map<String, String>,          // bvid, like(1赞/2取消), csrf
        @Header("Referer") referer: String? = null,
    ): BiliResponse<JsonElement>

    @FormUrlEncoded
    @POST("x/web-interface/coin/add")
    suspend fun addCoin(
        @FieldMap fields: Map<String, String>,          // bvid, multiply(1|2), select_like(0|1), csrf
        @Header("Referer") referer: String? = null,
    ): BiliResponse<JsonElement>

    @FormUrlEncoded
    @POST("x/v3/fav/resource/deal")
    suspend fun dealFavorite(
        @FieldMap fields: Map<String, String>,          // rid(=aid), type(2), add_media_ids, del_media_ids, csrf
    ): BiliResponse<JsonElement>

    /** 新建收藏夹。返回收藏夹元数据（含新 media_id）。 */
    @FormUrlEncoded
    @POST("x/v3/fav/folder/add")
    suspend fun addFavoriteFolder(
        @FieldMap fields: Map<String, String>,          // title, intro, privacy, csrf
    ): BiliResponse<FavoriteFolderData>

    /** 修改收藏夹标题/简介/隐私。 */
    @FormUrlEncoded
    @POST("x/v3/fav/folder/edit")
    suspend fun editFavoriteFolder(
        @FieldMap fields: Map<String, String>,          // media_id, title, intro, privacy, csrf
    ): BiliResponse<JsonElement>

    /** 删除收藏夹。media_ids 逗号分隔，支持批量。 */
    @FormUrlEncoded
    @POST("x/v3/fav/folder/del")
    suspend fun deleteFavoriteFolder(
        @FieldMap fields: Map<String, String>,          // media_ids, csrf
    ): BiliResponse<JsonElement>

    /** 取消订阅合集。 */
    @FormUrlEncoded
    @POST("x/v3/fav/season/unfav")
    suspend fun unfavSeason(
        @FieldMap fields: Map<String, String>,          // platform(web), season_id, csrf
    ): BiliResponse<JsonElement>

    @FormUrlEncoded
    @POST("x/web-interface/archive/like/triple")
    suspend fun tripleAction(
        @FieldMap fields: Map<String, String>,          // bvid, csrf
        @Header("Referer") referer: String? = null,
    ): BiliResponse<JsonElement>

    // ── 稍后再看 / 关注 ────────────────────────────────────────
    @FormUrlEncoded
    @POST("x/v2/history/toview/add")
    suspend fun addToView(@FieldMap fields: Map<String, String>): BiliResponse<JsonElement>   // aid|bvid, csrf

    @FormUrlEncoded
    @POST("x/v2/history/toview/del")
    suspend fun delToView(@FieldMap fields: Map<String, String>): BiliResponse<JsonElement>   // aid|viewed, csrf

    @FormUrlEncoded
    @POST("x/relation/modify")
    suspend fun modifyRelation(@FieldMap fields: Map<String, String>): BiliResponse<JsonElement> // fid, act(1|2), re_src(11), csrf

    // ── 评论 ──────────────────────────────────────────────────
    @FormUrlEncoded
    @POST("x/v2/reply/add")
    suspend fun addReply(@FieldMap fields: Map<String, String>): BiliResponse<AddReplyData>    // oid(aid), type(1), message, root?, parent?, plat(1), csrf → data.rpid

    @FormUrlEncoded
    @POST("x/v2/reply/action")
    suspend fun replyAction(@FieldMap fields: Map<String, String>): BiliResponse<JsonElement>  // oid, type, rpid, action(1赞/0取消), csrf

    // ── 弹幕（「看」= GET seg.so 属读路径；此处为写）────────────
    @FormUrlEncoded
    @POST("x/v2/dm/post")
    suspend fun sendDanmaku(@FieldMap fields: Map<String, String>): BiliResponse<JsonElement>  // oid(cid), aid, type(1), msg, progress, color, fontsize, mode, pool, plat(1), csrf

    @FormUrlEncoded
    @POST("x/v2/dm/thumbup/add")
    suspend fun likeDanmaku(@FieldMap fields: Map<String, String>): BiliResponse<JsonElement>  // oid(cid), dmid, op(1|2), csrf

    // ── 举报（B6，表单写，复用 writeRequest）────────────────────
    @FormUrlEncoded
    @POST("x/v2/dm/report/add")
    suspend fun reportDanmaku(@FieldMap fields: Map<String, String>): BiliResponse<JsonElement> // cid, dmid, reason, content?, csrf

    @FormUrlEncoded
    @POST("x/v2/reply/report")
    suspend fun reportReply(@FieldMap fields: Map<String, String>): BiliResponse<JsonElement>   // oid, type, rpid, reason, content?, csrf

    // ── 动态互动（Ch4）───────────────────────────────────

    /**
     * 动态点赞 / 取消（up=1 赞 / up=2 取消，已真机验证）。该接口要求 JSON body，
     * `csrf` 走 URL 查询参数——与其余表单写接口的形状不同，不能用 `@FormUrlEncoded`。
     */
    @POST("x/dynamic/feed/dyn/thumb")
    suspend fun likeDynamic(
        @Query("csrf") csrf: String,
        @Body body: DynThumbRequest,
    ): BiliResponse<JsonElement>

    // ── 消息中心（私信 + 互动消息）────────────────────────

    /** 私信会话列表（`api.vc.bilibili.com`）。`session_type=4` 表示全部。 */
    @GET("https://api.vc.bilibili.com/session_svr/v1/session_svr/get_sessions")
    suspend fun getSessions(
        @Query("session_type") sessionType: Int = 4,
        @Query("group_fold") groupFold: Int = 1,
        @Query("unfollow_fold") unfollowFold: Int = 0,
        @Query("sort_rule") sortRule: Int = 2,
        @Query("size") size: Int = 20,
        @Query("build") build: Int = 0,
        @Query("mobi_app") mobiApp: String = "web",
        @Query("web_location") webLocation: String = "333.999",
        @Query("pn") pn: Int = 1,
        @Query("end_ts") endTs: Long = 0,
    ): BiliResponse<com.biliseek.core.network.dto.SessionListData>

    /** 私信历史消息（`api.vc.bilibili.com`）。`beginSeqno=0` 拉首屏。 */
    @GET("https://api.vc.bilibili.com/svr_sync/v1/svr_sync/fetch_session_msgs")
    suspend fun fetchSessionMsgs(
        @Query("talker_id") talkerId: Long,
        @Query("session_type") sessionType: Int = 1,
        @Query("size") size: Int = 20,
        @Query("begin_seqno") beginSeqno: Long = 0,
        @Query("end_seqno") endSeqno: Long = 0,
        @Query("mobi_app") mobiApp: String = "web",
    ): BiliResponse<com.biliseek.core.network.dto.MessageHistoryData>

    /**
     * 批量用户卡片（`api.vc.bilibili.com`）。`get_sessions` 不给普通用户带昵称/头像，
     * 靠这个一次补齐一页会话的对方信息。`uids` 逗号分隔。
     */
    @GET("https://api.vc.bilibili.com/account/v1/user/cards")
    suspend fun getUserCards(
        @Query("uids") uids: String,
    ): BiliResponse<List<com.biliseek.core.network.dto.UserCard>>

    /** 私信未读聚合（`api.vc.bilibili.com`）。 */
    @GET("https://api.vc.bilibili.com/session_svr/v1/session_svr/single_unread")
    suspend fun getSessionUnread(
        @Query("unread_type") unreadType: Int = 0,
        @Query("show_unfollow_list") showUnfollowList: Int = 1,
        @Query("show_dustbin") showDustbin: Int = 1,
        @Query("mobi_app") mobiApp: String = "web",
    ): BiliResponse<com.biliseek.core.network.dto.SessionUnreadData>

    /** 发送私信（`api.vc.bilibili.com`）。`csrf` 与 `csrf_token` 两个都要，缺 `csrf` 服务端回 -111：
     *  `csrf` 取 `writeRequest` 注入的字段，`csrf_token` 由调用方从 `session.biliJct` 传入。 */
    @FormUrlEncoded
    @POST("https://api.vc.bilibili.com/web_im/v1/web_im/send_msg")
    suspend fun postSendMsg(
        @Field("msg[sender_uid]") senderUid: Long,
        @Field("msg[receiver_id]") receiverId: Long,
        @Field("msg[receiver_type]") receiverType: Int = 1,
        @Field("msg[msg_type]") msgType: Int = 1,
        @Field("msg[content]") content: String,
        @Field("msg[timestamp]") timestamp: Long,
        @Field("msg[dev_id]") devId: String,
        @Field("msg[new_face_version]") newFaceVersion: Int = 1,
        @Field("csrf") csrf: String,
        @Field("csrf_token") csrfToken: String,
    ): BiliResponse<com.biliseek.core.network.dto.SendMessageData>

    /** 标记会话已读（`api.vc.bilibili.com`）。同上：`csrf` 与 `csrf_token` 两个都要。 */
    @FormUrlEncoded
    @POST("https://api.vc.bilibili.com/session_svr/v1/session_svr/update_ack")
    suspend fun postUpdateAck(
        @Field("talker_id") talkerId: Long,
        @Field("session_type") sessionType: Int = 1,
        @Field("ack_seqno") ackSeqno: Long,
        @Field("csrf") csrf: String,
        @Field("csrf_token") csrfToken: String,
    ): BiliResponse<com.biliseek.core.network.dto.BiliDtoUnit>

    /** 互动消息未读聚合（`api.bilibili.com x/msgfeed`）。 */
    @GET("https://api.bilibili.com/x/msgfeed/unread")
    suspend fun getMsgfeedUnread(
        @Query("build") build: Int = 0,
        @Query("mobi_app") mobiApp: String = "web",
        @Query("web_location") webLocation: String = "333.1365",
    ): BiliResponse<com.biliseek.core.network.dto.MsgfeedUnreadData>

    /** 回复与@ 列表（`api.bilibili.com x/msgfeed/reply`）。`id`/`reply_time` 用于分页游标。 */
    @GET("https://api.bilibili.com/x/msgfeed/reply")
    suspend fun getMsgfeedReply(
        @Query("id") cursorId: Long? = null,
        @Query("reply_time") cursorTime: Long? = null,
        @Query("platform") platform: String = "web",
        @Query("mobi_app") mobiApp: String = "web",
        @Query("build") build: Int = 0,
        @Query("web_location") webLocation: String = "333.40164",
    ): BiliResponse<com.biliseek.core.network.dto.ReplyFeedData>

    /** @我的 列表（`api.bilibili.com x/msgfeed/at`）。`id`/`at_time` 用于分页游标。 */
    @GET("https://api.bilibili.com/x/msgfeed/at")
    suspend fun getMsgfeedAt(
        @Query("id") cursorId: Long? = null,
        @Query("at_time") cursorTime: Long? = null,
        @Query("platform") platform: String = "web",
        @Query("mobi_app") mobiApp: String = "web",
        @Query("build") build: Int = 0,
        @Query("web_location") webLocation: String = "333.40164",
    ): BiliResponse<com.biliseek.core.network.dto.AtFeedData>

    /** 收到喜欢列表（`api.bilibili.com x/msgfeed/like`）。 */
    @GET("https://api.bilibili.com/x/msgfeed/like")
    suspend fun getMsgfeedLike(
        @Query("id") cursorId: Long? = null,
        @Query("like_time") cursorTime: Long? = null,
        @Query("platform") platform: String = "web",
        @Query("mobi_app") mobiApp: String = "web",
        @Query("build") build: Int = 0,
        @Query("web_location") webLocation: String = "333.40164",
    ): BiliResponse<com.biliseek.core.network.dto.LikeFeedData>

    /** 粉丝列表（`api.bilibili.com x/relation/followers`）。`order=desc` 按关注时间倒序。 */
    @GET("https://api.bilibili.com/x/relation/followers")
    suspend fun getFollowers(
        @Query("vmid") vmid: Long,
        @Query("pn") pn: Int = 1,
        @Query("ps") ps: Int = 20,
        @Query("order") order: String = "desc",
        @Query("order_type") orderType: String = "time",
    ): BiliResponse<com.biliseek.core.network.dto.FollowersData>

    /**
     * 单个 UP 的关注状态。`relation.attribute` 含义未明，**仅按 1=已关注、其它=未关注**解读。
     * ⚠️ 真机抓包前不依赖 attribute 的具体含义。
     */
    @GET("x/relation")
    suspend fun relation(@Query("fid") fid: Long): BiliResponse<RelationData>

    // ── 视频互动状态 / 表情面板（UI 重构）────────────────────

    /**
     * 当前账号对某视频的互动状态：是否已赞、已投几枚币、是否已收藏。**需登录**。
     * 只在播放页展开时按 bvid 拉一次（有内存缓存），纯听后台不请求。
     */
    @GET("x/web-interface/archive/relation")
    suspend fun archiveRelation(
        @Query("bvid") bvid: String,
    ): BiliResponse<com.biliseek.core.network.dto.ArchiveRelation>

    /** 表情面板（评论业务）。进程内只拉一次，见 BiliRepository.emotePanel。 */
    @GET("x/emote/user/panel/web")
    suspend fun emotePanel(
        @Query("business") business: String = "reply",
    ): BiliResponse<com.biliseek.core.network.dto.EmotePanelData>

    companion object {
        const val QR_GENERATE = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        const val QR_POLL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
        const val COOKIE_INFO = "https://passport.bilibili.com/x/passport-login/web/cookie/info"
        const val COOKIE_REFRESH = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh"
        const val CONFIRM_REFRESH = "https://passport.bilibili.com/x/passport-login/web/confirm/refresh"
    }
}
