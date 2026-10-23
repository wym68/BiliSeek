package com.biliseek.core.network

import android.util.Log
import com.biliseek.core.network.dto.PageItem
import com.biliseek.core.network.dto.PlayerV2Data
import com.biliseek.core.network.dto.SubtitleBody
import com.biliseek.core.network.dto.VideoViewInfo
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.dto.FavoriteFolderItem
import com.biliseek.core.network.dto.FavoriteResourceData
import com.biliseek.core.network.dto.FavoriteMedia
import com.biliseek.core.network.dto.DynamicFeedData
import com.biliseek.core.network.dto.DynamicItem
import com.biliseek.core.network.dto.DynThumbRequest
import com.biliseek.core.network.dto.FollowingUp
import com.biliseek.core.network.dto.RelationData
import com.biliseek.core.network.dto.SearchUser
import com.biliseek.core.network.dto.SpaceVideo
import com.biliseek.core.network.dto.WatchLaterItem
import com.biliseek.core.network.dto.SeriesItem
import com.biliseek.core.network.dto.SeriesArchivesData
import com.biliseek.core.network.dto.SeriesArchive
import com.biliseek.core.network.dto.SeriesPage
import com.biliseek.core.network.dto.CollectedItem
import com.biliseek.core.network.dto.CollectionSeasonInfo
import com.biliseek.core.network.dto.CollectionMediaItem
import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.dto.ReplyItem
import com.biliseek.core.network.dto.DanmakuItem
import com.biliseek.core.network.internal.DanmakuProto
import com.biliseek.core.network.internal.IdempotentCodes
import com.biliseek.core.network.internal.runCommentRead
import com.biliseek.core.network.internal.resolveUnreadSummary
import com.biliseek.core.network.internal.RequestThrottle
import com.biliseek.core.network.internal.RetryAction
import com.biliseek.core.network.internal.RiskCooldown
import com.biliseek.core.network.internal.ThrottleBucket
import com.biliseek.core.network.internal.parseEraGroups
import com.biliseek.core.network.internal.parseEraPageTitle
import com.biliseek.core.network.internal.resolveCardItems
import com.biliseek.core.network.internal.resolveMusicIssues
import com.biliseek.core.network.internal.resolveRetryAction
import com.biliseek.core.network.internal.resolveWeeklyIssues
import com.biliseek.core.network.internal.toChartPage
import com.biliseek.core.network.internal.toRegionPage
import com.biliseek.core.network.internal.runWithLoginRefresh
import com.biliseek.core.network.internal.runWriteRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put
import java.io.IOException

/** 搜索结果的一页。 */
data class SearchPage(
    val items: List<SearchVideo>,
    val page: Int,
    val totalPages: Int,
    val totalResults: Int,
) {
    val hasMore: Boolean get() = page < totalPages
}

/** 收藏夹内容的一页（M11）。 */
data class FavoriteResourcePage(
    val items: List<FavoriteMedia>,
    val page: Int,
    val hasMore: Boolean,
)

/** 合集内容的一页（M13-N）。 */
data class SeriesArchivesPage(
    val items: List<SeriesArchive>,
    val page: Int,
    val hasMore: Boolean,
)

/** 订阅合集列表的一页。 */
data class CollectedListPage(
    val items: List<CollectedItem>,
    val page: Int,
    val hasMore: Boolean,
    val totalCount: Int,
)

/** 追更合集内容的一页。 */
data class CollectionSeasonPage(
    val info: CollectionSeasonInfo,
    val items: List<CollectionMediaItem>,
    val page: Int,
    val hasMore: Boolean,
)

/** 业务数据入口。UI 层不直接碰 [BiliApi]。 */
class BiliRepository internal constructor(
    private val api: BiliApi,
    private val authGate: AuthGate,
    private val sessionStore: SessionStore,
    private val cookieRefresher: CookieRefresher,   // -111 / -101 后续期重打
    private val riskCooldown: RiskCooldown = RiskCooldown(),          // B3：撞 -352 后全局冷却
    private val requestThrottle: RequestThrottle = RequestThrottle(), // B3：连发限速降噪
    /** passport 确认登录态已失效（[RefreshOutcome.Expired]）时自动登出，接 [LoginManager.expire]。 */
    private val onSessionExpired: suspend () -> Unit,
) {

    /**
     * 搜索。失败一律以 [SearchException] 抛出，携带 [SearchError] 分类——
     * UI 据此决定文案与**是否给重试按钮**。
     */
    suspend fun search(
        keyword: String,
        page: Int = 1,
        order: SearchOrder = SearchOrder.COMPREHENSIVE,
        duration: SearchDuration = SearchDuration.ANY,
    ): Result<SearchPage> = runCatching {
        val credentials = try {
            authGate.awaitReady()
        } catch (e: Exception) {
            throw SearchException(
                SearchError.Network(e.message.orEmpty()),
                e.message ?: "风控凭据不可用",
            )
        }
        val params = WbiSigner.sign(
            params = buildSearchParams(
                keyword = keyword,
                page = page,
                order = order,
                duration = duration,
            ),
            imgKey = credentials.wbiImgKey,
            subKey = credentials.wbiSubKey,
        )
        ensureNotCoolingRead()
        val response = try {
            refreshingOn101 {
                requestThrottle.gate(ThrottleBucket.SEARCH, SEARCH_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) {
                    api.searchType(params)
                }
            }
        } catch (e: IOException) {
            throw SearchException(SearchError.Network(e.message.orEmpty()), e.message ?: "网络不可达")
        }
        when (resolveRetryAction(response.code)) {
            RetryAction.BACKOFF_NO_RETRY -> {
                riskCooldown.onRiskControl()
                throw SearchException(SearchError.Blocked, "触发风控（-352），已进入退避")
            }
            RetryAction.RESEED_CREDENTIALS -> {
                authGate.invalidate()
                throw SearchException(SearchError.CredentialsInvalid, "请求被拦截（-412），已重置设备凭据")
            }
            else -> Unit
        }
        if (response.code != 0) {
            throw SearchException(
                SearchError.Unknown("code=${response.code} ${response.message}"),
                "搜索失败：code=${response.code} ${response.message}",
            )
        }
        riskCooldown.onSuccess()

        // data 为 null 通常意味着 buvid 未激活，服务端只回了 v_voucher 风控凭据。
        // 这种情况必须显式报错，不能静默返回空列表——否则表现为「搜索没反应」，极难排查。
        val data = response.data
            ?: throw SearchException(SearchError.NotActivated, "搜索未返回结果，设备凭据可能未激活")
        SearchPage(
            items = data.result.orEmpty().filter { it.bvid.isNotBlank() },
            page = data.page.takeIf { it > 0 } ?: page,
            totalPages = data.numPages,
            totalResults = data.numResults,
        )
    }

    /**
     * 展开 b23.tv 短链，返回最终地址。
     *
     * **落点必须还在 bilibili.com 域内**——b23.tv 是通用短链服务，
     * 不校验就等于把「跟随任意 302」的能力交给链接的提供者。
     */
    suspend fun expandShortLink(url: String): Result<String> = runCatching {
        val response = api.resolveShortLink(url)
        response.body()?.close()
        val finalUrl = response.raw().request.url.toString()
        require(isTrustedBiliHost(finalUrl)) { "短链落点不在 bilibili.com 域内：$finalUrl" }
        finalUrl
    }

    /** 分 P 列表。用于判断是否需要展开为歌单。 */
    suspend fun pages(bvid: String): Result<List<PageItem>> = runCatching {
        authGate.awaitReady()
        val response = api.getPageList(bvid)
        require(response.code == 0) { "pagelist 失败：code=${response.code} ${response.message}" }
        response.data.orEmpty()
    }

    /** 视频详情。目前只用于播放页"查看作者"在 authorMid 缺失时现查一次兜底。 */
    suspend fun videoView(bvid: String): Result<VideoViewInfo> = runCatching {
        authGate.awaitReady()
        val response = api.getVideoView(bvid)
        require(response.code == 0) { "视频详情失败：code=${response.code} ${response.message}" }
        response.data ?: error("视频详情为空")
    }

    // ── 收藏夹（M11）──────────────────────────────────────

    /**
     * 收藏夹目录。**必须登录**（list-all 需要 up_mid 与登录态 Cookie）。
     * 未登录直接返回失败，由 UI 显示登录引导。
     */
    suspend fun favoriteFolders(): Result<List<FavoriteFolderItem>> = runCatching {
        val mid = requireLoggedInMid()
        accountRead("收藏夹目录") { api.favoriteFolders(upMid = mid) }
            ?.list.orEmpty().filter { it.id > 0 && it.title.isNotBlank() }
    }

    /** 收藏夹内容（分页，`ps ≤ 20`）。必须登录。 */
    suspend fun favoriteResources(
        mediaId: Long,
        page: Int,
        pageSize: Int = 20,
    ): Result<FavoriteResourcePage> = runCatching {
        requireLoggedInMid()
        fetchFavoriteResources(mediaId, page, pageSize) { what, req -> accountRead(what, req) }
    }

    /**
     * 别人的公开收藏夹内容（首页「视频播客」）。同一个接口，**不要求登录**——10-21 实测不带 Cookie 可读。
     * 与首页榜单同属公开页面读，走 [chartRead]（CHART 限速桶）；登录态的 [favoriteResources] 仍走不限速的 [accountRead]。
     */
    suspend fun publicFavoriteResources(
        mediaId: Long,
        page: Int,
        pageSize: Int = 20,
    ): Result<FavoriteResourcePage> = runCatching { fetchFavoriteResources(mediaId, page, pageSize) { what, req -> chartRead(what, req) } }

    /** 请求与映射共用；[read] 决定是否过限速桶（登录态 [accountRead]，公开 [chartRead]）。 */
    private suspend fun fetchFavoriteResources(
        mediaId: Long,
        page: Int,
        pageSize: Int,
        read: suspend (String, suspend () -> BiliResponse<FavoriteResourceData>) -> FavoriteResourceData?,
    ): FavoriteResourcePage {
        val data = read("收藏夹内容") { api.favoriteResources(mediaId, pn = page, ps = pageSize) }
            ?: throw IllegalStateException("收藏夹内容为空")
        return FavoriteResourcePage(
            items = data.medias.filter { it.bvid.isNotBlank() && it.type == 2 },
            page = page,
            hasMore = data.hasMore,
        )
    }

    /**
     * 取收藏夹内首条内容的封面，用于目录列表封面缺失时兜底。
     *
     * 与 [favoriteResources] 的可播放过滤不同：只取封面图，任何 type（视频 / 音频 / 相簿）
     * 都有 cover；空收藏夹或请求失败返回 null。失败不抛——封面是装饰，主流程不能被它阻塞。
     */
    suspend fun firstMediaCover(mediaId: Long): String? = runCatching {
        accountRead("收藏夹内容") { api.favoriteResources(mediaId, pn = 1, ps = 20) }
            ?.medias?.firstOrNull()?.cover?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ── 合集（M13-N）──────────────────────────────────

    /** 合集列表。必须登录。 */
    suspend fun seriesList(mid: Long): Result<List<SeriesItem>> = runCatching {
        requireLoggedInMid()
        accountRead("合集列表") { api.seriesList(mid) }
            ?.list.orEmpty().filter { it.id > 0 && it.title.isNotBlank() }
    }

    /** 订阅合集列表（分页）。必须登录。 */
    suspend fun collectedList(
        mid: Long,
        page: Int,
        pageSize: Int = 20,
    ): Result<CollectedListPage> = runCatching {
        requireLoggedInMid()
        val data = accountRead("订阅合集列表") { api.collectedList(upMid = mid, pn = page, ps = pageSize) }
            ?: throw IllegalStateException("订阅合集列表为空")
        CollectedListPage(
            items = data.list.filter { it.id > 0 && it.title.isNotBlank() },
            page = page,
            hasMore = data.has_more,
            totalCount = data.count,
        )
    }

    /** 合集内容（分页，`ps ≤ 20`）。必须登录。 */
    suspend fun seriesArchives(
        seriesId: Long,
        page: Int,
        pageSize: Int = 20,
    ): Result<SeriesArchivesPage> = runCatching {
        requireLoggedInMid()
        val data = accountRead("合集内容") { api.seriesArchives(seriesId, pn = page, ps = pageSize) }
            ?: throw IllegalStateException("合集内容为空")
        SeriesArchivesPage(
            items = data.list.filter { it.bvid.isNotBlank() },
            page = page,
            hasMore = data.list.size >= pageSize,
        )
    }

    /** 追更合集内容（分页）。必须登录。 */
    suspend fun collectionSeasonList(
        seasonId: Long,
        page: Int,
        pageSize: Int = 20,
    ): Result<CollectionSeasonPage> = runCatching {
        requireLoggedInMid()
        val data = accountRead("追更合集内容") {
            api.collectionSeasonList(seasonId = seasonId, pn = page, ps = pageSize)
        } ?: throw IllegalStateException("追更合集内容为空")
        val medias = data.medias.orEmpty().filter { it.bvid.isNotBlank() }
        CollectionSeasonPage(
            info = data.info,
            items = medias,
            page = page,
            hasMore = medias.size >= pageSize,
        )
    }

    private fun requireLoggedInMid(): Long =
        sessionStore.current.dedeUserId.toLongOrNull()
            ?: throw IllegalStateException("需要登录")

    // ── 动态（M11-C）──────────────────────────────────────

    /** 关注动态流的一页。 */
    data class DynamicPage(
        val entries: List<DynamicEntry>,
        val offset: String,
        val hasMore: Boolean,
    )

    /** 关注动态流。必须登录（未登录 -352，不重试）。 */
    suspend fun dynamicFeed(
        type: String = DynamicType.ALL,
        offset: String = "",
    ): Result<DynamicPage> = runCatching {
        requireLoggedInMid()
        accountRead("动态流") { api.dynamicFeed(type = type, offset = offset) }.toDynamicPage(offset)
    }

    /** 单个 UP 的空间动态（UP 筛选）。必须登录。 */
    suspend fun dynamicSpace(
        hostMid: Long,
        offset: String = "",
    ): Result<DynamicPage> = runCatching {
        requireLoggedInMid()
        accountRead("UP 动态") { api.dynamicSpace(hostMid = hostMid, offset = offset) }.toDynamicPage(offset)
    }

    /**
     * 图文动态详情（点开看全文）。四级降级对齐 bilipai：
     * 1. web `v1/detail?id=` 主路径 → 2. `opus/detail`（OPUS/DRAW 时）→
     * 3. `desktop/v1/detail` 降级（含 WORD 取长文案）→ 4. 按 `basic.rid_str` 再拉。
     * [seed] 是 feed 卡片条目，用于合并（正文取更长、图片取并集、互动数以 feed 为准）。
     * 结果进 LRU 缓存，重复打开不重复请求。
     */
    suspend fun dynamicDetail(id: String, seed: DynamicEntry?): Result<DynamicEntry> = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        val cleanedId = id.trim()
        require(cleanedId.isNotEmpty()) { "动态 id 为空" }

        dynamicDetailCache[cleanedId]?.let { return@runCatching it }

        val candidates = mutableListOf<DynamicItem>()

        // 1) web detail 主路径
        fetchWebDetailItem(cleanedId)?.let(candidates::add)

        // 2) opus/detail：web 结果缺内容、本身是 OPUS/DRAW，或 seed 是 OPUS/DRAW 时
        val webItem = candidates.firstOrNull()
        val seedNeedsOpus = seed?.rawType == DynamicItemType.OPUS || seed?.rawType == DynamicItemType.DRAW
        if (webItem == null || !hasRenderableContent(webItem) ||
            shouldRequestOpusDetail(webItem) || seedNeedsOpus
        ) {
            fetchOpusDetailItem(cleanedId)?.let(candidates::add)
        }

        // 3) desktop detail：仍不可渲染时降级；WORD 纯文字也拉一次取更长文案
        var resolved = pickBestDetailItem(candidates)
        if (resolved == null || !hasRenderableContent(resolved) || resolved.type == DynamicItemType.WORD) {
            fetchDesktopDetailItem(cleanedId)?.let(candidates::add)
            resolved = pickBestDetailItem(candidates)
        }

        // 4) rid 兜底：web/opus/desktop 都没拿到可渲染内容时按资源 id 再拉
        val rid = seed?.ridStr.orEmpty()
        if ((resolved == null || !hasRenderableContent(resolved)) && rid.isNotBlank()) {
            fetchWebDetailItemByRid(rid)?.let(candidates::add)
            resolved = pickBestDetailItem(candidates)
        }

        val item = resolved ?: error("动态详情为空")
        mergeDetailItem(item, seed).also { dynamicDetailCache[cleanedId] = it }
    }

    private suspend fun fetchWebDetailItem(id: String): DynamicItem? =
        wbiRequest(
            buildMap {
                put("id", id)
                put("features", DYNAMIC_DETAIL_FEATURES)
                put("platform", "web")
            },
        ) { signed -> api.dynamicDetail(signed) }
            .getOrNull()?.item

    private suspend fun fetchWebDetailItemByRid(rid: String): DynamicItem? =
        wbiRequest(
            buildMap {
                put("rid", rid)
                put("type", "2")
                put("features", DYNAMIC_DETAIL_FEATURES)
                put("platform", "web")
            },
        ) { signed -> api.dynamicDetail(signed) }
            .getOrNull()?.item

    private suspend fun fetchDesktopDetailItem(id: String): DynamicItem? =
        wbiRequest(
            buildMap {
                put("id", id)
                put("features", DYNAMIC_DETAIL_FEATURES)
            },
        ) { signed -> api.desktopDynamicDetail(signed) }
            .getOrNull()?.item

    private suspend fun fetchOpusDetailItem(id: String): DynamicItem? =
        wbiRequest(
            buildMap {
                put("id", id)
                put("features", OPUS_DETAIL_FEATURES)
                put("timezone_offset", "-480")
                put("platform", "web")
            },
        ) { signed -> api.opusDetail(signed) }
            .getOrNull()?.item

    /** 图文详情 LRU 缓存（容量 32）：点开过的动态重复打开不重复请求。 */
    private val dynamicDetailCache = object : LinkedHashMap<String, DynamicEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DynamicEntry>): Boolean = size > 32
    }

    /** 关注列表（UP 筛选 chips）。必须登录，内存缓存由调用方持有。 */
    suspend fun followings(): Result<List<FollowingUp>> = runCatching {
        val mid = requireLoggedInMid()
        accountRead("关注列表") { api.followings(vmid = mid) }
            ?.list.orEmpty().filter { it.mid > 0 && it.uname.isNotBlank() }
    }

    // ── 稍后再看 ────────────────────────────────────────

    /**
     * 稍后再看列表。必须登录（-101 由 [accountRead] 续期后重打一次）。
     *
     * 只取视频型条目（bvid 非空）；进度字段保留，UI 可展示「播到哪了」。
     */
    suspend fun watchLater(): Result<List<WatchLaterItem>> = runCatching {
        requireLoggedInMid()
        accountRead("稍后再看") { api.watchLater() }
            ?.list.orEmpty().filter { it.bvid.isNotBlank() }
    }

    // ── 榜单（首页快捷入口）────────────────────────────────

    /**
     * 每周必看的全部期数，最新一期在前。公开接口，不需要登录；一次请求，走 [chartRead]。
     * 没有任何期数时返回空列表。
     */
    suspend fun weeklyIssues(): Result<List<ChartIssue>> = runCatching {
        resolveWeeklyIssues(chartRead("每周必看期数") { api.weeklySeriesList() }?.list.orEmpty())
    }

    /** 每周必看的某一期：页头 =「期名 · 主题」，条目是这一期的视频。一次请求，走 [chartRead]。 */
    suspend fun weeklyIssue(issue: ChartIssue): Result<ChartPage> = runCatching {
        chartRead("每周必看") { api.weeklySeriesOne(issue.number) }?.toChartPage() ?: ChartPage.EMPTY
    }

    /** 入站必刷。公开接口，一次请求取全部。 */
    suspend fun preciousVideos(): Result<ChartPage> = runCatching {
        chartRead("入站必刷") { api.preciousVideos() }?.toChartPage() ?: ChartPage.EMPTY
    }

    /** 全站排行榜：页头 = 官方说明，条目按名次。一次请求，走 [chartRead]；不签名。 */
    suspend fun rankingVideos(): Result<ChartPage> = runCatching {
        chartRead("全站排行榜") { api.rankingV2() }?.toChartPage() ?: ChartPage.EMPTY
    }

    /**
     * 热歌榜（音频榜单 `list_type=1` 的「热榜」）的全部期，最新一期在前。
     * 公开接口，不需要登录；一次请求，走 [chartRead]。没有任何期时返回空列表。
     */
    suspend fun hotSongIssues(): Result<List<ChartIssue>> = musicIssues(listType = 1, what = "热歌榜期数")

    /** 原创音乐榜的全部期：与热歌榜同一个接口，`list_type=2`；某一期的歌曲同样用 [hotSongIssue] 取。 */
    suspend fun originalSongIssues(): Result<List<ChartIssue>> = musicIssues(listType = 2, what = "原创榜期数")

    private suspend fun musicIssues(listType: Int, what: String): Result<List<ChartIssue>> = runCatching {
        resolveMusicIssues(chartRead(what) { api.musicToplistPeriods(listType = listType) }?.list.orEmpty())
    }

    /** 热歌榜 / 原创音乐榜的某一期：页头 =「第 N 期」，条目按名次排。一次请求，走 [chartRead]。 */
    suspend fun hotSongIssue(issue: ChartIssue): Result<ChartPage> = runCatching {
        chartRead("音乐榜") { api.musicToplistSongs(issue.id) }?.toChartPage(issue.number) ?: ChartPage.EMPTY
    }

    /**
     * 榜单读请求的统一出口：走 [accountRead] 的风控分流（冷却期内本地快速失败，-352 记入冷却且绝不重试，
     * -412 重置设备凭据），请求本身套 CHART 限速桶。**不加重试循环。**
     */
    private suspend fun <T> chartRead(what: String, request: suspend () -> BiliResponse<T>): T? =
        accountRead(what) {
            requestThrottle.gate(ThrottleBucket.CHART, CHART_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) { request() }
        }

    // ── 分区列表（时光机式）──────────────────────────────

    /**
     * 分区列表的一页：旧版分区号 [tid]、排序 [order]（`click` / `stow` / `coin` / `dm` / `scores`）、
     * 时间 [timeFrom]–[timeTo]（`yyyyMMdd`）。每页 [REGION_PAGE_SIZE] 条。走 [regionRead]；没有结果是空页，**不重试**。
     */
    suspend fun regionVideos(
        tid: Int,
        order: String,
        page: Int,
        timeFrom: String,
        timeTo: String,
    ): Result<RegionPage> = runCatching {
        regionRead("分区列表") {
            api.newlistRank(
                cateId = tid,
                order = order,
                page = page,
                pageSize = REGION_PAGE_SIZE,
                timeFrom = timeFrom,
                timeTo = timeTo,
            )
        }.toRegionPage(requestedPage = page)
    }

    /**
     * 分区列表的读出口：后台是搜索服务，套 SEARCH 限速桶（与搜索共用间隔）；风控分流同 [accountRead]
     * （冷却期内本地快速失败，-352 记入冷却且绝不重试，-412 重置设备凭据）。**不加重试循环。**
     */
    private suspend fun <T> regionRead(what: String, request: suspend () -> BiliResponse<T>): T? =
        accountRead(what) {
            requestThrottle.gate(ThrottleBucket.SEARCH, SEARCH_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) { request() }
        }

    // ── 热门播客（运营搭建的活动页）──────────────────────────

    /**
     * 取一个活动页（`blackboard/era`）：标题 + 按页面里的标签页分组的 BV 号。公开页面，不需要登录。
     * 页面只给 BV 号，标题封面要再经 [videoCards] 补。页面里认不出任何视频时抛 [emptyMessage]，
     * 而不是返回空列表。热门播客与搜索框里的活动页都走这里。
     */
    suspend fun eraPage(
        url: String,
        emptyMessage: String = "这个活动页没能解析：里面没有找到视频，或者页面结构认不出来",
    ): Result<EraPageContent> = runCatching {
        val html = eraPageRead(url)
        val content = withContext(Dispatchers.Default) {
            EraPageContent(title = parseEraPageTitle(html), groups = parseEraGroups(html))
        }
        if (content.groups.isEmpty()) {
            throw SearchException(SearchError.Unknown("era page: no groups"), emptyMessage)
        }
        content
    }

    /**
     * 热门播客：固定网址的活动页，按期（标签页）分组，最新一期在前。
     * **绑定这一个活动页**：运营换页或改版就解析不出，此时抛出明确的错误。
     */
    suspend fun podcastPage(): Result<EraPageContent> =
        eraPage(PODCAST_ERA_URL, emptyMessage = "热门播客页面结构变了，没能解析")

    /**
     * 批量取视频的标题、封面、UP 主、时长、播放量（`x/article/cards`，一次请求），按传入顺序返回，
     * 取不到或已不可用的跳过。公开接口，不需要登录。一次最多 [MAX_CARD_IDS] 个。
     */
    suspend fun videoCards(bvids: List<String>): Result<List<ChartItem>> = runCatching {
        val ids = bvids.distinct().take(MAX_CARD_IDS)
        if (ids.isEmpty()) return@runCatching emptyList()
        val cards = chartRead("视频信息") { api.articleCards(ids.joinToString(",")) }.orEmpty()
        resolveCardItems(ids, cards)
    }

    /**
     * 取 B 站活动页（`bilibili.com` 域内）的 HTML。公开页面，不需要登录。
     *
     * 页面不是 JSON 接口，没有 `code` 字段：冷却期内本地快速失败；HTTP 412 当作被拦，
     * 记入全局冷却且**不重试**；其他非 2xx 报「页面加载失败」。套 CHART 限速桶。
     */
    private suspend fun eraPageRead(url: String): String {
        require(isTrustedBiliHost(url)) { "只取 bilibili.com 域内的页面：$url" }
        authGate.awaitReady()
        ensureNotCoolingRead()
        val response = requestThrottle.gate(ThrottleBucket.CHART, CHART_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) {
            api.eraPage(url)
        }
        if (response.code() == 412) {
            riskCooldown.onRiskControl()
            throw SearchException(SearchError.Blocked, "触发风控（HTTP 412），已进入退避")
        }
        if (!response.isSuccessful) {
            throw SearchException(
                SearchError.Unknown("HTTP ${response.code()}"),
                "页面加载失败：HTTP ${response.code()}",
            )
        }
        riskCooldown.onSuccess()
        return response.body()?.string().orEmpty()
    }

    // ── UP 主搜索与空间投稿（M11-D）────────────────────────

    /** UP 主搜索结果的一页。 */
    data class UserSearchPage(
        val items: List<SearchUser>,
        val page: Int,
        val totalPages: Int,
        val totalResults: Int,
    )

    /** UP 主搜索（`search_type=bili_user`）。不需登录。 */
    suspend fun searchUsers(keyword: String, page: Int = 1): Result<UserSearchPage> {
        val data = wbiRequest(
            params = buildMap {
                put("search_type", "bili_user")
                put("keyword", keyword)
                put("page", page.toString())
                put("page_size", "20")
            },
        ) { api.searchUsers(it) }
        return data.map { d ->
            UserSearchPage(
                items = d.result.filter { it.mid > 0 && it.uname.isNotBlank() },
                page = d.page.takeIf { it > 0 } ?: page,
                totalPages = d.numPages,
                totalResults = d.numResults,
            )
        }
    }

    /** 用户卡片信息（头像、粉丝数等）。无需登录。 */
    suspend fun userCard(mid: Long): Result<SearchUser> = runCatching {
        authGate.awaitReady()
        val response = api.userCard(mid)
        require(response.code == 0) {
            "获取用户信息失败：code=${response.code} ${response.message}"
        }
        val card = response.data?.card
        require(card != null) {
            "获取用户信息失败：card 为空"
        }
        SearchUser(
            mid = card.mid,
            uname = card.name,
            upic = card.face,
            usign = card.sign,
            fans = card.fans,
        )
    }

    /** UP 空间投稿的一页。 */
    data class SpaceArchivePage(
        val items: List<SpaceVideo>,
        val page: Int,
        val hasMore: Boolean,
        val total: Int,
    )

    /** UP 主空间投稿列表。不需登录；**必须 WBI 签名**（不加实测 -403）。 */
    suspend fun spaceArchives(
        mid: Long,
        page: Int = 1,
        pageSize: Int = 30,
    ): Result<SpaceArchivePage> {
        val data = wbiRequest(
            params = buildMap {
                put("mid", mid.toString())
                put("pn", page.toString())
                put("ps", pageSize.toString())
                put("order", "pubdate")
            },
        ) { api.spaceArchives(it) }
        return data.map { d ->
            val list = d.list?.vlist.orEmpty()
            SpaceArchivePage(
                items = list.filter { it.bvid.isNotBlank() },
                page = d.page?.pn?.takeIf { it > 0 } ?: page,
                hasMore = list.size >= pageSize,
                total = d.page?.count ?: 0,
            )
        }
    }

    /**
     * 带 WBI 签名 + 统一风控错误处理的 GET 请求（M11-D 起的新接口共用）。
     * 既有 [search] 保持原样不重构——那是已验收路径，避免回归。
     */
    private suspend fun <T> wbiRequest(
        params: Map<String, String>,
        block: suspend (Map<String, String>) -> BiliResponse<T>,
    ): Result<T> = runCatching {
        val credentials = try {
            authGate.awaitReady()
        } catch (e: Exception) {
            throw SearchException(
                SearchError.Network(e.message.orEmpty()),
                e.message ?: "风控凭据不可用",
            )
        }
        val signed = WbiSigner.sign(
            params = params,
            imgKey = credentials.wbiImgKey,
            subKey = credentials.wbiSubKey,
        )
        ensureNotCoolingRead()
        val response = try {
            refreshingOn101 {
                requestThrottle.gate(ThrottleBucket.FEED, FEED_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) {
                    block(signed)
                }
            }
        } catch (e: IOException) {
            throw SearchException(SearchError.Network(e.message.orEmpty()), e.message ?: "网络不可达")
        }
        when (resolveRetryAction(response.code)) {
            RetryAction.BACKOFF_NO_RETRY -> {
                riskCooldown.onRiskControl()
                throw SearchException(SearchError.Blocked, "触发风控（-352），已进入退避")
            }
            RetryAction.RESEED_CREDENTIALS -> {
                authGate.invalidate()
                throw SearchException(
                    SearchError.CredentialsInvalid,
                    "请求被拦截（-412），已重置设备凭据",
                )
            }
            else -> Unit
        }
        if (response.code != 0) {
            // 调试期：把"双码"分别打出来——确认到底是 code=-111 还是 message 也是 -111
            if (response.code == -111) {
                android.util.Log.d(
                    "BiliSeekReadDebug",
                    "wbiRequest code=-111 msg='${response.message}' " +
                        "paramsSignedKeys=${signed.keys.sorted()} " +
                        "csrfInQuery=${signed.containsKey("csrf")}",
                )
            }
            throw SearchException(
                SearchError.Unknown("code=${response.code} ${response.message}"),
                "请求失败：code=${response.code} ${response.message}",
            )
        }
        riskCooldown.onSuccess()
        response.data
            ?: throw SearchException(SearchError.NotActivated, "响应为空，设备凭据可能未激活")
    }

    private fun DynamicFeedData?.toDynamicPage(requestOffset: String): DynamicPage {
        val data = this ?: throw IllegalStateException("动态响应为空")
        return DynamicPage(
            entries = parseDynamicItems(data.items),
            // 首页（offset 空）时服务端可能不给 offset，用请求值兜底，避免分页断裂
            offset = data.offset.ifBlank { requestOffset },
            hasMore = data.hasMore,
        )
    }

    // ── 写操作（Phase A）──────────────────────────────────────
    //
    // 失败一律以 [WriteException] 抛出，携带 [WriteError] 分类——:app 据此决定
    // 弹登录 / 弹重试 / 弹风控提示 / 静默。[WriteOutcome.AlreadyDone]（幂等重复动作）
    // 在此已折叠成成功，UI 无需区分「本来就赞过」与「刚赞成功」。

    /**
     * 写操作统一入口。绑定 authGate/sessionStore/cookieRefresher 到纯编排函数
     * [runWriteRequest]——csrf 在那里唯一注入，调用点物理上无法遗漏。
     */
    private suspend fun <T> writeRequest(
        payload: Map<String, String>,
        idempotentCodes: Set<Int> = emptySet(),
        referer: String? = null,
        block: suspend (fields: Map<String, String>, referer: String?) -> BiliResponse<T>,
    ): Result<T?> = runCatching {
        if (riskCooldown.remaining() > 0) {
            throw WriteException(WriteError.Blocked, "触发风控冷却中(-352)，请稍后再试")
        }
        val session = sessionStore.current
        requestThrottle.gate(ThrottleBucket.WRITE, WRITE_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) {
            try {
                val result = runWriteRequest(
                    payload = payload,
                    idempotentCodes = idempotentCodes,
                    referer = referer,
                    loggedIn = session.isLoggedIn,
                    initialJct = session.biliJct,
                    awaitReady = { authGate.awaitReady() },
                    refresh = { cookieRefresher.refreshAfterRejection(session) },
                    invalidate = { authGate.invalidate() },
                    logOut = onSessionExpired,
                    currentJct = { sessionStore.current.biliJct },
                    log = { msg -> Log.d("BiliSeekWriteDebug", msg) },
                    block = block,
                )
                riskCooldown.onSuccess()
                result
            } catch (e: WriteException) {
                if (e.error is WriteError.Blocked) riskCooldown.onRiskControl()
                throw e
            }
        }
    }

    /**
     * 消息中心读请求的统一响应检查，风控分流与搜索 / 评论读路径一致：
     * -352 → 记入全局冷却并抛 [SearchError.Blocked]（绝不重试）；-412 → 作废设备凭据并抛
     * [SearchError.CredentialsInvalid]；其余非 0 → 抛 [SearchError.Unknown]；成功 → 清冷却。
     * （-101 的续期重打在发请求处，见 [refreshingOn101]。）
     */
    private suspend fun <T> checkMessageRead(response: BiliResponse<T>, what: String): T? {
        when (resolveRetryAction(response.code)) {
            RetryAction.BACKOFF_NO_RETRY -> {
                riskCooldown.onRiskControl()
                throw SearchException(SearchError.Blocked, "触发风控（-352），已进入退避")
            }
            RetryAction.RESEED_CREDENTIALS -> {
                authGate.invalidate()
                throw SearchException(SearchError.CredentialsInvalid, "请求被拦截（-412），已重置设备凭据")
            }
            else -> Unit
        }
        if (response.code != 0) {
            throw SearchException(
                SearchError.Unknown("code=${response.code} ${response.message}"),
                "${what}加载失败：code=${response.code} ${response.message}",
            )
        }
        riskCooldown.onSuccess()
        return response.data
    }

    /** best-effort 请求用：不抛错，但 -352 照样记入冷却。 */
    private fun noteRiskCode(code: Int) {
        if (resolveRetryAction(code) == RetryAction.BACKOFF_NO_RETRY) riskCooldown.onRiskControl()
    }

    /** 读路径冷却闸门：处于 -352 冷却期则快速失败，不硬顶。 */
    private fun ensureNotCoolingRead() {
        if (riskCooldown.remaining() > 0) {
            throw SearchException(SearchError.Blocked, "触发风控冷却中，请稍后再试")
        }
    }

    /**
     * -101：登录态可能刚过期 → 续期一次，续上了把这个请求重打一次（[runWithLoginRefresh]，绝不循环）。
     * **包在限速闸门外面**：重打同样排队、吃最小间隔；刷新期间也不占着该桶的锁。
     */
    private suspend fun <T> refreshingOn101(fire: suspend () -> BiliResponse<T>): BiliResponse<T> =
        runWithLoginRefresh(
            currentSession = { sessionStore.current },
            refresh = { rejected -> cookieRefresher.refreshAfterRejection(rejected) },
            logOut = onSessionExpired,
            coolDown = { riskCooldown.onRiskControl() },
            fire = fire,
        )

    /**
     * 账号页读请求（收藏夹、合集、动态、关注、稍后再看）的统一出口，风控分流与消息中心读路径同口径
     * （见 [checkMessageRead]）：冷却期内本地快速失败，-352 记入全局冷却、**绝不重试**，-412 重置设备凭据，
     * -101 续期后重打一次（[refreshingOn101]）。
     *
     * 这些页面都能下拉刷新。早期它们各自 `require(code == 0)`：撞了 -352 不进冷却，
     * 用户每拉一次就真打一次服务端——正是《架构设计篇》§3.2 说的「越被拦越猛打」。
     */
    private suspend fun <T> accountRead(what: String, request: suspend () -> BiliResponse<T>): T? {
        authGate.awaitReady()
        ensureNotCoolingRead()
        return checkMessageRead(refreshingOn101(request), what)
    }

    // ── AI 总结：字幕与章节（工单 AI-4）─────────────────────

    /**
     * 字幕列表 + UP 主章节（`x/player/wbi/v2`）。
     *
     * **只在用户点「AI 总结」、打开风格弹窗那一刻查一次**，绝不在打开播放页时预取。
     * 风控分流与其他读接口一致：冷却期内快速失败，-352 记入冷却**不重试**，-412 重置设备凭据。
     */
    suspend fun playerSubtitles(bvid: String, cid: Long): Result<PlayerV2Data> = runCatching {
        val credentials = authGate.awaitReady()
        val signed = WbiSigner.sign(
            params = mapOf("bvid" to bvid, "cid" to cid.toString()),
            imgKey = credentials.wbiImgKey,
            subKey = credentials.wbiSubKey,
        )
        ensureNotCoolingRead()
        val response = refreshingOn101 {
            requestThrottle.gate(ThrottleBucket.SUMMARY, SUMMARY_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) {
                api.playerV2(signed)
            }
        }
        checkMessageRead(response, "字幕列表") ?: PlayerV2Data()
    }

    /** 字幕文件本体。[url] 来自 [com.biliseek.core.network.dto.SubtitleTrack.subtitleUrl]，只接受 B 站字幕域名。 */
    suspend fun subtitleBody(url: String): Result<SubtitleBody> = runCatching {
        val full = normalizeSubtitleUrl(url) ?: throw IllegalArgumentException("字幕地址不在 B 站域内")
        ensureNotCoolingRead()
        requestThrottle.gate(ThrottleBucket.SUMMARY, SUMMARY_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) {
            api.subtitleBody(full)
        }
    }

    /**
     * B 站官方 AI 总结（工单 AI-4b）。返回外层 code 与 data，由调用方按状态表判断（-101 不当异常）。
     *
     * 与 BiliPai 不同，这里**刻意收紧**：只在用户点「AI 总结」时调用一次；排队中不轮询；
     * 风控码（-352 / -412）与 HTTP 412 都只请求一次、不重试，并照常记入冷却 / 重置凭据。
     * 未登录时调用方应直接跳过，不发请求。
     */
    suspend fun viewConclusion(bvid: String, cid: Long, upMid: Long?): Result<ConclusionResult> = runCatching {
        val credentials = authGate.awaitReady()
        val params = buildMap {
            put("bvid", bvid)
            put("cid", cid.toString())
            if (upMid != null && upMid > 0) put("up_mid", upMid.toString())
        }
        val signed = WbiSigner.sign(params, credentials.wbiImgKey, credentials.wbiSubKey)
        ensureNotCoolingRead()
        val response = try {
            refreshingOn101 {
                requestThrottle.gate(ThrottleBucket.SUMMARY, SUMMARY_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) {
                    api.viewConclusion(signed)
                }
            }
        } catch (e: retrofit2.HttpException) {
            // HTTP 412 同样是风控拦截：进冷却，不重试
            if (e.code() == 412) riskCooldown.onRiskControl()
            throw e
        }
        when (resolveRetryAction(response.code)) {
            RetryAction.BACKOFF_NO_RETRY -> {
                riskCooldown.onRiskControl()
                throw SearchException(SearchError.Blocked, "触发风控（-352），已进入退避")
            }
            RetryAction.RESEED_CREDENTIALS -> {
                authGate.invalidate()
                throw SearchException(SearchError.CredentialsInvalid, "请求被拦截（-412），已重置设备凭据")
            }
            else -> Unit
        }
        if (response.code == 0) riskCooldown.onSuccess()
        ConclusionResult(rootCode = response.code, data = response.data)
    }

    // ── 弹幕 ────────────────────────────────────────────

    /**
     * 一段弹幕（6 分钟一段，[segmentIndex] 从 1 起）。
     *
     * 只在「全屏 + 弹幕开 + 看模式」时由播放页按进度逐段拉取，纯听永远不请求。
     * 错误分流与其他读接口一致：-352 进冷却、**不重试**；-412 重置凭据。
     */
    suspend fun danmakuSegment(cid: Long, segmentIndex: Int): Result<List<DanmakuItem>> = runCatching {
        val credentials = try {
            authGate.awaitReady()
        } catch (e: Exception) {
            throw SearchException(SearchError.Network(e.message.orEmpty()), e.message ?: "风控凭据不可用")
        }
        val signed = WbiSigner.sign(
            params = mapOf(
                "type" to "1",
                "oid" to cid.toString(),
                "segment_index" to segmentIndex.toString(),
                "web_location" to "1315873",
            ),
            imgKey = credentials.wbiImgKey,
            subKey = credentials.wbiSubKey,
        )
        ensureNotCoolingRead()
        val response = try {
            requestThrottle.gate(ThrottleBucket.DANMAKU, DANMAKU_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) {
                api.danmakuSegment(signed)
            }
        } catch (e: IOException) {
            throw SearchException(SearchError.Network(e.message.orEmpty()), e.message ?: "网络不可达")
        }
        if (!response.isSuccessful) {
            throw SearchException(
                SearchError.Unknown("http=${response.code()}"),
                "弹幕加载失败：HTTP ${response.code()}",
            )
        }
        val bytes = response.body()?.use { it.bytes() } ?: ByteArray(0)
        DanmakuProto.jsonErrorCode(bytes)?.let { code ->
            when (resolveRetryAction(code)) {
                RetryAction.BACKOFF_NO_RETRY -> {
                    riskCooldown.onRiskControl()
                    throw SearchException(SearchError.Blocked, "触发风控（-352），已进入退避")
                }
                RetryAction.RESEED_CREDENTIALS -> {
                    authGate.invalidate()
                    throw SearchException(SearchError.CredentialsInvalid, "请求被拦截（-412），已重置设备凭据")
                }
                else -> Unit
            }
            // code=0 的 JSON 表示这一段没有弹幕
            if (code != 0) {
                throw SearchException(SearchError.Unknown("code=$code"), "弹幕加载失败：code=$code")
            }
            riskCooldown.onSuccess()
            return@runCatching emptyList()
        }
        riskCooldown.onSuccess()
        DanmakuProto.parseSegment(bytes)
    }

    // ── 评论读（B5）────────────────────────────────────────

    /** 评论列表的一页。 */
    data class CommentPage(val items: List<ReplyItem>, val count: Int)

    /**
     * 评论列表。先走 WBI 主链路，遇 -111/-412/-403 降级兼容链路一次（[runCommentRead]）；-352 不降级，直接进冷却。
     * `oid`/`type`：视频=用 aid + type=1；动态=按 [com.biliseek.core.network.internal.resolveDynamicCommentTargetFromEntry] 算出的 oid + type(11/17)。
     * `sort`：0 按时间 / 1 按热度。写侧（发评论/点赞）走 [addDynamicReply]/[replyAction]。
     */
    suspend fun comments(oid: Long, type: Int, page: Int = 1, sort: Int = 1): Result<CommentPage> = runCatching {
        val credentials = try {
            authGate.awaitReady()
        } catch (e: Exception) {
            throw SearchException(SearchError.Network(e.message.orEmpty()), e.message ?: "风控凭据不可用")
        }
        val baseParams = mapOf(
            "oid" to oid.toString(),
            "type" to type.toString(),
            "pn" to page.toString(),
            "sort" to sort.toString(),
        )
        ensureNotCoolingRead()
        val response = try {
            refreshingOn101 {
                requestThrottle.gate(ThrottleBucket.FEED, FEED_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) {
                    runCommentRead(
                        primary = {
                            api.replyMainWbi(
                                WbiSigner.sign(baseParams, credentials.wbiImgKey, credentials.wbiSubKey),
                            )
                        },
                        compat = { api.replyMain(baseParams) },
                    )
                }
            }
        } catch (e: IOException) {
            throw SearchException(SearchError.Network(e.message.orEmpty()), e.message ?: "网络不可达")
        }
        when (resolveRetryAction(response.code)) {
            RetryAction.BACKOFF_NO_RETRY -> {
                riskCooldown.onRiskControl()
                throw SearchException(SearchError.Blocked, "触发风控（-352），已进入退避")
            }
            RetryAction.RESEED_CREDENTIALS -> {
                authGate.invalidate()
                throw SearchException(SearchError.CredentialsInvalid, "请求被拦截（-412），已重置设备凭据")
            }
            else -> Unit
        }
        if (response.code != 0) {
            throw SearchException(
                SearchError.Unknown("code=${response.code} ${response.message}"),
                "评论加载失败：code=${response.code} ${response.message}",
            )
        }
        riskCooldown.onSuccess()
        val data = response.data
        CommentPage(items = data?.replies.orEmpty(), count = data?.page?.count ?: 0)
    }

    /**
     * 楼中楼（`x/v2/reply/reply`，不需要 WBI 签名）。`root` 是根评论 rpid，`page` 从 1 起。
     * 错误分流 / 风控退避与 [comments] 一致，但只有单一链路，没有 WBI/兼容双轨。
     */
    suspend fun subReplies(oid: Long, type: Int, root: Long, page: Int = 1): Result<CommentPage> = runCatching {
        try {
            authGate.awaitReady()
        } catch (e: Exception) {
            throw SearchException(SearchError.Network(e.message.orEmpty()), e.message ?: "风控凭据不可用")
        }
        val params = mapOf(
            "oid" to oid.toString(),
            "type" to type.toString(),
            "root" to root.toString(),
            "pn" to page.toString(),
            "ps" to "10",
        )
        ensureNotCoolingRead()
        val response = try {
            refreshingOn101 {
                requestThrottle.gate(ThrottleBucket.FEED, FEED_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) {
                    api.replyReply(params)
                }
            }
        } catch (e: IOException) {
            throw SearchException(SearchError.Network(e.message.orEmpty()), e.message ?: "网络不可达")
        }
        when (resolveRetryAction(response.code)) {
            RetryAction.BACKOFF_NO_RETRY -> {
                riskCooldown.onRiskControl()
                throw SearchException(SearchError.Blocked, "触发风控（-352），已进入退避")
            }
            RetryAction.RESEED_CREDENTIALS -> {
                authGate.invalidate()
                throw SearchException(SearchError.CredentialsInvalid, "请求被拦截（-412），已重置设备凭据")
            }
            else -> Unit
        }
        if (response.code != 0) {
            throw SearchException(
                SearchError.Unknown("code=${response.code} ${response.message}"),
                "楼中楼加载失败：code=${response.code} ${response.message}",
            )
        }
        riskCooldown.onSuccess()
        val data = response.data
        CommentPage(items = data?.replies.orEmpty(), count = data?.page?.count ?: 0)
    }

    /** 点赞 / 取消点赞。 */
    suspend fun likeVideo(bvid: String, like: Boolean): Result<Unit> =
        writeRequest(
            payload = mapOf("bvid" to bvid, "like" to if (like) "1" else "2"),
            idempotentCodes = IdempotentCodes.LIKE,
            referer = "https://www.bilibili.com/video/$bvid",
        ) { f, r -> api.likeVideo(f, r) }.map { }

    /** 投币（1 或 2 枚，alsoLike=同时点赞）。 */
    suspend fun addCoin(bvid: String, count: Int, alsoLike: Boolean): Result<Unit> =
        writeRequest(
            payload = mapOf(
                "bvid" to bvid,
                "multiply" to count.coerceIn(1, 2).toString(),
                "select_like" to if (alsoLike) "1" else "0",
            ),
            idempotentCodes = IdempotentCodes.COIN,
            referer = "https://www.bilibili.com/video/$bvid",
        ) { f, r -> api.addCoin(f, r) }.map { }

    /** 收藏：加入 addIds、移出 delIds（deal 一次搞定）。rid 用 aid。 */
    suspend fun dealFavorite(aid: Long, addIds: List<Long>, delIds: List<Long>): Result<Unit> =
        writeRequest(
            payload = mapOf(
                "rid" to aid.toString(),
                "type" to "2",
                "add_media_ids" to addIds.joinToString(","),
                "del_media_ids" to delIds.joinToString(","),
            ),
            idempotentCodes = IdempotentCodes.FAV,
        ) { f, _ -> api.dealFavorite(f) }.map { }

    /** 新建收藏夹，返回新建收藏夹的 media_id。 */
    suspend fun addFavoriteFolder(
        title: String,
        intro: String = "",
        privacy: Int = 0,
    ): Result<Long> = writeRequest(
        payload = buildMap {
            put("title", title)
            if (intro.isNotBlank()) put("intro", intro)
            put("privacy", privacy.toString())
        },
    ) { f, _ -> api.addFavoriteFolder(f) }
        .map { it?.id ?: 0L }

    /** 修改收藏夹标题和简介。 */
    suspend fun editFavoriteFolder(
        mediaId: Long,
        title: String,
        intro: String = "",
        privacy: Int = 0,
    ): Result<Unit> = writeRequest(
        payload = buildMap {
            put("media_id", mediaId.toString())
            put("title", title)
            put("intro", intro)
            put("privacy", privacy.toString())
        },
    ) { f, _ -> api.editFavoriteFolder(f) }
        .map { }

    /** 删除收藏夹。收藏夹内的视频不会被删除。 */
    suspend fun deleteFavoriteFolder(mediaIds: List<Long>): Result<Unit> =
        writeRequest(
            payload = mapOf("media_ids" to mediaIds.joinToString(",")),
        ) { f, _ -> api.deleteFavoriteFolder(f) }
            .map { }

    /** 取消订阅合集（「列表」页合集分区里的一项）。 */
    suspend fun unfavSeason(seasonId: Long): Result<Unit> =
        writeRequest(
            payload = mapOf("platform" to "web", "season_id" to seasonId.toString()),
        ) { f, _ -> api.unfavSeason(f) }
            .map { }

    /** 一键三连。 */
    suspend fun tripleAction(bvid: String): Result<Unit> =
        writeRequest(
            payload = mapOf("bvid" to bvid),
            referer = "https://www.bilibili.com/video/$bvid",
        ) { f, r -> api.tripleAction(f, r) }.map { }

    /** 关注 / 取关。act: 1 关注 / 2 取关。 */
    suspend fun modifyRelation(mid: Long, follow: Boolean): Result<Unit> =
        writeRequest(
            payload = mapOf("fid" to mid.toString(), "act" to if (follow) "1" else "2", "re_src" to "11"),
            idempotentCodes = IdempotentCodes.FOLLOW,
        ) { f, _ -> api.modifyRelation(f) }.map { }

    /** 稍后再看：加入。 */
    suspend fun addToView(aid: Long): Result<Unit> =
        writeRequest(payload = mapOf("aid" to aid.toString())) { f, _ -> api.addToView(f) }.map { }

    /** 稍后再看：删除单条 / 清已看（viewed=true）。 */
    suspend fun delToView(aid: Long?): Result<Unit> =
        writeRequest(
            payload = if (aid != null) mapOf("aid" to aid.toString()) else mapOf("viewed" to "true"),
        ) { f, _ -> api.delToView(f) }.map { }

    /** 发评论，返回 rpid。oid 用 aid，一级评论 root/parent 省略。 */
    suspend fun addReply(aid: Long, message: String, root: Long? = null, parent: Long? = null): Result<Long> =
        writeRequest(
            payload = buildMap {
                put("oid", aid.toString()); put("type", "1")
                put("message", message); put("plat", "1")
                root?.let { put("root", it.toString()) }
                parent?.let { put("parent", it.toString()) }
            },
        ) { f, _ -> api.addReply(f) }.map { it?.rpid ?: 0L }

    /** 评论点赞 / 取消。`oid`/`type` 语义同 [comments]。 */
    suspend fun replyAction(oid: Long, type: Int, rpid: Long, like: Boolean): Result<Unit> =
        writeRequest(
            payload = mapOf(
                "oid" to oid.toString(), "type" to type.toString(),
                "rpid" to rpid.toString(), "action" to if (like) "1" else "0",
            ),
        ) { f, _ -> api.replyAction(f) }.map { }

    /** 发弹幕。oid 用 cid，progress 单位毫秒。 */
    suspend fun sendDanmaku(cid: Long, aid: Long, msg: String, progressMs: Long): Result<Unit> =
        writeRequest(
            payload = mapOf(
                "oid" to cid.toString(), "aid" to aid.toString(), "type" to "1",
                "msg" to msg, "progress" to progressMs.toString(),
                "color" to "16777215", "fontsize" to "25", "mode" to "1", "pool" to "0", "plat" to "1",
            ),
        ) { f, _ -> api.sendDanmaku(f) }.map { }

    /** 弹幕点赞 / 取消。op: 1 赞 / 2 取消。 */
    suspend fun likeDanmaku(cid: Long, dmid: Long, like: Boolean): Result<Unit> =
        writeRequest(
            payload = mapOf("oid" to cid.toString(), "dmid" to dmid.toString(), "op" to if (like) "1" else "2"),
        ) { f, _ -> api.likeDanmaku(f) }.map { }

    // ── 举报 / 转发（B6）──────────────────────────────────────

    /** 举报弹幕。reason 见 B 站举报理由枚举。 */
    suspend fun reportDanmaku(cid: Long, dmid: Long, reason: Int, content: String? = null): Result<Unit> =
        writeRequest(
            payload = buildMap {
                put("cid", cid.toString()); put("dmid", dmid.toString()); put("reason", reason.toString())
                content?.let { put("content", it) }
            },
            idempotentCodes = IdempotentCodes.REPORT,
        ) { f, _ -> api.reportDanmaku(f) }.map { }

    /** 举报评论。oid 用 aid。 */
    suspend fun reportReply(aid: Long, rpid: Long, reason: Int, content: String? = null): Result<Unit> =
        writeRequest(
            payload = buildMap {
                put("oid", aid.toString()); put("type", "1"); put("rpid", rpid.toString()); put("reason", reason.toString())
                content?.let { put("content", it) }
            },
            idempotentCodes = IdempotentCodes.REPORT,
        ) { f, _ -> api.reportReply(f) }.map { }

    /**
     * 点赞 / 取消点赞一条动态。`up=1` 点赞、`up=2` 取消（已真机验证）。
     * 该接口要求 JSON body + `csrf` 走 query，`block` 从统一注入 csrf 后的 `fields` 里
     * 把 csrf 取出来传参数、其余字段拼成请求体——`writeRequest` 的 csrf 注入机制不受影响。
     */
    suspend fun likeDynamic(dynIdStr: String, like: Boolean): Result<Unit> =
        writeRequest(
            payload = mapOf(
                "dyn_id_str" to dynIdStr,
                "up" to if (like) "1" else "2",
            ),
            referer = "https://t.bilibili.com/",
        ) { f, _ ->
            api.likeDynamic(
                csrf = f.getValue("csrf"),
                body = DynThumbRequest(dynIdStr = f.getValue("dyn_id_str"), up = f.getValue("up").toInt()),
            )
        }.map { }

    /**
     * 动态评论 / 回复。`type` 与 `oid` 由调用方按 [resolveDynamicCommentTarget] 决定
     * ——DRAW=11+drawId，其他=17+id_str。`root`/`parent` 为 0 表示发表新的一级评论；
     * 非 0 表示回复某条评论或楼中楼（语义同 [addReply]）。
     */
    suspend fun addDynamicReply(
        oid: Long,
        type: Int,
        message: String,
        root: Long = 0,
        parent: Long = 0,
    ): Result<Long> =
        writeRequest(
            payload = buildMap {
                put("oid", oid.toString())
                put("type", type.toString())
                put("message", message)
                put("plat", "1")
                if (root > 0) put("root", root.toString())
                if (parent > 0) put("parent", parent.toString())
            },
            referer = "https://t.bilibili.com/",
        ) { f, _ -> api.addReply(f) }.map { it?.rpid ?: 0L }

    /**
     * 拉取 `fid` 的关注状态。仅登录后有效；未登录会 -101，由调用方按现有错误分支处理。
     * 走 [accountRead]：-352 进冷却、-412 重置凭据、-101 续期后重打一次。
     */
    suspend fun relation(fid: Long): Result<RelationData> = runCatching {
        accountRead("关注状态") { api.relation(fid) } ?: RelationData()
    }

    // ── 视频互动状态 / 表情面板（UI 重构）───────────────────

    /**
     * 当前账号对视频的互动状态（已赞 / 已投币数 / 已收藏）。必须登录；未登录直接失败，
     * 由调用方回落为「全部未激活」。错误分流同其他读接口（-352 进冷却不重试）。
     */
    suspend fun archiveRelation(bvid: String): Result<com.biliseek.core.network.dto.ArchiveRelation> = runCatching {
        requireLoggedInMid()
        ensureNotCoolingRead()
        authGate.awaitReady()
        checkMessageRead(refreshingOn101 { api.archiveRelation(bvid) }, "互动状态")
            ?: com.biliseek.core.network.dto.ArchiveRelation()
    }

    /** 表情面板进程内缓存：表情包几乎不变，一次进程只拉一次。 */
    @Volatile
    private var emotePanelCache: List<com.biliseek.core.network.dto.EmotePackage>? = null

    /**
     * 评论表情面板。未登录时服务端只给默认表情包，同样可用。
     * 成功结果缓存到进程结束；失败不缓存（下次打开面板再试）。
     */
    suspend fun emotePanel(): Result<List<com.biliseek.core.network.dto.EmotePackage>> = runCatching {
        emotePanelCache?.let { return@runCatching it }
        ensureNotCoolingRead()
        authGate.awaitReady()
        val packages = checkMessageRead(refreshingOn101 { api.emotePanel() }, "表情面板")?.packages.orEmpty()
            .filter { !it.emote.isNullOrEmpty() }
        emotePanelCache = packages
        packages
    }

    // ── 消息中心（私信 + 互动消息）──────────────────────────

    /** 私信会话列表。消息中心页面主体，懒加载——独立于三个子页面。 */
    suspend fun privateSessions(): Result<com.biliseek.core.network.dto.SessionListData> = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        ensureNotCoolingRead()
        val response = refreshingOn101 {
            requestThrottle.gate(
                ThrottleBucket.MESSAGE,
                MESSAGE_MIN_INTERVAL_MS,
                THROTTLE_JITTER_MS,
            ) { api.getSessions() }
        }
        val data = checkMessageRead(response, "私信会话") ?: com.biliseek.core.network.dto.SessionListData()
        data.copy(sessionList = fillSessionPeers(data.sessionList))
    }

    /**
     * 给缺 `account_info` 的会话补昵称/头像（`get_sessions` 只给官方号带）。
     * best-effort：查询失败就原样返回，列表照常显示（UI 退回「用户{uid}」）。
     */
    private suspend fun fillSessionPeers(
        sessions: List<com.biliseek.core.network.dto.Session>,
    ): List<com.biliseek.core.network.dto.Session> {
        val missing = sessions.asSequence()
            .filter { it.accountInfo?.name.isNullOrBlank() && it.talkerId > 0 }
            .map { it.talkerId }
            .distinct()
            .toList()
        if (missing.isEmpty()) return sessions
        val cards = runCatching {
            requestThrottle.gate(
                ThrottleBucket.MESSAGE,
                MESSAGE_MIN_INTERVAL_MS,
                THROTTLE_JITTER_MS,
            ) { api.getUserCards(missing.joinToString(",")) }
                .also { noteRiskCode(it.code) }
                .takeIf { it.code == 0 }
                ?.data.orEmpty()
                .associateBy { it.mid }
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyMap()
        }
        return mergeSessionPeers(sessions, cards)
    }

    /** 回复与@ 列表。「回复与@」子页面专用，懒加载——只有用户点开这个子页面才请求。 */
    suspend fun replyFeed(): Result<com.biliseek.core.network.dto.ReplyFeedData> = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        ensureNotCoolingRead()
        val response = refreshingOn101 {
            requestThrottle.gate(
                ThrottleBucket.MESSAGE,
                MESSAGE_MIN_INTERVAL_MS,
                THROTTLE_JITTER_MS,
            ) { api.getMsgfeedReply() }
        }
        checkMessageRead(response, "回复与@") ?: com.biliseek.core.network.dto.ReplyFeedData()
    }

    /** @我的 列表。与回复合并展示在「回复与@」子页面，懒加载。 */
    suspend fun atFeed(): Result<com.biliseek.core.network.dto.AtFeedData> = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        ensureNotCoolingRead()
        val response = refreshingOn101 {
            requestThrottle.gate(
                ThrottleBucket.MESSAGE,
                MESSAGE_MIN_INTERVAL_MS,
                THROTTLE_JITTER_MS,
            ) { api.getMsgfeedAt() }
        }
        checkMessageRead(response, "@我的") ?: com.biliseek.core.network.dto.AtFeedData()
    }

    /** 收到喜欢 列表。「收到喜欢」子页面专用，懒加载。 */
    suspend fun likeFeed(): Result<com.biliseek.core.network.dto.LikeFeedData> = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        ensureNotCoolingRead()
        val response = refreshingOn101 {
            requestThrottle.gate(
                ThrottleBucket.MESSAGE,
                MESSAGE_MIN_INTERVAL_MS,
                THROTTLE_JITTER_MS,
            ) { api.getMsgfeedLike() }
        }
        checkMessageRead(response, "收到喜欢") ?: com.biliseek.core.network.dto.LikeFeedData()
    }

    /** 新增粉丝 列表（`x/relation/followers`）。「新增粉丝」子页面专用，懒加载。 */
    suspend fun followerFeed(): Result<com.biliseek.core.network.dto.FollowersData> = runCatching {
        val mid = requireLoggedInMid()
        authGate.awaitReady()
        ensureNotCoolingRead()
        val response = refreshingOn101 {
            requestThrottle.gate(
                ThrottleBucket.MESSAGE,
                MESSAGE_MIN_INTERVAL_MS,
                THROTTLE_JITTER_MS,
            ) { api.getFollowers(vmid = mid) }
        }
        checkMessageRead(response, "新增粉丝") ?: com.biliseek.core.network.dto.FollowersData()
    }

    /** 私信历史消息分页加载。`beginSeqno=0` 拉首屏。 */
    suspend fun loadMoreSessionMessages(
        talkerId: Long,
        beginSeqno: Long,
    ): Result<com.biliseek.core.network.dto.MessageHistoryData> = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        ensureNotCoolingRead()
        val response = refreshingOn101 {
            requestThrottle.gate(
                ThrottleBucket.MESSAGE,
                MESSAGE_MIN_INTERVAL_MS,
                THROTTLE_JITTER_MS,
            ) { api.fetchSessionMsgs(talkerId = talkerId, beginSeqno = beginSeqno) }
        }
        checkMessageRead(response, "私信消息") ?: throw IllegalStateException("私信消息 data 为空")
    }

    /** 发送文本私信。`devId` 取自 `BiliCredentials.bNut`（缺失时 fallback UUID）。 */
    suspend fun sendPrivateMessage(
        receiverId: Long,
        content: String,
        devId: String,
    ): Result<Long> = runCatching {
        requireLoggedInMid()
        val session = sessionStore.current
        val senderUid = session.dedeUserId.toLongOrNull()
            ?: throw IllegalStateException("缺 sender_uid")
        val timestampSec = System.currentTimeMillis() / 1000L
        // `msg[content]` 是 JSON 字符串，B 站 Web 客户端格式
        val contentJson = """{"content":${escapeJsonString(content)}}"""
        val response = writeRequest(
            payload = mapOf(
                "msg[sender_uid]" to senderUid.toString(),
                "msg[receiver_id]" to receiverId.toString(),
                "msg[receiver_type]" to "1",
                "msg[msg_type]" to "1",
                "msg[content]" to contentJson,
                "msg[timestamp]" to timestampSec.toString(),
                "msg[dev_id]" to devId,
                "msg[new_face_version]" to "1",
                "csrf_token" to session.biliJct,
            ),
        ) { f, _ ->
            api.postSendMsg(
                senderUid = f.getValue("msg[sender_uid]").toLong(),
                receiverId = f.getValue("msg[receiver_id]").toLong(),
                receiverType = f.getValue("msg[receiver_type]").toInt(),
                msgType = f.getValue("msg[msg_type]").toInt(),
                content = f.getValue("msg[content]"),
                timestamp = f.getValue("msg[timestamp]").toLong(),
                devId = f.getValue("msg[dev_id]"),
                newFaceVersion = f.getValue("msg[new_face_version]").toInt(),
                csrf = f.getValue("csrf"),
                csrfToken = f.getValue("csrf_token"),
            )
        }.getOrThrow()
        response?.msgSeqno ?: 0L
    }

    /** 标记会话已读到 `ackSeqno`。 */
    suspend fun markSessionRead(talkerId: Long, ackSeqno: Long): Result<Unit> = runCatching {
        requireLoggedInMid()
        writeRequest(
            payload = mapOf(
                "talker_id" to talkerId.toString(),
                "session_type" to "1",
                "ack_seqno" to ackSeqno.toString(),
                "csrf_token" to sessionStore.current.biliJct,
            ),
        ) { f, _ ->
            api.postUpdateAck(
                talkerId = f.getValue("talker_id").toLong(),
                sessionType = f.getValue("session_type").toInt(),
                ackSeqno = f.getValue("ack_seqno").toLong(),
                csrf = f.getValue("csrf"),
                csrfToken = f.getValue("csrf_token"),
            )
        }.getOrThrow()
    }

    /**
     * 站内回复一条评论（`x/v2/reply/add`）。`root=0/parent=0` 表示发一级评论；
     * 楼中楼用 `root=一级评论 rpid, parent=被回复评论 rpid`。
     * `oid` 用 aid、`type=1`（视频评论）。
     */
    suspend fun postCommentReply(
        aid: Long,
        root: Long,
        parent: Long,
        content: String,
    ): Result<Long> = runCatching {
        requireLoggedInMid()
        writeRequest(
            payload = buildMap {
                put("oid", aid.toString())
                put("type", "1")
                put("root", root.toString())
                put("parent", parent.toString())
                put("message", content)
                put("plat", "1")
            },
        ) { f, _ -> api.addReply(f) }
            .getOrThrow()
            ?.rpid ?: 0L
    }

    /**
     * 入口未读角标聚合：互动未读（回复/@/赞）+ 私信未读。
     * `api.bilibili.com x/msgfeed/unread` + `api.vc.bilibili.com session_svr/single_unread`。
     * 任一路失败返回 null（见 [resolveUnreadSummary]），调用方保留旧角标；UI 不报错（角标是 best-effort）。
     */
    suspend fun messageUnread(): MessageUnreadSummary? = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        ensureNotCoolingRead()
        // 角标是 best-effort：失败不抛，但 -352 仍要记入冷却，否则后台轮询会继续顶着风控打。
        val msgfeed = runCatching { api.getMsgfeedUnread().also { noteRiskCode(it.code) } }.getOrNull()
        val session = runCatching { api.getSessionUnread().also { noteRiskCode(it.code) } }.getOrNull()
        resolveUnreadSummary(msgfeed, session)
    }.getOrNull()

    /**
     * 入口未读角标的结构化聚合。`total` 喂动态页铃铛；`replyAtBadge`/`like`
     * 分别喂消息中心「回复与@」「收到喜欢」两个图标角标（「新增粉丝」没有未读概念，不带角标）。
     */
    data class MessageUnreadSummary(
        val reply: Int = 0,
        val at: Int = 0,
        val like: Int = 0,
        val sessionUnread: Int = 0,
    ) {
        val total: Int get() = reply + at + like + sessionUnread
        val replyAtBadge: Int get() = reply + at
    }

    private companion object {
        // B3 限速最小间隔（ms）+ 抖动。保守初值，真机按体感/效果校准（工单 §5.3）。
        const val SEARCH_MIN_INTERVAL_MS = 800L
        const val FEED_MIN_INTERVAL_MS = 600L
        /** 榜单 / 活动页 / 视频播客：连点几个入口时 600ms 偏密（10-21 真机 -352），放到 1 秒。 */
        const val CHART_MIN_INTERVAL_MS = 1_000L
        const val DANMAKU_MIN_INTERVAL_MS = 1_000L
        const val SUMMARY_MIN_INTERVAL_MS = 800L
        const val WRITE_MIN_INTERVAL_MS = 1_000L
        /** 消息中心限速：B 站私信接口对连发敏感，按 800ms/req 起步。 */
        const val MESSAGE_MIN_INTERVAL_MS = 800L
        const val THROTTLE_JITTER_MS = 300L

        /** 热门播客：运营搭建的 H5 活动页（App 热门页的「热门播客」入口分享出来的页面）。 */
        const val PODCAST_ERA_URL = "https://www.bilibili.com/blackboard/era/fuwAmTrtqFLexuSx.html"

        /** 批量取视频卡片时一次最多带的 BV 数（一期最多 21 个，留余量）。 */
        const val MAX_CARD_IDS = 30

        /** 分区列表每页条数（`newlist_rank` 的 `pagesize`）。 */
        const val REGION_PAGE_SIZE = 20
    }
}

/** 把字符串里的 `"` 与换行转义为 JSON 安全形式。文件级（顶层）函数 —— 比 private member
 *  更容易在单测里通过 reflection 调到。 */
internal fun escapeJsonString(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append("\"")
    return sb.toString()
}
