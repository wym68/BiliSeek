package com.biliseek.app.search

import com.biliseek.app.SearchMode
import com.biliseek.app.SearchUiState
import com.biliseek.app.data.LibraryRepository
import com.biliseek.app.data.SearchHistoryEntity
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.SearchDuration
import com.biliseek.core.network.SearchError
import com.biliseek.core.network.SearchException
import com.biliseek.core.network.SearchIntent
import com.biliseek.core.network.SearchOrder
import com.biliseek.core.network.SearchPage
import com.biliseek.core.network.avToBv
import com.biliseek.core.network.mergeSearchResults
import com.biliseek.core.network.parseSearchInput
import com.biliseek.core.player.PlaybackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 搜索：关键词输入与提交（BV / AV / b23 短链直达）、外部分享文本、视频搜索与 UP 主搜索、
 * 分页、筛选、模式切换、搜索历史。
 *
 * 从 AppViewModel 拆出，由 `AppViewModel.search` 持有；生命周期挂在宿主的 [scope] 上。
 * 直达播放经 [play] 回到宿主。
 * 多 P 面板是宿主的独立状态（PagePanelState）；新一轮搜索时经 [onNewSearch] 通知宿主收起。
 */
class SearchController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val library: LibraryRepository,
    private val play: (PlaybackRequest) -> Unit,
    private val onNewSearch: () -> Unit = {},
) {
    private val _search = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _search.asStateFlow()

    /** 搜索历史（本地）。 */
    val history: StateFlow<List<SearchHistoryEntity>> = library.observeSearchHistory()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onKeywordChange(text: String) {
        // 清空搜索框即回到首页空状态：结果一并清掉，否则会停留在上次的列表上
        _search.value = if (text.isBlank()) {
            SearchUiState()
        } else {
            _search.value.copy(keyword = text)
        }
    }

    /** 提交搜索。输入是 BV/AV/短链时直接播放，否则走关键词搜索。 */
    fun submitSearch() {
        val raw = _search.value.keyword
        when (val intent = parseSearchInput(raw)) {
            is SearchIntent.Video -> play(PlaybackRequest.Bili(intent.bvid, title = intent.bvid))

            // 本地换算，不为了拿 BV 号多打一次网络请求
            is SearchIntent.VideoAid -> {
                val bvid = runCatching { avToBv(intent.aid) }.getOrNull()
                if (bvid == null) doSearch(raw, page = 1)
                else play(PlaybackRequest.Bili(bvid, title = bvid))
            }

            is SearchIntent.ShortLink -> expandAndOpen(intent.url, fallbackKeyword = raw)

            is SearchIntent.Keyword -> {
                if (intent.text.isBlank()) return
                scope.launch { library.recordSearch(intent.text) }
                if (_search.value.searchMode == SearchMode.USER) {
                    doUserSearch(intent.text, page = 1)
                } else {
                    doSearch(intent.text, page = 1)
                }
            }
        }
    }

    /** 展开 b23 短链后二次解析。展不开或落点不可信时，退回关键词搜索。 */
    private fun expandAndOpen(url: String, fallbackKeyword: String) {
        scope.launch {
            _search.value = _search.value.copy(isLoading = true, error = null)
            repository.expandShortLink(url)
                .onSuccess { finalUrl ->
                    _search.value = _search.value.copy(isLoading = false)
                    when (val resolved = parseSearchInput(finalUrl)) {
                        is SearchIntent.Video ->
                            play(PlaybackRequest.Bili(resolved.bvid, title = resolved.bvid))
                        is SearchIntent.VideoAid -> {
                            val bvid = runCatching { avToBv(resolved.aid) }.getOrNull()
                            if (bvid == null) doSearch(fallbackKeyword, page = 1)
                            else play(PlaybackRequest.Bili(bvid, title = bvid))
                        }
                        else -> doSearch(fallbackKeyword, page = 1)
                    }
                }
                .onFailure {
                    _search.value = _search.value.copy(isLoading = false)
                    doSearch(fallbackKeyword, page = 1)
                }
        }
    }

    /**
     * 接住外部分享。
     *
     * 分享过来的文本往往是「标题 + 一句吆喝 + 链接」，
     * [parseSearchInput] 本来就能从中挑出 BV / 短链，直接整段喂进去即可。
     */
    fun handleSharedText(text: String) {
        if (text.isBlank()) return
        _search.value = _search.value.copy(keyword = text.trim(), error = null)
        submitSearch()
    }

    private fun doSearch(keyword: String, page: Int) {
        val filters = _search.value
        scope.launch {
            _search.value = _search.value.copy(
                isLoading = page == 1,
                isLoadingMore = page > 1,
                error = null,
            )
            if (page == 1) onNewSearch()
            repository.search(keyword, page, filters.order, filters.duration)
                .onSuccess { result -> applyPage(result, keyword, append = page > 1) }
                .onFailure { e ->
                    _search.value = _search.value.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = (e as? SearchException)?.error
                            ?: SearchError.Unknown(e.message ?: "搜索失败"),
                    )
                }
        }
    }

    private fun applyPage(result: SearchPage, keyword: String, append: Boolean) {
        val current = _search.value
        // B 站分页会返回重复条目，必须去重——LazyColumn 的 key 撞重复会直接崩
        val items = if (append) {
            mergeSearchResults(current.items, result.items)
        } else {
            mergeSearchResults(emptyList(), result.items)
        }
        _search.value = current.copy(
            items = items,
            page = result.page,
            totalPages = result.totalPages,
            totalResults = result.totalResults,
            isLoading = false,
            isLoadingMore = false,
            // code=0 但一条没有：接口是「成功」的，界面却会是白的。必须显式表达。
            error = if (items.isEmpty()) SearchError.Empty(keyword) else null,
        )
    }

    /** 错误面板上的「重试」。风控 [SearchError.Blocked] 不会走到这里——UI 不给按钮。 */
    fun retrySearch() {
        val s = _search.value
        if (s.keyword.isBlank()) return
        doSearch(s.keyword, page = if (s.page > 0) s.page else 1)
    }

    fun deleteSearchKeyword(keyword: String) = scope.launch { library.deleteSearch(keyword) }
    fun clearSearchHistory() = scope.launch { library.clearSearchHistory() }

    fun searchKeyword(keyword: String) {
        _search.value = _search.value.copy(keyword = keyword)
        submitSearch()
    }

    /**
     * 切换搜索筛选（时长 / 排序）。
     *
     * **重置到第 1 页**：筛选变了结果集就变了，停留在旧页码会把两页不同的
     * 结果接在一起。BV/AV/短链直达不走筛选，只有关键词搜索才响应。
     */
    fun setSearchFilters(order: SearchOrder, duration: SearchDuration) {
        val current = _search.value
        if (current.order == order && current.duration == duration) return
        _search.value = current.copy(order = order, duration = duration)
        if (current.keyword.isNotBlank() && parseSearchInput(current.keyword) is SearchIntent.Keyword) {
            doSearch(current.keyword, page = 1)
        }
    }

    fun loadMore() {
        val s = _search.value
        if (!s.hasMore || s.isLoadingMore || s.isLoading) return
        doSearch(s.keyword, s.page + 1)
    }

    // ── UP 主搜索（M11-D）──

    /**
     * 切换搜索目标（视频 / UP 主）。
     *
     * 切换后页码归 1 重搜——与搜索筛选切换同一纪律：
     * 两种模式结果集完全无关，停留在旧页码会把两页不同种类的结果接在一起。
     */
    fun setSearchMode(mode: SearchMode) {
        val current = _search.value
        if (current.searchMode == mode) return
        _search.value = current.copy(
            searchMode = mode,
            items = emptyList(),
            users = emptyList(),
            page = 0,
            userPage = 0,
            error = null,
            userError = null,
        )
        val keyword = current.keyword
        if (keyword.isNotBlank() && parseSearchInput(keyword) is SearchIntent.Keyword) {
            if (mode == SearchMode.USER) doUserSearch(keyword, page = 1) else doSearch(keyword, page = 1)
        }
    }

    private fun doUserSearch(keyword: String, page: Int) {
        scope.launch {
            _search.value = _search.value.copy(
                userLoading = page == 1,
                userError = null,
            )
            repository.searchUsers(keyword, page)
                .onSuccess { result -> applyUserPage(result, keyword, append = page > 1) }
                .onFailure { e ->
                    _search.value = _search.value.copy(
                        userLoading = false,
                        userError = (e as? SearchException)?.error
                            ?: SearchError.Unknown(e.message ?: "搜索失败"),
                    )
                }
        }
    }

    private fun applyUserPage(result: BiliRepository.UserSearchPage, keyword: String, append: Boolean) {
        val current = _search.value
        // UP 主按 mid 去重（B 站分页会返回重复条目，key 撞重复会崩）
        val users = if (append) {
            (current.users + result.items).distinctBy { it.mid }
        } else {
            result.items
        }
        _search.value = current.copy(
            users = users,
            userPage = result.page,
            userTotalPages = result.totalPages,
            userTotalResults = result.totalResults,
            userLoading = false,
            userError = if (users.isEmpty()) SearchError.Empty(keyword) else null,
        )
    }

    fun retryUserSearch() {
        val s = _search.value
        if (s.keyword.isBlank()) return
        doUserSearch(s.keyword, page = if (s.userPage > 0) s.userPage else 1)
    }

    fun loadMoreUsers() {
        val s = _search.value
        if (s.userPage <= 0 || s.userPage >= s.userTotalPages) return
        if (s.userLoading) return
        doUserSearch(s.keyword, s.userPage + 1)
    }
}
