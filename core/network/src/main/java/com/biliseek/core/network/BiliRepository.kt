package com.biliseek.core.network

import android.util.Log
import com.biliseek.core.network.dto.PageItem
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.dto.FavoriteFolderItem
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
import com.biliseek.core.network.internal.IdempotentCodes
import com.biliseek.core.network.internal.runCommentRead
import com.biliseek.core.network.internal.RequestThrottle
import com.biliseek.core.network.internal.RetryAction
import com.biliseek.core.network.internal.RiskCooldown
import com.biliseek.core.network.internal.ThrottleBucket
import com.biliseek.core.network.internal.resolveRetryAction
import com.biliseek.core.network.internal.runWriteRequest
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
    private val cookieRefresher: CookieRefresher,   // -111 后刷新重试
    private val riskCooldown: RiskCooldown = RiskCooldown(),          // B3：撞 -352 后全局冷却
    private val requestThrottle: RequestThrottle = RequestThrottle(), // B3：连发限速降噪
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
            requestThrottle.gate(ThrottleBucket.SEARCH, SEARCH_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) {
                api.searchType(params)
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

    // ── 收藏夹（M11）──────────────────────────────────────

    /**
     * 收藏夹目录。**必须登录**（list-all 需要 up_mid 与登录态 Cookie）。
     * 未登录直接返回失败，由 UI 显示登录引导。
     */
    suspend fun favoriteFolders(): Result<List<FavoriteFolderItem>> = runCatching {
        val mid = requireLoggedInMid()
        authGate.awaitReady()
        val response = api.favoriteFolders(upMid = mid)
        require(response.code == 0) {
            "收藏夹目录失败：code=${response.code} ${response.message}"
        }
        response.data?.list.orEmpty().filter { it.id > 0 && it.title.isNotBlank() }
    }

    /** 收藏夹内容（分页，`ps ≤ 20`）。必须登录。 */
    suspend fun favoriteResources(
        mediaId: Long,
        page: Int,
        pageSize: Int = 20,
    ): Result<FavoriteResourcePage> = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        val response = api.favoriteResources(mediaId, pn = page, ps = pageSize)
        require(response.code == 0) {
            "收藏夹内容失败：code=${response.code} ${response.message}"
        }
        val data = response.data ?: throw IllegalStateException("收藏夹内容为空")
        FavoriteResourcePage(
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
        authGate.awaitReady()
        val response = api.favoriteResources(mediaId, pn = 1, ps = 20)
        require(response.code == 0) {
            "收藏夹内容失败：code=${response.code} ${response.message}"
        }
        response.data?.medias?.firstOrNull()?.cover?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ── 合集（M13-N）──────────────────────────────────

    /** 合集列表。必须登录。 */
    suspend fun seriesList(mid: Long): Result<List<SeriesItem>> = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        val response = api.seriesList(mid)
        require(response.code == 0) {
            "合集列表失败：code=${response.code} ${response.message}"
        }
        response.data?.list.orEmpty().filter { it.id > 0 && it.title.isNotBlank() }
    }

    /** 订阅合集列表（分页）。必须登录。 */
    suspend fun collectedList(
        mid: Long,
        page: Int,
        pageSize: Int = 20,
    ): Result<CollectedListPage> = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        val response = api.collectedList(upMid = mid, pn = page, ps = pageSize)
        require(response.code == 0) {
            "订阅合集列表失败：code=${response.code} ${response.message}"
        }
        val data = response.data ?: throw IllegalStateException("订阅合集列表为空")
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
        authGate.awaitReady()
        val response = api.seriesArchives(seriesId, pn = page, ps = pageSize)
        require(response.code == 0) {
            "合集内容失败：code=${response.code} ${response.message}"
        }
        val data = response.data ?: throw IllegalStateException("合集内容为空")
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
        authGate.awaitReady()
        val response = api.collectionSeasonList(seasonId = seasonId, pn = page, ps = pageSize)
        require(response.code == 0) {
            "追更合集内容失败：code=${response.code} ${response.message}"
        }
        val data = response.data ?: throw IllegalStateException("追更合集内容为空")
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
        authGate.awaitReady()
        val response = api.dynamicFeed(type = type, offset = offset)
        require(response.code == 0) {
            "动态流失败：code=${response.code} ${response.message}"
        }
        response.data.toDynamicPage(offset)
    }

    /** 单个 UP 的空间动态（UP 筛选）。必须登录。 */
    suspend fun dynamicSpace(
        hostMid: Long,
        offset: String = "",
    ): Result<DynamicPage> = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        val response = api.dynamicSpace(hostMid = hostMid, offset = offset)
        require(response.code == 0) {
            "UP 动态失败：code=${response.code} ${response.message}"
        }
        response.data.toDynamicPage(offset)
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
        authGate.awaitReady()
        val response = api.followings(vmid = mid)
        require(response.code == 0) {
            "关注列表失败：code=${response.code} ${response.message}"
        }
        response.data?.list.orEmpty().filter { it.mid > 0 && it.uname.isNotBlank() }
    }

    // ── 稍后再看 ────────────────────────────────────────

    /**
     * 稍后再看列表。必须登录（未登录 -101，不重试）。
     *
     * 只取视频型条目（bvid 非空）；进度字段保留，UI 可展示「播到哪了」。
     */
    suspend fun watchLater(): Result<List<WatchLaterItem>> = runCatching {
        requireLoggedInMid()
        authGate.awaitReady()
        val response = api.watchLater()
        require(response.code == 0) {
            "稍后再看失败：code=${response.code} ${response.message}"
        }
        response.data?.list.orEmpty().filter { it.bvid.isNotBlank() }
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
            requestThrottle.gate(ThrottleBucket.FEED, FEED_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) {
                block(signed)
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
                    refresh = { cookieRefresher.refreshIfNeeded() },
                    invalidate = { authGate.invalidate() },
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

    /** 读路径冷却闸门：处于 -352 冷却期则快速失败，不硬顶。 */
    private fun ensureNotCoolingRead() {
        if (riskCooldown.remaining() > 0) {
            throw SearchException(SearchError.Blocked, "触发风控冷却中，请稍后再试")
        }
    }

    // ── 评论读（B5）────────────────────────────────────────

    /** 评论列表的一页。 */
    data class CommentPage(val items: List<ReplyItem>, val count: Int)

    /**
     * 评论列表。先走 WBI 主链路，遇 -111/风控降级兼容链路一次（[runCommentRead]）。
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
            requestThrottle.gate(ThrottleBucket.FEED, FEED_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) {
                api.replyReply(params)
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
     * 拉取 `fid` 的关注状态。仅登录后有效；未登录会 -101 由调用方按现有错误分支处理。
     */
    suspend fun relation(fid: Long): Result<RelationData> = runCatching {
        ensureNotCoolingRead()
        val response = api.relation(fid)
        // -101 / -352 由 interceptor 链提前抛成 SearchException；
        // 这里只负责把 code != 0 折成 Result 失败
        if (response.code != 0) error("relation 接口失败：code=${response.code} ${response.message}")
        response.data ?: RelationData()
    }

    private companion object {
        const val TAG = "BiliSeekRepo"

        // B3 限速最小间隔（ms）+ 抖动。保守初值，真机按体感/效果校准（工单 §5.3）。
        const val SEARCH_MIN_INTERVAL_MS = 800L
        const val FEED_MIN_INTERVAL_MS = 600L
        const val WRITE_MIN_INTERVAL_MS = 1_000L
        const val THROTTLE_JITTER_MS = 300L
    }
}
