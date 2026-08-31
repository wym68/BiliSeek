package com.biliseek.core.network

import android.util.Log
import com.biliseek.core.network.dto.PageItem
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.dto.FavoriteFolderItem
import com.biliseek.core.network.dto.FavoriteMedia
import com.biliseek.core.network.dto.DynamicFeedData
import com.biliseek.core.network.dto.FollowingUp
import com.biliseek.core.network.dto.SearchUser
import com.biliseek.core.network.dto.SpaceVideo
import com.biliseek.core.network.dto.WatchLaterItem
import com.biliseek.core.network.dto.SeriesItem
import com.biliseek.core.network.dto.SeriesArchivesData
import com.biliseek.core.network.dto.SeriesArchive
import com.biliseek.core.network.dto.SeriesPage
import com.biliseek.core.network.dto.BiliResponse
import com.biliseek.core.network.internal.RetryAction
import com.biliseek.core.network.internal.resolveRetryAction
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

/** 业务数据入口。UI 层不直接碰 [BiliApi]。 */
class BiliRepository(
    private val api: BiliApi,
    private val authGate: AuthGate,
    private val sessionStore: SessionStore,
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
        val response = try {
            api.searchType(params)
        } catch (e: IOException) {
            throw SearchException(SearchError.Network(e.message.orEmpty()), e.message ?: "网络不可达")
        }
        when (resolveRetryAction(response.code)) {
            RetryAction.BACKOFF_NO_RETRY ->
                throw SearchException(SearchError.Blocked, "触发风控（-352），已进入退避")
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
        val response = try {
            block(signed)
        } catch (e: IOException) {
            throw SearchException(SearchError.Network(e.message.orEmpty()), e.message ?: "网络不可达")
        }
        when (resolveRetryAction(response.code)) {
            RetryAction.BACKOFF_NO_RETRY ->
                throw SearchException(SearchError.Blocked, "触发风控（-352），已进入退避")
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
            throw SearchException(
                SearchError.Unknown("code=${response.code} ${response.message}"),
                "请求失败：code=${response.code} ${response.message}",
            )
        }
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


    private companion object {
        const val TAG = "BiliSeekRepo"
    }
}
