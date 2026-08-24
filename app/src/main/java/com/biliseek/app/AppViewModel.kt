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
import com.biliseek.app.data.BiliSeekDatabase
import com.biliseek.app.data.LibraryRepository
import com.biliseek.app.data.PlayHistoryEntity
import com.biliseek.app.data.PlaylistEntity
import com.biliseek.app.data.PlaylistTrack
import com.biliseek.app.data.TrackEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
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
    private val library = LibraryRepository(BiliSeekDatabase.get(app))
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

    // ── 本地库 ──────────────────────────────────────────

    val playlists = library.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val searchHistory = library.observeSearchHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentPlays = library.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val heatmap = library.observeHeatmap()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _openedPlaylist = MutableStateFlow<PlaylistEntity?>(null)
    val openedPlaylist: StateFlow<PlaylistEntity?> = _openedPlaylist.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val openedTracks: StateFlow<List<PlaylistTrack>> = _openedPlaylist
        .flatMapLatest { p -> if (p == null) flowOf(emptyList()) else library.observeTracks(p.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createPlaylist(name: String) = viewModelScope.launch { library.createPlaylist(name) }
    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { library.renamePlaylist(id, name) }
    fun deletePlaylist(id: Long) = viewModelScope.launch {
        library.deletePlaylist(id)
        if (_openedPlaylist.value?.id == id) _openedPlaylist.value = null
    }
    fun openPlaylist(p: PlaylistEntity) { _openedPlaylist.value = p }
    fun closePlaylist() { _openedPlaylist.value = null }
    fun removeFromPlaylist(trackId: Long) = viewModelScope.launch {
        _openedPlaylist.value?.let { library.removeFromPlaylist(it.id, trackId) }
    }

    /** 长按搜索结果加入歌单。没有歌单时自动建一个默认的。 */
    fun addToPlaylist(video: SearchVideo, cid: Long = 0) = viewModelScope.launch {
        val target = playlists.value.firstOrNull()?.id ?: library.createPlaylist("我的歌单")
        library.addToPlaylist(
            target,
            TrackEntity(
                bvid = video.bvid,
                cid = cid,
                title = com.biliseek.core.network.stripHighlight(video.title),
                author = video.author,
                coverUrl = com.biliseek.core.network.normalizeCoverUrl(video.pic),
                durationSec = 0,
            ),
        )
    }

    fun playHistoryEntry(entry: PlayHistoryEntity) =
        play(PlaybackRequest.Bili(entry.bvid, entry.cid.takeIf { it > 0 }, entry.title))

    fun playTrack(item: PlaylistTrack) {
        val items = openedTracks.value
        playPlaylist(items, items.indexOfFirst { it.track.id == item.track.id }.coerceAtLeast(0))
    }

    fun deleteSearchKeyword(keyword: String) = viewModelScope.launch { library.deleteSearch(keyword) }
    fun clearSearchHistory() = viewModelScope.launch { library.clearSearchHistory() }

    fun searchKeyword(keyword: String) {
        _search.value = _search.value.copy(keyword = keyword)
        submitSearch()
    }

    init {
        network.authGate.start()
        // 续播与位置回写：播放内核不认识数据库，通过回调解耦
        connection.resumePositionProvider = { bvid, cid -> library.resumePosition(bvid, cid) }
        connection.positionRecorder = { bvid, cid, position ->
            library.recordPlayPosition(bvid, cid, position)
        }
        viewModelScope.launch {
            network.sessionStore.load()
            connection.connect()
        }
    }

    // ── 搜索 ────────────────────────────────────────────

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
            is SearchIntent.Video -> {
                play(PlaybackRequest.Bili(intent.bvid, title = intent.bvid))
                return
            }
            is SearchIntent.Keyword -> {
                if (intent.text.isBlank()) return
                viewModelScope.launch { library.recordSearch(intent.text) }
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
                        play(PlaybackRequest.Bili(video.bvid, pages.firstOrNull()?.cid, cleanTitle(video.title)))
                    } else {
                        _search.value = _search.value.copy(
                            pages = pages.map {
                                PageEntry(it.cid, it.page, it.part, it.duration)
                            },
                            pagesOfBvid = video.bvid,
                        )
                    }
                }
                .onFailure { play(PlaybackRequest.Bili(video.bvid, title = cleanTitle(video.title))) }
        }
    }

    private fun cleanTitle(raw: String) = com.biliseek.core.network.stripHighlight(raw)

    /** 点某个分 P：整张多 P 视频作为队列，从该 P 开始播。 */
    fun playPage(bvid: String, entry: PageEntry) {
        val all = _search.value.pages
        val index = all.indexOfFirst { it.cid == entry.cid }.coerceAtLeast(0)
        viewModelScope.launch {
            connection.playQueue(
                all.map { PlaybackRequest.Bili(bvid, it.cid, it.title) },
                startIndex = index,
            )
            _playbackStarted.tryEmit(Unit)
            library.recordPlay(bvid, entry.cid, entry.title)
        }
    }

    /** 播放整个本地歌单。 */
    fun playPlaylist(items: List<PlaylistTrack>, startIndex: Int) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            connection.playQueue(
                items.map {
                    PlaybackRequest.Bili(it.track.bvid, it.track.cid.takeIf { c -> c > 0 }, it.track.title)
                },
                startIndex = startIndex,
            )
            _playbackStarted.tryEmit(Unit)
        }
    }

    fun skipNext() = connection.skipToNext()
    fun skipPrevious() = connection.skipToPrevious()
    fun cyclePlayMode() {
        val modes = com.biliseek.core.player.PlayMode.entries
        val next = modes[(modes.indexOf(connection.state.value.playMode) + 1) % modes.size]
        connection.setPlayMode(next)
    }

    fun onEnterBackground() = connection.onEnterBackground()

    /** PiP 进出。PiP 是可见呈现态，视频轨保持启用。 */
    fun onPipChanged(inPip: Boolean) {
        connection.setPresentation(
            if (inPip) {
                com.biliseek.core.player.PresentationMode.PIP
            } else {
                com.biliseek.core.player.PresentationMode.INLINE
            },
        )
    }

    fun dismissPages() {
        _search.value = _search.value.copy(pages = emptyList(), pagesOfBvid = "")
    }

    fun play(request: PlaybackRequest) {
        viewModelScope.launch {
            connection.play(request)
            _playbackStarted.tryEmit(Unit)
            (request as? PlaybackRequest.Bili)?.let { r ->
                library.recordPlay(r.bvid, r.cid ?: 0L, r.title)
            }
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
