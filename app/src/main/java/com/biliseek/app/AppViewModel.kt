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
import com.biliseek.core.network.dto.FavoriteFolderItem
import com.biliseek.core.network.dto.FavoriteMedia
import com.biliseek.core.network.dto.SearchUser
import com.biliseek.core.network.dto.SpaceVideo
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
import com.biliseek.app.data.FavoriteFolderEntity
import com.biliseek.app.data.TrackListId
import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.DynamicEntry
import com.biliseek.core.network.DynamicType
import com.biliseek.core.network.DynamicItemType
import com.biliseek.core.network.dto.FollowingUp
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

/** 收藏夹内容条目（可播放形状）。 */
data class FavoriteTrack(
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val durationSec: Long,
    val author: String,
)

/** 收藏夹目录同步状态。 */
sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data object NotLoggedIn : SyncState
    data class Error(val message: String) : SyncState
}

/** 动态 tab 的初始化状态（关注列表拉取）。 */
sealed interface DynamicInitState {
    data object Idle : DynamicInitState
    data object Loading : DynamicInitState
    data class Error(val message: String) : DynamicInitState
}

/** 动态 tab 的 UI 状态（M11-C）。 */
data class DynamicUiState(
    /** 全部 / 仅视频。默认仅视频（听场景，全可播）。 */
    val typeFilter: String = DynamicType.VIDEO,
    /** 按 UP 筛选：null = 关注流（feed/all），非 null = 该 UP 的动态（feed/space）。 */
    val selectedUpMid: Long? = null,
    /** 关注列表（UP 筛选 chips）。登录后拉一次，内存缓存。 */
    val upChips: List<FollowingUp> = emptyList(),
    val initState: DynamicInitState = DynamicInitState.Idle,
    /** 当前视图的 feed（关注流或单个 UP）。 */
    val feed: RemoteListState<DynamicEntry> = RemoteListState.empty(),
    /** 分页游标（feed/all、feed/space 用 offset 而非页码）。 */
    val feedOffset: String = "",
)

/** 搜索目标：视频 / UP 主（M11-D）。 */
enum class SearchMode { VIDEO, USER }

/** UP 空间页的视图（M11-D）。 */
enum class UpSpaceView { POSTS, DYNAMIC }

/** UP 空间页状态：UP 头部卡 + 投稿 / 动态双视图。 */
data class UpSpaceUiState(
    val up: SearchUser? = null,
    val view: UpSpaceView = UpSpaceView.POSTS,
    val posts: RemoteListState<SpaceVideo> = RemoteListState.empty(),
    val dynamic: RemoteListState<DynamicEntry> = RemoteListState.empty(),
    /** 动态视图的 offset 游标（feed/space）。 */
    val dynamicOffset: String = "",
)

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
    /** 搜索目标：视频 / UP 主。切换重置页码重搜（M11-D）。 */
    val searchMode: SearchMode = SearchMode.VIDEO,
    /** UP 主搜索结果（searchMode == USER 时使用）。 */
    val users: List<SearchUser> = emptyList(),
    val userPage: Int = 0,
    val userTotalPages: Int = 0,
    val userTotalResults: Int = 0,
    val userLoading: Boolean = false,
    val userError: SearchError? = null,
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

    // ── 收藏夹目录快照（M11-B）──────────────────────────

    /** 收藏夹目录（音乐库「收藏夹」分区）。落库快照，未登录时为空。 */
    val favoriteFolders: StateFlow<List<FavoriteFolderEntity>> = library.observeFavoriteFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 目录同步状态：拉取中 / 错误（可重试）。 */
    private val _favoriteSync = MutableStateFlow<SyncState>(SyncState.Idle)
    val favoriteSync: StateFlow<SyncState> = _favoriteSync.asStateFlow()

    /**
     * 同步收藏夹目录（整体替换快照）。
     *
     * 未登录不发起网络请求，直接置 [SyncState.NotLoggedIn]——
     * 音乐库分区据此显示登录引导。
     */
    fun syncFavoriteFolders() {
        if (_favoriteSync.value is SyncState.Syncing) return
        if (!session.value.isLoggedIn) {
            _favoriteSync.value = SyncState.NotLoggedIn
            return
        }
        viewModelScope.launch {
            _favoriteSync.value = SyncState.Syncing
            network.repository.favoriteFolders()
                .onSuccess { folders ->
                    library.replaceFavoriteFolders(
                        folders.map { folder ->
                            FavoriteFolderEntity(
                                mediaId = folder.id,
                                title = folder.title,
                                coverUrl = com.biliseek.core.network.normalizeCoverUrl(folder.cover),
                                mediaCount = folder.mediaCount,
                                syncedAt = System.currentTimeMillis(),
                            )
                        },
                    )
                    _favoriteSync.value = SyncState.Idle
                }
                .onFailure {
                    _favoriteSync.value = SyncState.Error(it.message ?: "同步失败")
                }
        }
    }

    private fun FavoriteMedia.toTrack() = FavoriteTrack(
        bvid = bvid,
        title = com.biliseek.core.network.stripHighlight(title),
        coverUrl = com.biliseek.core.network.normalizeCoverUrl(cover),
        durationSec = duration,
        author = upper?.name.orEmpty(),
    )

    /** 打开收藏夹内容页（分页拉取，不落库）。 */
    fun openFavorite(mediaId: Long, title: String) {
        _openedFavorite.value = RemoteListState(
            id = TrackListId.Favorite(mediaId),
            title = title,
        )
        loadFavoritePage(1)
    }

    fun closeFavorite() { _openedFavorite.value = null }

    private val _openedFavorite =
        MutableStateFlow<RemoteListState<FavoriteTrack>?>(null)
    val openedFavorite: StateFlow<RemoteListState<FavoriteTrack>?> = _openedFavorite.asStateFlow()

    /** 加载收藏夹内容第 [page] 页。翻页时追加，刷新时从第 1 页替换。 */
    fun loadFavoritePage(page: Int) {
        val state = _openedFavorite.value ?: return
        val id = state.id as? TrackListId.Favorite ?: return
        if (state.isLoading || state.isLoadingMore) return
        if (page > 1 && !state.hasMore) return
        viewModelScope.launch {
            val loadingMore = page > 1
            _openedFavorite.value = state.copy(
                isLoading = !loadingMore,
                isLoadingMore = loadingMore,
                errorMessage = null,
            )
            network.repository.favoriteResources(id.mediaId, page)
                .onSuccess { result ->
                    val base = _openedFavorite.value ?: return@onSuccess
                    _openedFavorite.value = base.copy(
                        items = if (loadingMore) base.items + result.items.map { it.toTrack() }
                        else result.items.map { it.toTrack() },
                        page = result.page,
                        hasMore = result.hasMore,
                        isLoading = false,
                        isLoadingMore = false,
                    )
                }
                .onFailure {
                    val base = _openedFavorite.value ?: return@onFailure
                    _openedFavorite.value = base.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        errorMessage = it.message ?: "加载失败",
                    )
                }
        }
    }

    /**
     * 点收藏夹条目：取分 P，单 P 直接播，多 P 整张作队列从头播。
     * （搜索结果里「先展开再选」是因为用户在看；收藏夹里点播放意图是「听」。）
     */
    fun playFavoriteTrack(item: FavoriteTrack) {
        playBvid(
            bvid = item.bvid,
            title = item.title,
            coverUrl = item.coverUrl,
            author = item.author,
            durationSec = item.durationSec,
        )
    }

    /**
     * 按 bvid 起播：单 P 直接播，多 P 整张作队列从头播。
     * 收藏夹内容 / 动态视频共用同一条路径（先取分 P 再起播）。
     */
    private fun playBvid(
        bvid: String,
        title: String,
        coverUrl: String,
        author: String,
        durationSec: Long,
    ) {
        viewModelScope.launch {
            network.repository.pages(bvid)
                .onSuccess { pages ->
                    if (pages.size <= 1) {
                        play(
                            PlaybackRequest.Bili(
                                bvid,
                                pages.firstOrNull()?.cid?.takeIf { it > 0 },
                                title,
                                coverUrl,
                                author = author,
                                durationSec = durationSec,
                            ),
                        )
                    } else {
                        connection.playQueue(
                            pages.map {
                                PlaybackRequest.Bili(
                                    bvid,
                                    it.cid,
                                    it.part.ifBlank { title },
                                    coverUrl,
                                    author = author,
                                    durationSec = it.duration,
                                )
                            },
                            startIndex = 0,
                        )
                        _playbackStarted.tryEmit(Unit)
                    }
                }
                .onFailure { /* 失败静默：取流层会有自己的错误呈现 */ }
        }
    }

    // ── 动态（M11-C）──────────────────────────────────────

    private val _dynamic = MutableStateFlow(DynamicUiState())
    val dynamic: StateFlow<DynamicUiState> = _dynamic.asStateFlow()

    /** 登录后首次进入动态 tab 时调用：拉关注列表 + 第一页动态。 */
    fun initDynamic() {
        if (!session.value.isLoggedIn) return
        val cur = _dynamic.value
        if (cur.initState is DynamicInitState.Loading) return
        if (cur.upChips.isNotEmpty() || cur.feed.items.isNotEmpty()) return
        viewModelScope.launch {
            _dynamic.value = cur.copy(initState = DynamicInitState.Loading)
            network.repository.followings()
                .onSuccess { ups ->
                    _dynamic.value = _dynamic.value.copy(
                        upChips = ups,
                        initState = DynamicInitState.Idle,
                    )
                    loadDynamicPage(offset = "")
                }
                .onFailure {
                    _dynamic.value = _dynamic.value.copy(
                        initState = DynamicInitState.Error(it.message ?: "加载失败"),
                    )
                }
        }
    }

    /** 「全部 / 仅视频」切换：变化必须重置游标重拉第 1 页。 */
    fun setDynamicTypeFilter(type: String) {
        val cur = _dynamic.value
        if (cur.typeFilter == type) return
        _dynamic.value = cur.copy(typeFilter = type, feed = RemoteListState.empty(), feedOffset = "")
        loadDynamicPage(offset = "")
    }

    /** 按 UP 筛选：null = 回关注流，非 null = 只看该 UP。切换同样重置游标。 */
    fun selectDynamicUp(up: FollowingUp?) {
        val cur = _dynamic.value
        if (cur.selectedUpMid == up?.mid) return
        _dynamic.value = cur.copy(
            selectedUpMid = up?.mid,
            feed = RemoteListState.empty(),
            feedOffset = "",
        )
        loadDynamicPage(offset = "")
    }

    /** 加载下一页（继续翻）。 */
    fun loadMoreDynamic() {
        val cur = _dynamic.value
        if (!cur.feed.hasMore) return
        loadDynamicPage(offset = cur.feedOffset)
    }

    private fun loadDynamicPage(offset: String) {
        val cur = _dynamic.value
        if (cur.feed.isLoading || cur.feed.isLoadingMore) return
        val loadingMore = offset.isNotBlank()
        viewModelScope.launch {
            _dynamic.value = cur.feed.let { feed ->
                cur.copy(
                    feed = feed.copy(
                        isLoading = !loadingMore,
                        isLoadingMore = loadingMore,
                        errorMessage = null,
                    ),
                )
            }
            val result = if (cur.selectedUpMid != null) {
                network.repository.dynamicSpace(cur.selectedUpMid, offset)
            } else {
                network.repository.dynamicFeed(cur.typeFilter, offset)
            }
            result
                .onSuccess { pageData ->
                    val base = _dynamic.value.feed
                    _dynamic.value = _dynamic.value.copy(
                        feed = base.copy(
                            items = if (loadingMore) base.items + pageData.entries
                            else pageData.entries,
                            hasMore = pageData.hasMore,
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = null,
                        ),
                        feedOffset = pageData.offset,
                    )
                }
                .onFailure {
                    val base = _dynamic.value.feed
                    _dynamic.value = _dynamic.value.copy(
                        feed = base.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = it.message ?: "加载失败",
                        ),
                    )
                }
        }
    }

    /** 点动态条目：视频动态直接播（走与收藏夹相同的 playBvid 路径）。 */
    fun playDynamic(entry: DynamicEntry) {
        val video = entry.video ?: return
        playBvid(
            bvid = video.bvid,
            title = video.title,
            coverUrl = video.coverUrl,
            author = entry.authorName,
            durationSec = com.biliseek.core.network.parseDurationText(video.durationText),
        )
    }

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
            // 老登录态（升级前登录的）没有用户名/头像，启动时补拉一次（失败不打扰）
            network.loginManager.refreshProfileIfNeeded()
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

    // ── UP 主搜索（M11-D）─────────────────────────────────

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
        viewModelScope.launch {
            _search.value = _search.value.copy(
                userLoading = page == 1,
                userError = null,
            )
            network.repository.searchUsers(keyword, page)
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

    private fun applyUserPage(result: com.biliseek.core.network.BiliRepository.UserSearchPage, keyword: String, append: Boolean) {
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

    // ── UP 空间页（M11-D）─────────────────────────────────

    private val _upSpace = MutableStateFlow(UpSpaceUiState())
    val upSpace: StateFlow<UpSpaceUiState> = _upSpace.asStateFlow()

    /** 点 UP 搜索结果 → 打开空间页（默认投稿视图）。 */
    fun openUpSpace(user: SearchUser) {
        _upSpace.value = UpSpaceUiState(up = user)
        loadUpPosts(1)
    }

    fun closeUpSpace() { _upSpace.value = UpSpaceUiState() }

    /** 投稿 / 动态 视图切换：各视图独立分页状态，互不干扰。 */
    fun setUpSpaceView(view: UpSpaceView) {
        val cur = _upSpace.value
        if (cur.view == view) return
        _upSpace.value = cur.copy(view = view)
        if (view == UpSpaceView.DYNAMIC && cur.dynamic.items.isEmpty() && !cur.dynamic.isLoading) {
            loadUpDynamicPage(offset = "")
        }
    }

    fun loadUpPosts(page: Int) {
        val cur = _upSpace.value
        val up = cur.up ?: return
        if (cur.posts.isLoading || cur.posts.isLoadingMore) return
        val loadingMore = page > 1
        if (loadingMore && !cur.posts.hasMore) return
        viewModelScope.launch {
            _upSpace.value = cur.copy(
                posts = cur.posts.copy(
                    isLoading = !loadingMore,
                    isLoadingMore = loadingMore,
                    errorMessage = null,
                ),
            )
            network.repository.spaceArchives(up.mid, page)
                .onSuccess { result ->
                    val base = _upSpace.value.posts
                    _upSpace.value = _upSpace.value.copy(
                        posts = base.copy(
                            items = if (loadingMore) base.items + result.items else result.items,
                            hasMore = result.hasMore,
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = null,
                        ),
                    )
                }
                .onFailure {
                    val base = _upSpace.value.posts
                    _upSpace.value = _upSpace.value.copy(
                        posts = base.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = it.message ?: "加载失败",
                        ),
                    )
                }
        }
    }

    fun loadMoreUpPosts() {
        val cur = _upSpace.value
        if (!cur.posts.hasMore) return
        loadUpPosts(cur.posts.page + 1)
    }

    private fun loadUpDynamicPage(offset: String) {
        val cur = _upSpace.value
        val up = cur.up ?: return
        if (cur.dynamic.isLoading || cur.dynamic.isLoadingMore) return
        val loadingMore = offset.isNotBlank()
        viewModelScope.launch {
            _upSpace.value = cur.copy(
                dynamic = cur.dynamic.copy(
                    isLoading = !loadingMore,
                    isLoadingMore = loadingMore,
                    errorMessage = null,
                ),
            )
            network.repository.dynamicSpace(up.mid, offset)
                .onSuccess { pageData ->
                    val base = _upSpace.value.dynamic
                    _upSpace.value = _upSpace.value.copy(
                        dynamic = base.copy(
                            items = if (loadingMore) base.items + pageData.entries else pageData.entries,
                            hasMore = pageData.hasMore,
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = null,
                        ),
                        dynamicOffset = pageData.offset,
                    )
                }
                .onFailure {
                    val base = _upSpace.value.dynamic
                    _upSpace.value = _upSpace.value.copy(
                        dynamic = base.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = it.message ?: "加载失败",
                        ),
                    )
                }
        }
    }

    fun loadMoreUpDynamic() {
        val cur = _upSpace.value
        if (!cur.dynamic.hasMore) return
        loadUpDynamicPage(cur.dynamicOffset)
    }

    /** 点 UP 投稿条目：走 playBvid（单 P 直播 / 多 P 整张作队列）。 */
    fun playUpPost(video: SpaceVideo) {
        playBvid(
            bvid = video.bvid,
            title = com.biliseek.core.network.stripHighlight(video.title),
            coverUrl = com.biliseek.core.network.normalizeCoverUrl(video.pic),
            author = video.author,
            durationSec = com.biliseek.core.network.parseDurationText(video.length),
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
