package com.biliseek.core.network

import android.util.Log
import com.biliseek.core.network.dto.PageItem
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.internal.RetryAction
import com.biliseek.core.network.internal.resolveRetryAction

/** 搜索结果的一页。 */
data class SearchPage(
    val items: List<SearchVideo>,
    val page: Int,
    val totalPages: Int,
    val totalResults: Int,
) {
    val hasMore: Boolean get() = page < totalPages
}

/** 业务数据入口。UI 层不直接碰 [BiliApi]。 */
class BiliRepository(
    private val api: BiliApi,
    private val authGate: AuthGate,
) {

    suspend fun search(keyword: String, page: Int = 1): Result<SearchPage> = runCatching {
        val credentials = authGate.awaitReady()
        val params = WbiSigner.sign(
            params = mapOf(
                "search_type" to "video",
                "keyword" to keyword,
                "page" to page.toString(),
                "page_size" to PAGE_SIZE.toString(),
            ),
            imgKey = credentials.wbiImgKey,
            subKey = credentials.wbiSubKey,
        )
        val response = api.searchType(params)
        when (resolveRetryAction(response.code)) {
            RetryAction.BACKOFF_NO_RETRY ->
                error("触发风控（-352），已进入退避，请稍后再试")
            RetryAction.RESEED_CREDENTIALS -> {
                authGate.invalidate()
                error("请求被拦截（-412），已重置设备凭据，请重试")
            }
            else -> Unit
        }
        require(response.code == 0) { "搜索失败：code=${response.code} ${response.message}" }

        // data 为 null 通常意味着 buvid 未激活，服务端只回了 v_voucher 风控凭据。
        // 这种情况必须显式报错，不能静默返回空列表——否则表现为「搜索没反应」，极难排查。
        val data = response.data
            ?: error("搜索未返回结果，设备凭据可能未激活，请稍后重试")
        SearchPage(
            items = data.result.orEmpty().filter { it.bvid.isNotBlank() },
            page = data.page.takeIf { it > 0 } ?: page,
            totalPages = data.numPages,
            totalResults = data.numResults,
        )
    }

    /** 分 P 列表。用于判断是否需要展开为歌单。 */
    suspend fun pages(bvid: String): Result<List<PageItem>> = runCatching {
        authGate.awaitReady()
        val response = api.getPageList(bvid)
        require(response.code == 0) { "pagelist 失败：code=${response.code} ${response.message}" }
        response.data.orEmpty()
    }

    private companion object {
        const val TAG = "BiliSeekRepo"
        const val PAGE_SIZE = 20
    }
}
