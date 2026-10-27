package com.biliseek.app.search

import com.biliseek.app.SearchMode
import com.biliseek.app.SearchUiState
import com.biliseek.app.data.LibraryRepository
import com.biliseek.app.data.SearchHistoryEntity
import com.biliseek.app.ui.toSearchVideo
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
 * 搜索：关键词输入与提交（BV / AV / b23 短链 / 活动页直达）、外部分享文本、视频搜索与 UP 主搜索、
 * 分页、筛选、模式切换、搜索历史。
 *
 * 从 AppViewModel 拆出，由 `AppViewModel.search` 持有；生命周期挂在宿主的 [scope] 上。
 * 直达播放经 [play] 回到宿主。
 * 多 P 面板是宿主的独立状态（PagePanelState）；新一轮搜索、清空搜索框时经 [onNewSearch] 通知宿主收起。
 */
class SearchController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val library: LibraryRepository,
    private val play: (PlaybackRequest) -> Unit,
    private val onNewSearch: () -> Unit = {},
    /** 搜索框里认出活动页（b23 短链展开成活动页、或直接粘贴的网址）：交给榜单页列出里面的视频。 */
    private val openEraPage: (String) -> Unit = {},
) {
    private val _search = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _search.asStateFlow()

    /** 搜索历史（本地）。 */
    val history: StateFlow<List<SearchHistoryEntity>> = library.observeSearchHistory()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onKeywordChange(text: String) {
        // 清空搜索框即回到首页空状态：结果一并清掉，否则会停留在上次的列表上。
        // 多 P 面板也属于这次结果，一并收起：它只在有关键词时画出来，留着不收就成了看不见的面板，
        // 首页又因「面板开着」不显示，整页空白（2026-10-27 真机：面板开着时点 × 清空搜索框）。
        if (text.isBlank()) {
            onNewSearch()
            _search.value = SearchUiState()
        } else {
            _search.value = _search.value.copy(keyword = text)
        }
    }

    /**
     * 提交搜索。输入是 BV 号 / av 号 / 视频链接 / 短链时先拉详情显示一条结果，点了才播（用户 2026-10-04 要求）；
     * 否则走关键词搜索。
     */
    fun submitSearch() = submit(directPlay = false)

    /** [directPlay]：外部分享过来的直接起播；搜索框里自己输入的先显示结果。 */
    private fun submit(directPlay: Boolean) {
        val raw = _search.value.keyword
        when (val intent = parseSearchInput(raw)) {
            is SearchIntent.Video -> openVideo(intent.bvid, fallbackKeyword = raw, directPlay = directPlay)

            // av 号本地换算成 BV 号，不为了换算多打一次网络请求
            is SearchIntent.VideoAid -> {
                val bvid = runCatching { avToBv(intent.aid) }.getOrNull()
                if (bvid == null) doSearch(raw, page = 1)
                else openVideo(bvid, fallbackKeyword = raw, directPlay = directPlay)
            }

            is SearchIntent.ShortLink -> expandAndOpen(intent.url, fallbackKeyword = raw, directPlay = directPlay)

            is SearchIntent.EraPage -> showEraPage(intent.url)

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

    /**
     * 认出一个视频：外部分享过来的直接起播；搜索框里输入的先显示一条结果（[showVideoResult]）。
     */
    private fun openVideo(bvid: String, fallbackKeyword: String, directPlay: Boolean) {
        if (directPlay) {
            play(PlaybackRequest.Bili(bvid, title = bvid))
        } else {
            showVideoResult(bvid, fallbackKeyword)
        }
    }

    /**
     * 拉一次视频详情（播放页本来也要拉的公开接口），在搜索结果里只放这一条：标题、UP 主、时长、播放量都有，
     * 点了和普通结果一样起播（多 P 先挑 P）。在「UP 主」模式下也切回「视频」，结果才看得到。
     * 拉不到（断网、视频不存在）时退回把原输入当关键词搜索，和短链展不开时一样。
     */
    private fun showVideoResult(bvid: String, fallbackKeyword: String) {
        onNewSearch()
        _search.value = _search.value.copy(
            searchMode = SearchMode.VIDEO,
            isLoading = true,
            error = null,
            items = emptyList(), page = 0, totalPages = 0, totalResults = 0,
        )
        scope.launch {
            repository.videoView(bvid)
                .onSuccess { view ->
                    _search.value = _search.value.copy(
                        isLoading = false,
                        items = listOf(view.toSearchVideo()),
                        page = 1, totalPages = 1, totalResults = 1,
                    )
                }
                .onFailure {
                    _search.value = _search.value.copy(isLoading = false)
                    doSearch(fallbackKeyword, page = 1)
                }
        }
    }

    /**
     * 展开 b23 短链后二次解析：落点是视频就按 [openVideo] 处理，是活动页就列出页面里的视频，
     * 既不是视频也不是活动页就提示「这个链接不是视频」（[SearchError.NotVideo]）。
     * 展不开（断网、落点不在 B 站域内）时退回关键词搜索：这时它可能就是个正常视频，不能说它不是。
     */
    private fun expandAndOpen(url: String, fallbackKeyword: String, directPlay: Boolean) {
        scope.launch {
            _search.value = _search.value.copy(isLoading = true, error = null)
            repository.expandShortLink(url)
                .onSuccess { finalUrl ->
                    _search.value = _search.value.copy(isLoading = false)
                    when (val resolved = parseSearchInput(finalUrl)) {
                        is SearchIntent.Video -> openVideo(resolved.bvid, fallbackKeyword, directPlay)
                        is SearchIntent.VideoAid -> {
                            val bvid = runCatching { avToBv(resolved.aid) }.getOrNull()
                            if (bvid == null) doSearch(fallbackKeyword, page = 1)
                            else openVideo(bvid, fallbackKeyword, directPlay)
                        }
                        is SearchIntent.EraPage -> showEraPage(resolved.url)
                        else -> showNotVideo()
                    }
                }
                .onFailure {
                    _search.value = _search.value.copy(isLoading = false)
                    doSearch(fallbackKeyword, page = 1)
                }
        }
    }

    /**
     * 短链展开后既不是视频也不是活动页：明确提示「这个链接不是视频」，不再拿链接文字去搜索。
     * 旧结果一并清掉——有旧结果时错误只会收窄成列表底部一条，容易漏看；
     * 视频 / UP 主两种搜索模式的面板都要显示。
     */
    private fun showNotVideo() {
        onNewSearch()
        _search.value = _search.value.copy(
            isLoading = false,
            items = emptyList(), page = 0, totalPages = 0, totalResults = 0,
            users = emptyList(), userPage = 0, userTotalPages = 0, userTotalResults = 0,
            error = SearchError.NotVideo,
            userError = SearchError.NotVideo,
        )
    }

    /** 活动页：链接已经「用掉」，清空搜索框回到首页，由榜单页列出页面里的视频。 */
    private fun showEraPage(url: String) {
        _search.value = SearchUiState()
        openEraPage(url)
    }

    /**
     * 接住外部分享：分享过来的视频**直接起播**（用户 2026-10-04 选定；搜索框里输入的才先显示结果）。
     *
     * 分享过来的文本往往是「标题 + 一句吆喝 + 链接」，
     * [parseSearchInput] 本来就能从中挑出 BV / 短链，直接整段喂进去即可。
     */
    fun handleSharedText(text: String) {
        if (text.isBlank()) return
        _search.value = _search.value.copy(keyword = text.trim(), error = null)
        submit(directPlay = true)
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
