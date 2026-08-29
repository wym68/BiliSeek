package com.biliseek.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.biliseek.core.network.BiliNetwork
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.LoginState
import com.biliseek.core.network.SearchDuration
import com.biliseek.core.network.SearchError
import com.biliseek.core.network.SearchException
import com.biliseek.core.network.SearchIntent
import com.biliseek.core.network.SearchOrder
import com.biliseek.core.network.SearchPage
import com.biliseek.core.network.avToBv
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.parseSearchInput
import android.view.SurfaceView
import com.biliseek.core.player.PlaybackPreferences
import com.biliseek.core.player.PlaybackRequest
import com.biliseek.core.player.PlayerConnection
import com.biliseek.core.player.PresentationMode
import com.biliseek.app.data.AppSettings
import com.biliseek.app.data.BiliSeekDatabase
import com.biliseek.app.data.DefaultPresentation
import com.biliseek.app.data.LibraryRepository
import com.biliseek.app.data.PlayHistoryEntity
import com.biliseek.app.data.PlaylistEntity
import com.biliseek.app.data.PlaylistTrack
import com.biliseek.app.data.SettingsStore
import com.biliseek.app.data.TrackEntity
import com.biliseek.app.data.VideoGate
import com.biliseek.app.data.resolveVideoGate
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
    val error: SearchError? = null,
    /** 搜索筛选（时长 / 排序）。变化时必须重置到第 1 页，否则页码与结果集错位。 */
    val order: SearchOrder = SearchOrder.COMPREHENSIVE,
    val duration: SearchDuration = SearchDuration.ANY,
    /** 展开的多 P 列表，非空时显示分 P 选择 */
    val pages: List<PageEntry> = emptyList(),
    val pagesOfBvid: String = "",
    /** 展开的多 P 视频封面。分 P 接口不返回封面，只能从点入的搜索结果带过来。 */
    val pagesCover: String = "",
) {
    val hasMore: Boolean get() = page in 1 until totalPages
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val network = BiliNetwork.get(app)
    private val library = LibraryRepository(BiliSeekDatabase.get(app))
    private val settingsStore = SettingsStore(app)
    private val downloadManager = com.biliseek.app.download.DownloadManager.get(app)
    val connection = PlayerConnection(app)

    val settings: StateFlow<AppSettings> = settingsStore.state

    /** 下载任务列表（含进度 / 失败可重试）。 */
    val downloadTasks: StateFlow<List<com.biliseek.app.download.DownloadTask>> =
        downloadManager.tasks

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

    /** 待加入歌单的条目。非空时 UI 弹歌单选择框。 */
    private val _pendingAdd = MutableStateFlow<SearchVideo?>(null)
    val pendingAdd: StateFlow<SearchVideo?> = _pendingAdd.asStateFlow()

    /** 长按「加入歌单」：先问加到哪个歌单，不再替用户选第一个。 */
    fun requestAddToPlaylist(video: SearchVideo) { _pendingAdd.value = video }

    fun dismissAddToPlaylist() { _pendingAdd.value = null }

    fun confirmAddToPlaylist(playlistId: Long) {
        val video = _pendingAdd.value ?: return
        _pendingAdd.value = null
        viewModelScope.launch { library.addToPlaylist(playlistId, video.toTrack()) }
    }

    /** 「新建歌单」分支：建完直接把待加入的条目放进去。 */
    fun createPlaylistAndAdd(name: String) {
        val video = _pendingAdd.value ?: return
        _pendingAdd.value = null
        if (name.isBlank()) return
        viewModelScope.launch {
            library.addToPlaylist(library.createPlaylist(name), video.toTrack())
        }
    }

    private fun SearchVideo.toTrack() = TrackEntity(
        bvid = bvid,
        cid = 0,
        title = com.biliseek.core.network.stripHighlight(title),
        author = author,
        coverUrl = coverOf(this),
        // 搜索结果里的 "4:32" 是入库时唯一能拿到时长的地方
        durationSec = com.biliseek.core.network.parseDurationText(duration),
    )

    /** 「下一首播放」。不打断正在播的这首。 */
    fun enqueueNext(video: SearchVideo) = connection.enqueueNext(video.toRequest())

    fun queueSkipTo(index: Int) = connection.skipToIndex(index)

    fun reorderTrack(trackId: Long, before: Double?, after: Double?) = viewModelScope.launch {
        _openedPlaylist.value?.let { library.reorder(it.id, trackId, before, after) }
    }

    /** 「查看分 P」：不管是不是单 P 都展开列表，而不是直接起播。 */
    fun showPages(video: SearchVideo) {
        viewModelScope.launch {
            network.repository.pages(video.bvid).onSuccess { pages ->
                _search.value = _search.value.copy(
                    pages = pages.map { PageEntry(it.cid, it.page, it.part, it.duration) },
                    pagesOfBvid = video.bvid,
                    pagesCover = coverOf(video),
                )
            }
        }
    }

    fun videoUrl(video: SearchVideo) = "https://www.bilibili.com/video/${video.bvid}"

    fun playHistoryEntry(entry: PlayHistoryEntity) =
        play(
            PlaybackRequest.Bili(
                entry.bvid,
                entry.cid.takeIf { it > 0 },
                entry.title,
                entry.coverUrl,
                author = entry.author,
                durationSec = entry.durationSec,
            ),
        )

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

    init {
        network.authGate.start()
        // 续播与位置回写：播放内核不认识数据库，通过回调解耦
        connection.resumePositionProvider = { bvid, cid -> library.resumePosition(bvid, cid) }
        connection.positionRecorder = { bvid, cid, position ->
            library.recordPlayPosition(bvid, cid, position)
        }
        connection.durationRecorder = { bvid, cid, durationSec ->
            library.recordDuration(bvid, cid, durationSec)
        }
        // 起播落库挂在门面上而不是三个 UI 入口上：队列自动推进的第二条及之后
        // 走不到那三个入口，此前就是这样漏掉的
        connection.trackStartRecorder = { r ->
            library.recordPlay(
                bvid = r.bvid,
                cid = r.cid ?: 0L,
                title = r.title,
                author = r.author,
                coverUrl = r.coverUrl,
                durationSec = r.durationSec,
            )
        }
        // 离线命中：取流前、风控凭据前查本地下载库（M9 §9.4 的拦截点）
        connection.offlineLookup = { bvid, cid ->
            library.lookupOffline(bvid, cid) { uri -> fileExists(uri) }
        }
        // 下载取流：与播放共用同一份解析器（同一份缓存与降级链）
        downloadManager.resolveSource = { bvid, cid, quality ->
            connection.resolveDownloadSource(bvid, cid, quality)
        }
        downloadManager.qualityProvider = { settingsStore.current.audioQuality }
        // 取流时现读，用户改完设置下一首即生效
        connection.preferenceProvider = {
            val s = settingsStore.current
            PlaybackPreferences(
                audioQuality = s.audioQuality,
                loudnessNormalization = s.loudnessNormalization,
            )
        }
        viewModelScope.launch {
            settingsStore.load()
            network.sessionStore.load()
            connection.connect()
            // 登录态续期。会话已加载后再查，不阻塞 UI，失败也不打扰用户
            val outcome = network.cookieRefresher.refreshIfNeeded()
            android.util.Log.d("BiliSeekRefresh", "启动时检查登录态续期：$outcome")
            // 中断的下载任务捞回队列（续传 / 重试）
            downloadManager.resumePending()
        }
    }

    /** content:// 与 file:// 都存在性检查（SAF 走 DocumentsProvider 查询）。 */
    private fun fileExists(uri: String): Boolean = when {
        uri.startsWith("file://") -> {
            val path = android.net.Uri.parse(uri).path.orEmpty()
            path.isNotBlank() && java.io.File(path).exists()
        }
        uri.startsWith("content://") -> runCatching {
            val resolver = getApplication<Application>().contentResolver
            resolver.query(android.net.Uri.parse(uri), null, null, null, null)
                ?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
        else -> false
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
                viewModelScope.launch { library.recordSearch(intent.text) }
                doSearch(intent.text, page = 1)
            }
        }
    }

    /** 展开 b23 短链后二次解析。展不开或落点不可信时，退回关键词搜索。 */
    private fun expandAndOpen(url: String, fallbackKeyword: String) {
        viewModelScope.launch {
            _search.value = _search.value.copy(isLoading = true, error = null)
            network.repository.expandShortLink(url)
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
        viewModelScope.launch {
            _search.value = _search.value.copy(
                isLoading = page == 1,
                isLoadingMore = page > 1,
                error = null,
                pages = if (page == 1) emptyList() else _search.value.pages,
            )
            network.repository.search(keyword, page, filters.order, filters.duration)
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
            com.biliseek.core.network.mergeSearchResults(current.items, result.items)
        } else {
            com.biliseek.core.network.mergeSearchResults(emptyList(), result.items)
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

    /** 取流失败后的「重试」：重放队列里当前这条。 */
    fun retryPlayback() = connection.retry()

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
                        _search.value = _search.value.copy(
                            pages = emptyList(),
                            pagesOfBvid = "",
                            pagesCover = "",
                        )
                        play(video.toRequest(cid = pages.firstOrNull()?.cid))
                    } else {
                        _search.value = _search.value.copy(
                            pages = pages.map {
                                PageEntry(it.cid, it.page, it.part, it.duration)
                            },
                            pagesOfBvid = video.bvid,
                            pagesCover = coverOf(video),
                        )
                    }
                }
                .onFailure { play(video.toRequest()) }
        }
    }

    private fun cleanTitle(raw: String) = com.biliseek.core.network.stripHighlight(raw)

    /** 搜索结果 → 播放请求。UP 主与时长一并带上，队列自动推进时历史才写得全。 */
    private fun SearchVideo.toRequest(cid: Long? = null) = PlaybackRequest.Bili(
        bvid = bvid,
        cid = cid,
        title = cleanTitle(title),
        coverUrl = coverOf(this),
        author = author,
        durationSec = com.biliseek.core.network.parseDurationText(duration),
    )

    private fun coverOf(video: SearchVideo) =
        com.biliseek.core.network.normalizeCoverUrl(video.pic)

    /** 点某个分 P：整张多 P 视频作为队列，从该 P 开始播。 */
    fun playPage(bvid: String, entry: PageEntry) {
        val all = _search.value.pages
        val cover = _search.value.pagesCover
        val index = all.indexOfFirst { it.cid == entry.cid }.coerceAtLeast(0)
        viewModelScope.launch {
            connection.playQueue(
                all.map {
                    PlaybackRequest.Bili(
                        bvid,
                        it.cid,
                        it.title,
                        cover,
                        durationSec = it.durationSec,
                    )
                },
                startIndex = index,
            )
            _playbackStarted.tryEmit(Unit)
        }
    }

    /** 播放整个本地歌单。 */
    fun playPlaylist(items: List<PlaylistTrack>, startIndex: Int) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            connection.playQueue(
                items.map {
                    PlaybackRequest.Bili(
                        it.track.bvid,
                        it.track.cid.takeIf { c -> c > 0 },
                        it.track.title,
                        it.track.coverUrl,
                        author = it.track.author,
                        durationSec = it.track.durationSec,
                    )
                },
                startIndex = startIndex,
            )
            _playbackStarted.tryEmit(Unit)
        }
    }

    fun skipNext() = connection.skipToNext()
    fun skipPrevious() = connection.skipToPrevious()
    fun seekTo(positionMs: Long) = connection.seekTo(positionMs)
    fun setSpeed(speed: Float) = connection.setSpeed(speed)

    /** [durationMs] 为 0 表示取消；负数表示「播完当前曲目」。 */
    fun setSleepTimer(durationMs: Long) = when {
        durationMs < 0 -> connection.setSleepTimerEndOfTrack()
        durationMs == 0L -> connection.cancelSleepTimer()
        else -> connection.setSleepTimer(durationMs)
    }

    fun cyclePlayMode() {
        val modes = com.biliseek.core.player.PlayMode.entries
        val next = modes[(modes.indexOf(connection.state.value.playMode) + 1) % modes.size]
        connection.setPlayMode(next)
    }

    fun onEnterBackground() = connection.onEnterBackground()

    // ── 下载 ────────────────────────────────────────────

    /** 入队下载。内容（只音频/视频）与位置在调用前由 UI 弹窗定好。 */
    fun enqueueDownload(request: com.biliseek.app.download.DownloadRequest) =
        downloadManager.enqueue(request)

    fun retryDownload(id: Long) = downloadManager.retry(id)

    /**
     * 删除下载。公共 / SAF 时由 UI 弹窗决定是否连源文件一起删，
     * 见 [com.biliseek.app.download.DownloadManager.delete]。
     */
    fun deleteDownload(id: Long, deleteFile: Boolean = false) = downloadManager.delete(id, deleteFile)

    /** 播放一条已下载的条目。走普通播放路径——离线拦截点在取流层。 */
    fun playDownload(task: com.biliseek.app.download.DownloadTask) {
        play(
            PlaybackRequest.Bili(
                bvid = task.bvid,
                cid = task.cid.takeIf { it > 0 },
                title = task.title,
                coverUrl = task.coverUrl,
                author = task.author,
            ),
        )
    }

    /** 记住下载位置，下次默认带入。 */
    fun setDownloadLocation(value: String) =
        viewModelScope.launch { settingsStore.setDownloadLocation(value) }

    fun setDownloadSafTreeUri(value: String) =
        viewModelScope.launch { settingsStore.setDownloadSafTreeUri(value) }

    // ── 设置 ────────────────────────────────────────────

    fun setDefaultPresentation(value: DefaultPresentation) =
        viewModelScope.launch { settingsStore.setDefaultPresentation(value) }

    fun setAllowVideoOnMetered(value: Boolean) =
        viewModelScope.launch { settingsStore.setAllowVideoOnMetered(value) }

    fun setAudioQuality(value: com.biliseek.core.player.AudioQuality) =
        viewModelScope.launch { settingsStore.setAudioQuality(value) }

    fun setLoudnessNormalization(value: Boolean) =
        viewModelScope.launch { settingsStore.setLoudnessNormalization(value) }

    /** 被流量策略拦下的那次「切看」。非空时 UI 询问是否这一次放行。 */
    private val _videoBlocked = MutableStateFlow<PresentationMode?>(null)
    val videoBlocked: StateFlow<PresentationMode?> = _videoBlocked.asStateFlow()

    /**
     * 呈现态变更的唯一入口。所有「切看」都要过流量策略这一关。
     *
     * 退回听与 PiP 不受策略约束：前者只会更省，后者是系统在驱动。
     */
    fun requestPresentation(target: PresentationMode, surfaceView: SurfaceView?) {
        val isVideo = target != PresentationMode.NONE
        if (!isVideo || target == PresentationMode.PIP) {
            applyPresentation(target, surfaceView)
            return
        }
        when (resolveVideoGate(settingsStore.current, isMetered())) {
            VideoGate.ALLOW -> applyPresentation(target, surfaceView)
            VideoGate.BLOCKED_BY_METERED -> _videoBlocked.value = target
        }
    }

    /**
     * 真正切过去，并记住这次是听还是看（「记住上次」用）。
     *
     * 一次性放行也走这里——**但不写回 `allowVideoOnMetered`**：
     * 用户说的是「这一次」，把它变成永久开关是替用户做主。
     */
    fun applyPresentation(target: PresentationMode, surfaceView: SurfaceView?) {
        _videoBlocked.value = null
        connection.setPresentation(target, surfaceView)
        viewModelScope.launch {
            settingsStore.setLastPresentationWasVideo(target != PresentationMode.NONE)
        }
    }

    fun dismissVideoBlocked() { _videoBlocked.value = null }

    /** 起播后是否应自动进看模式。「记住上次」开着且上次在看时才为真。 */
    fun shouldAutoEnterVideo(): Boolean {
        val s = settingsStore.current
        return s.defaultPresentation == DefaultPresentation.REMEMBER_LAST &&
            s.lastPresentationWasVideo
    }

    /** 计量网络（移动数据 / 计费热点）判定。策略本身是纯函数，这里只负责问系统。 */
    private fun isMetered(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return false
        return cm.isActiveNetworkMetered
    }

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
        _search.value = _search.value.copy(pages = emptyList(), pagesOfBvid = "", pagesCover = "")
    }

    /**
     * 起播。
     *
     * 历史落库由 `connection.trackStartRecorder` 在 `play()` 内部完成——
     * 那里是所有起播路径（含队列自动推进）的唯一收敛点。
     */
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
