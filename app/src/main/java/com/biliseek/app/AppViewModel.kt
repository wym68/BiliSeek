package com.biliseek.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.biliseek.core.network.BiliNetwork
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.LoginState
import com.biliseek.core.network.SearchIntent
import com.biliseek.core.network.SearchPage
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.parseSearchInput
import com.biliseek.core.player.PlaybackRequest
import com.biliseek.core.player.PlayerConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 一个分 P。多 P 视频点入后展开成列表。 */
data class PageEntry(val cid: Long, val page: Int, val title: String, val durationSec: Long)

data class SearchUiState(
    val keyword: String = "",
    val items: List<SearchVideo> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 0,
    val totalResults: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    /** 展开的多 P 列表，非空时显示分 P 选择 */
    val pages: List<PageEntry> = emptyList(),
    val pagesOfBvid: String = "",
) {
    val hasMore: Boolean get() = page in 1 until totalPages
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val network = BiliNetwork.get(app)
    val connection = PlayerConnection(app)

    val authState = network.authGate.state
    val loginState: StateFlow<LoginState> = network.loginManager.state
    val session: StateFlow<BiliSession> = network.sessionStore.session

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    /**
     * 「已开始播放」事件。UI 据此切到播放页。
     *
     * 不能在点击时无条件切页：点到多 P 视频时应留在列表展开分 P，
     * 只有真正起播了才跳转。
     */
    private val _playbackStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val playbackStarted: SharedFlow<Unit> = _playbackStarted.asSharedFlow()

    private var pollJob: Job? = null

    init {
        network.authGate.start()
        viewModelScope.launch {
            network.sessionStore.load()
            connection.connect()
        }
    }

    // ── 搜索 ────────────────────────────────────────────

    fun onKeywordChange(text: String) {
        _search.value = _search.value.copy(keyword = text)
    }

    /** 提交搜索。输入是 BV/AV/短链时直接播放，否则走关键词搜索。 */
    fun submitSearch() {
        val raw = _search.value.keyword
        when (val intent = parseSearchInput(raw)) {
            is SearchIntent.Video -> {
                play(PlaybackRequest.Bili(intent.bvid, title = intent.bvid))
                return
            }
            is SearchIntent.Keyword -> {
                if (intent.text.isBlank()) return
                doSearch(intent.text, page = 1)
            }
            // AV 号与短链的解析留到后续里程碑，先退回关键词搜索
            else -> doSearch(raw, page = 1)
        }
    }

    private fun doSearch(keyword: String, page: Int) {
        viewModelScope.launch {
            _search.value = _search.value.copy(
                isLoading = page == 1,
                isLoadingMore = page > 1,
                error = null,
                pages = if (page == 1) emptyList() else _search.value.pages,
            )
            network.repository.search(keyword, page)
                .onSuccess { result -> applyPage(result, append = page > 1) }
                .onFailure { e ->
                    _search.value = _search.value.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = e.message ?: "搜索失败",
                    )
                }
        }
    }

    private fun applyPage(result: SearchPage, append: Boolean) {
        val current = _search.value
        _search.value = current.copy(
            items = if (append) current.items + result.items else result.items,
            page = result.page,
            totalPages = result.totalPages,
            totalResults = result.totalResults,
            isLoading = false,
            isLoadingMore = false,
        )
    }

    fun loadMore() {
        val s = _search.value
        if (!s.hasMore || s.isLoadingMore || s.isLoading) return
        doSearch(s.keyword, s.page + 1)
    }

    // ── 播放 ────────────────────────────────────────────

    /**
     * 点击搜索结果。先查分 P：
     * 单 P 直接播放；多 P 展开成列表（对应「一切皆歌单」的建模）。
     */
    fun onResultClick(video: SearchVideo) {
        viewModelScope.launch {
            network.repository.pages(video.bvid)
                .onSuccess { pages ->
                    if (pages.size <= 1) {
                        _search.value = _search.value.copy(pages = emptyList(), pagesOfBvid = "")
                        play(PlaybackRequest.Bili(video.bvid, pages.firstOrNull()?.cid, video.title))
                    } else {
                        _search.value = _search.value.copy(
                            pages = pages.map {
                                PageEntry(it.cid, it.page, it.part, it.duration)
                            },
                            pagesOfBvid = video.bvid,
                        )
                    }
                }
                .onFailure { play(PlaybackRequest.Bili(video.bvid, title = video.title)) }
        }
    }

    fun playPage(bvid: String, entry: PageEntry) {
        play(PlaybackRequest.Bili(bvid, entry.cid, entry.title))
    }

    fun dismissPages() {
        _search.value = _search.value.copy(pages = emptyList(), pagesOfBvid = "")
    }

    fun play(request: PlaybackRequest) {
        viewModelScope.launch {
            connection.play(request)
            _playbackStarted.tryEmit(Unit)
        }
    }

    // ── 登录 ────────────────────────────────────────────

    fun startLogin() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            network.loginManager.requestQrCode().onSuccess { ready ->
                network.loginManager.poll(ready.key)
            }
        }
    }

    fun cancelLogin() {
        pollJob?.cancel()
        network.loginManager.reset()
    }

    fun logout() {
        viewModelScope.launch { network.loginManager.logout() }
    }

    override fun onCleared() {
        pollJob?.cancel()
        connection.release()
        super.onCleared()
    }
}
