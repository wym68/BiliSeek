package com.biliseek.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
import com.biliseek.core.network.bvToAv
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.dto.FavoriteFolderItem
import com.biliseek.core.network.dto.FavoriteMedia
import com.biliseek.core.network.dto.SearchUser
import com.biliseek.core.network.dto.SpaceVideo
import com.biliseek.core.network.dto.WatchLaterItem
import com.biliseek.core.network.dto.SeriesItem
import com.biliseek.core.network.dto.SeriesArchive
import com.biliseek.core.network.dto.CollectedItem
import com.biliseek.core.network.SeriesArchivesPage
import com.biliseek.core.network.parseSearchInput
import android.view.SurfaceView
import android.graphics.Bitmap
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
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
import com.biliseek.app.comment.CommentThreadController
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.data.CollectedListLoadState
import com.biliseek.app.data.DynamicExpandState
import com.biliseek.app.data.nextDynamicExpandState
import com.biliseek.app.data.shouldFetchDynamicDetail
import com.biliseek.core.network.DynamicEntry
import com.biliseek.core.network.DynamicType
import com.biliseek.core.network.DynamicItemType
import com.biliseek.core.network.dto.FollowingUp
import com.biliseek.core.network.dto.ReplyItem
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 一个分 P。多 P 视频点入后展开成列表。 */
data class PageEntry(val cid: Long, val page: Int, val title: String, val durationSec: Long)

/** 复选模式（Ch3）。搜索结果长按进入，工具栏对选中集批量操作。 */
sealed interface SelectionMode {
    data object Off : SelectionMode
    data class On(val initialBvid: String) : SelectionMode
}

/** 批量 / 单条动作类型（Ch3）。 */
enum class PendingAction { AddToPlaylist, AppendToQueue, EnqueueNext, Download }

/**
 * 通用复选作用域：多 P 面板与 UP 空间投稿各持有一个实例。
 *
 * 搜索结果复选（[SelectionMode]）绑定 bvid 与结果列表，保持不动；
 * 新页面的选择键统一用字符串（多 P 用 cid、UP 空间用 bvid），
 * 由 VM 在 enter 时互斥复位，避免跨页面残留勾选。
 */
class SelectionScope {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    fun enter(initial: String) {
        _active.value = true
        _selected.value = setOf(initial)
    }

    fun exit() {
        _active.value = false
        _selected.value = emptySet()
    }

    fun toggle(key: String) {
        val cur = _selected.value
        _selected.value = if (key in cur) cur - key else cur + key
    }

    /** 全选：只覆盖当前已加载的可见项（与搜索结果复选同一纪律）。 */
    fun selectAll(keys: List<String>) { _selected.value = keys.toSet() }

    fun clear() { _selected.value = emptySet() }
}

/**
 * UP 空间投稿 → 搜索结果模型。
 *
 * 字段一一对应（length 是时长文本、created 是发布时间戳），映射后 UP 空间的
 * 单条动作（歌单 / 队列 / 下载 / 链接）全部复用搜索结果那套机制。
 */
fun SpaceVideo.toSearchVideo(): SearchVideo = SearchVideo(
    bvid = bvid,
    title = title,
    author = author,
    duration = length,
    pic = pic,
    pubdate = created,
    play = play,
)

/**
 * 单条多 P 视频的分 P 选择态（Ch3）。**仅正常态三点按钮触发**；批量场景取全 P、不经此路径。
 */
data class MultiPagePickerState(
    val bvid: String,
    val cover: String,
    val author: String,
    val pages: List<PageEntry>,
    val pendingAction: PendingAction,
)

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
    /** 关注状态：null=未拉取，true=已关注，false=未关注。乐观更新支持。 */
    val following: Boolean? = null,
    /** 关注请求 in-flight（按钮 loading）。 */
    val followingLoading: Boolean = false,
)

/** Ch4 图文动态图片全屏预览状态。 */
data class ImagePreviewState(
    val pics: List<String>,
    val initialIndex: Int,
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
    /** 展开的多 P 视频 UP 主。加歌单 / 下载 / 播放历史落库要用，同封面一起带过来。 */
    val pagesAuthor: String = "",
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

    /** 瞬时提示（Ch3 批量操作成功等）。UI 侧 collect 后弹 Toast。 */
    private val _transientMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val transientMessage: SharedFlow<String> = _transientMessage.asSharedFlow()

    private fun emitMessage(text: String) { _transientMessage.tryEmit(text) }

    /** 正在删除的收藏夹 mediaId（清空视频 + 删文件夹的整段过程）。非空 = 忙碌。 */
    private val _deletingFavoriteMediaId = MutableStateFlow<Long?>(null)
    val deletingFavoriteMediaId: StateFlow<Long?> = _deletingFavoriteMediaId.asStateFlow()

    /** 复制链接后的一次性提示（Toast），走既有 [transientMessage] 管道。 */
    fun notifyLinkCopied() = emitMessage("链接已复制")

    // ── Ch5 MiniPlayer 状态 ─────────────────────────

    /** 播放列表 sheet 是否打开。 */
    private val _queueSheetOpen = MutableStateFlow(false)
    val queueSheetOpen: StateFlow<Boolean> = _queueSheetOpen.asStateFlow()

    fun openQueueSheet() { _queueSheetOpen.value = true }
    fun closeQueueSheet() { _queueSheetOpen.value = false }

    fun clearQueue() {
        connection.clearQueue()
        emitMessage("队列已清空")
    }

    /** 设置页改 minibar 风格：直接落库，UI 订阅 settings.miniBarStyle 即时切换。 */
    fun setMiniBarStyle(style: com.biliseek.app.data.MiniBarStyle) = viewModelScope.launch {
        settingsStore.setMiniBarStyle(style)
    }

    // ── Ch4 状态 ──────────────────────────────────────

    /** 动态评论对话框。发表时对卡片评论数乐观 +1，失败回滚。 */
    val dynamicComments = CommentThreadController(
        scope = viewModelScope,
        repository = network.repository,
        session = session,
        emitMessage = ::emitMessage,
        onPostStarted = { s -> patchCommentCount(s.key, +1) },
        onPostFailed = { s -> patchCommentCount(s.key, -1) },
    )

    /** 图文动态图片全屏预览。null=关闭。 */
    private val _previewImage = MutableStateFlow<ImagePreviewState?>(null)
    val previewImage: StateFlow<ImagePreviewState?> = _previewImage.asStateFlow()

    /** 点赞防抖：记录每个 dyn_id_str 最近一次发起请求的时间戳。 */
    private val likeRequestGate = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private var pollJob: Job? = null

    // ── 锁屏封面常量 ─────────────────────────────────

    /**
     * 封面最长边的像素上限。锁屏卡片约 256dp（@xxxhdpi 下 768px），
     * 512px 留余量，JPEG 压缩后 < 30KB，足以覆盖所有设备 / 系统版本。
     */
    private val COVER_ART_MAX_DIM = 512

    /** JPEG 压缩质量。锁屏场景不需要原图质量，85 几乎看不出差别，文件减半。 */
    private val COVER_ART_JPEG_QUALITY = 85

    /** 首页热力图天数：过去一年 ≈ 365 天。窗口可横向滚动，初始滑到右端（今天）。 */
    private val HEATMAP_DAYS = 365

    // ── 本地库 ──────────────────────────────────────────

    val playlists = library.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** playlistId → 条目数。加入列表弹窗的歌单行显示用。 */
    val playlistTrackCounts = library.observePlaylistTrackCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val searchHistory = library.observeSearchHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentPlays = library.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 近 [HEATMAP_DAYS] 天播放热力图，**本地日期**聚合（`java.time` 设备时区）。
     *
     * 旧实现用 SQL `playedAt / 86400000`（UTC 天数）——中国用户凌晨 0–7 点的
     * 播放会归到前一天。这里拉原始记录在 Kotlin 层按 `LocalDate` 分组。
     */
    val heatmap: StateFlow<Map<LocalDate, Int>> = library.observeHistorySince(
        System.currentTimeMillis() - HEATMAP_DAYS * 24 * 3600 * 1000L,
    )
        .map { list ->
            val zone = ZoneId.systemDefault()
            list.groupingBy { Instant.ofEpochMilli(it.playedAt).atZone(zone).toLocalDate() }
                .eachCount()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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
     *
     * 封面策略：B 站 list-all 接口常返回空封面（用户从未给文件夹设置封面），
     * 此时拉一次首页内容，取首条 media 的 cover 作不上去——避免列表里全是占位色块。
     * 每个空封面目录串行打一次「收藏夹内容」接口，N 个文件夹多 N 次请求，
     * 平时几个夹子的话几秒内能完成。
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
                    val now = System.currentTimeMillis()
                    val entities = folders.map { folder ->
                        val cover = folder.cover.ifBlank {
                            // 串行：N 个空封面就 N 次额外请求。
                            // 并发会马上被 B 站限流，不如让用户感觉到一点进度。
                            network.repository.firstMediaCover(folder.id).orEmpty()
                        }
                        FavoriteFolderEntity(
                            mediaId = folder.id,
                            title = folder.title,
                            coverUrl = com.biliseek.core.network.normalizeCoverUrl(cover),
                            mediaCount = folder.mediaCount,
                            syncedAt = now,
                        )
                    }
                    library.replaceFavoriteFolders(entities)
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

    // ── 日历史页（M12）────────────────────────────────────

    /** 当前打开的日历史页日期。null = 未打开。 */
    private val _dayHistoryDate = MutableStateFlow<LocalDate?>(null)
    val dayHistoryDate: StateFlow<LocalDate?> = _dayHistoryDate.asStateFlow()

    /** 日历史页的播放记录（某天的本地日期范围）。 */
    val dayHistory: StateFlow<List<PlayHistoryEntity>> = _dayHistoryDate
        .flatMapLatest { date ->
            if (date == null) flowOf(emptyList())
            else {
                val zone = ZoneId.systemDefault()
                val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                library.observeDayHistory(start, start + 24 * 3600 * 1000L)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openDayHistory(date: LocalDate) { _dayHistoryDate.value = date }
    fun closeDayHistory() { _dayHistoryDate.value = null }

    /** 日历史页点条目：与「最近播放」同一条播放路径。 */
    fun playDayHistoryEntry(entry: PlayHistoryEntity) = playHistoryEntry(entry)

    // ── 稍后再看（M12）───────────────────────────────────

    /** 稍后再看列表状态。 */
    private val _watchLater = MutableStateFlow<RemoteListState<com.biliseek.core.network.dto.WatchLaterItem>?>(null)
    val watchLater: StateFlow<RemoteListState<com.biliseek.core.network.dto.WatchLaterItem>?> = _watchLater.asStateFlow()

    /** 打开稍后再看页：未登录置空（UI 显示登录引导）。 */
    fun openWatchLater() {
        if (!session.value.isLoggedIn) {
            _watchLater.value = RemoteListState(
                id = TrackListId.WatchLater,
                title = "稍后再看",
            )
            return
        }
        // 不预置 isLoading：loadWatchLater 自己会设，预置反而被开头
        // `if (state.isLoading) return` 挡掉——请求永远发不出去（M12 bug）。
        _watchLater.value = RemoteListState(
            id = TrackListId.WatchLater,
            title = "稍后再看",
        )
        loadWatchLater()
    }

    fun closeWatchLater() { _watchLater.value = null }

    fun retryWatchLater() = loadWatchLater()

    private fun loadWatchLater() {
        val state = _watchLater.value ?: return
        if (state.isLoading) return
        viewModelScope.launch {
            _watchLater.value = state.copy(isLoading = true, errorMessage = null)
            network.repository.watchLater()
                .onSuccess { items ->
                    _watchLater.value = _watchLater.value?.copy(
                        items = items,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
                .onFailure {
                    _watchLater.value = _watchLater.value?.copy(
                        isLoading = false,
                        errorMessage = it.message ?: "加载失败",
                    )
                }
        }
    }

    /** 点稍后再看条目：走与收藏夹相同的 playBvid 路径。 */
    fun playWatchLaterItem(item: com.biliseek.core.network.dto.WatchLaterItem) {
        playBvid(
            bvid = item.bvid,
            title = item.title,
            coverUrl = com.biliseek.core.network.normalizeCoverUrl(item.pic),
            author = item.owner?.name.orEmpty(),
            durationSec = item.duration,
        )
    }

    // ── 合集（M13-N）────────────────────────────────

    /** 订阅合集列表（分页）。 */
    private val _collectedList = MutableStateFlow<List<CollectedItem>>(emptyList())
    val collectedList: StateFlow<List<CollectedItem>> = _collectedList.asStateFlow()

    /** 当前打开的合集内容页（分页拉取，不落库）。 */
    private val _openedSeries = MutableStateFlow<RemoteListState<SeriesArchive>?>(null)
    val openedSeries: StateFlow<RemoteListState<SeriesArchive>?> = _openedSeries.asStateFlow()

    /** 订阅合集列表加载状态。 */
    private val _collectedListState = MutableStateFlow(CollectedListLoadState())
    val collectedListState: StateFlow<CollectedListLoadState> = _collectedListState.asStateFlow()

    /** 加载订阅合集列表第 [page] 页。 */
    fun loadCollectedList(page: Int = 1) {
        val mid = session.value.dedeUserId.toLongOrNull() ?: return
        val state = _collectedListState.value
        if (state.isLoading || state.isLoadingMore) return
        if (page > 1 && !state.hasMore) return
        viewModelScope.launch {
            val loadingMore = page > 1
            _collectedListState.value = state.copy(
                isLoading = !loadingMore,
                isLoadingMore = loadingMore,
                errorMessage = null,
            )
            network.repository.collectedList(mid, page)
                .onSuccess { result ->
                    val base = _collectedListState.value
                    _collectedList.value = if (loadingMore) _collectedList.value + result.items else result.items
                    _collectedListState.value = base.copy(
                        page = result.page,
                        hasMore = result.hasMore,
                        totalCount = result.totalCount,
                        isLoading = false,
                        isLoadingMore = false,
                    )
                }
                .onFailure {
                    _collectedListState.value = _collectedListState.value.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        errorMessage = it.message ?: "加载失败",
                    )
                }
        }
    }

    /** 打开合集内容页。 */
    fun openSeries(seriesId: Long, title: String) {
        _openedSeries.value = RemoteListState(
            id = TrackListId.Series(seriesId),
            title = title,
        )
        loadSeriesPage(1)
    }

    fun closeSeries() { _openedSeries.value = null }

    /** 加载合集内容第 [page] 页。 */
    fun loadSeriesPage(page: Int) {
        val state = _openedSeries.value ?: return
        val seriesId = (state.id as? TrackListId.Series)?.seriesId ?: return
        if (state.isLoading || state.isLoadingMore) return
        if (page > 1 && !state.hasMore) return
        viewModelScope.launch {
            val loadingMore = page > 1
            _openedSeries.value = state.copy(
                isLoading = !loadingMore,
                isLoadingMore = loadingMore,
                errorMessage = null,
            )
            network.repository.collectionSeasonList(seriesId, page)
                .onSuccess { result ->
                    val base = _openedSeries.value ?: return@onSuccess
                    val archives = result.items.map { item ->
                        SeriesArchive(
                            bvid = item.bvid,
                            cid = item.id,
                            title = item.title,
                            cover = item.cover,
                            duration = item.duration.toLong(),
                            author = item.upper.name,
                        )
                    }
                    _openedSeries.value = base.copy(
                        items = if (loadingMore) base.items + archives else archives,
                        page = result.page,
                        hasMore = result.hasMore,
                        isLoading = false,
                        isLoadingMore = false,
                    )
                }
                .onFailure {
                    val base = _openedSeries.value ?: return@onFailure
                    _openedSeries.value = base.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        errorMessage = it.message ?: "加载失败",
                    )
                }
        }
    }

    /** 点合集条目：走与收藏夹相同的 playBvid 路径。 */
    fun playSeriesArchive(archive: SeriesArchive) {
        playBvid(
            bvid = archive.bvid,
            title = archive.title,
            coverUrl = com.biliseek.core.network.normalizeCoverUrl(archive.cover),
            author = archive.author,
            durationSec = archive.duration,
        )
    }

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
    fun initDynamic(forceRefresh: Boolean = false) {
        if (!session.value.isLoggedIn) return
        val cur = _dynamic.value
        if (cur.initState is DynamicInitState.Loading) return
        if (!forceRefresh && (cur.upChips.isNotEmpty() || cur.feed.items.isNotEmpty())) return
        viewModelScope.launch {
            _dynamic.value = cur.copy(initState = DynamicInitState.Loading)
            network.repository.followings()
                .onSuccess { ups ->
                    _dynamic.value = _dynamic.value.copy(
                        upChips = mergeUpChips(ups, library.upVisitAll()),
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

    /** 合并关注列表与本地访问记录，产出「最常访问」栏的展示顺序。 */
    private suspend fun mergeUpChips(
        followings: List<FollowingUp>,
        visits: List<com.biliseek.app.data.UpVisitEntity>,
    ): List<FollowingUp> = com.biliseek.app.data.UpVisitPolicy.mergeUpChips(followings, visits)

    /** 计一次 UP 访问。失败静默：不影响主流程。 */
    private fun recordUpVisit(mid: Long) {
        if (mid <= 0) return
        viewModelScope.launch { library.recordUpVisit(mid) }
    }

    /** 「全部 / 仅视频」切换：变化必须重置游标重拉第 1 页。 */
    fun setDynamicTypeFilter(type: String) {
        val cur = _dynamic.value
        if (cur.typeFilter == type) return
        _dynamic.value = cur.copy(typeFilter = type, feed = RemoteListState.empty(), feedOffset = "")
        loadDynamicPage(offset = "")
    }

    /**
 * 按 UP 筛选：null = 回关注流，非 null = 只看该 UP。切换同样重置游标。
 *
 * 再点已选中的 UP = 取消筛选（与 B 站 App 行为一致）。
 */
    fun selectDynamicUp(up: FollowingUp?) {
        val cur = _dynamic.value
        val targetMid = if (up != null && cur.selectedUpMid == up.mid) null else up?.mid
        if (cur.selectedUpMid == targetMid) return
        _dynamic.value = cur.copy(
            selectedUpMid = targetMid,
            feed = RemoteListState.empty(),
            feedOffset = "",
        )
        loadDynamicPage(offset = "")
        // 筛选本身就是一次「访问」：计入最常访问（取消筛选不计）。
        if (up != null && targetMid != null) recordUpVisit(up.mid)
    }

    /** 强制刷新关注列表（无视缓存），关注/取关成功后调用。 */
    private fun refreshUpChips() = viewModelScope.launch {
        network.repository.followings()
            .onSuccess { ups ->
                _dynamic.value = _dynamic.value.copy(
                    upChips = mergeUpChips(ups, library.upVisitAll()),
                )
            }
    }

    /** 从关注列表页点击 UP 主 → 打开 UP 空间页。 */
    fun openUpSpaceFromFollowing(up: FollowingUp) {
        openUpSpace(
            SearchUser(mid = up.mid, uname = up.uname, upic = up.face)
        )
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

    // ── Ch3：复选模式与批量操作 ──────────────────────────

    private val _selectionMode = MutableStateFlow<SelectionMode>(SelectionMode.Off)
    val selectionMode: StateFlow<SelectionMode> = _selectionMode.asStateFlow()

    private val _selectedBvids = MutableStateFlow<Set<String>>(emptySet())
    val selectedBvids: StateFlow<Set<String>> = _selectedBvids.asStateFlow()

    // 多 P 面板 / UP 空间投稿的复选作用域（与搜索结果复选互斥，enter 时互相复位）
    val pageSelection = SelectionScope()
    val upSelection = SelectionScope()

    /** 单条多 P 选择页状态（三点按钮触发）；非空时 UI 渲染 MultiPagePickerScreen。 */
    private val _multiPagePicker = MutableStateFlow<MultiPagePickerState?>(null)
    val multiPagePicker: StateFlow<MultiPagePickerState?> = _multiPagePicker.asStateFlow()

    /**
     * 待加入列表的条目（一组 track，1 或 N）。非空时 UI 弹加入列表选择框。
     * 单条与批量统一走这一条路径。
     */
    private val _pendingBatchAdd = MutableStateFlow<List<TrackEntity>?>(null)
    val pendingBatchAdd: StateFlow<List<TrackEntity>?> = _pendingBatchAdd.asStateFlow()

    fun enterSelectionMode(initialBvid: String) {
        // 搜索结果复选与多 P / UP 空间复选互斥，进入时复位另两套
        pageSelection.exit()
        upSelection.exit()
        _selectionMode.value = SelectionMode.On(initialBvid)
        _selectedBvids.value = setOf(initialBvid)
    }

    fun exitSelectionMode() {
        _selectionMode.value = SelectionMode.Off
        _selectedBvids.value = emptySet()
    }

    fun toggleSelection(bvid: String) {
        val cur = _selectedBvids.value
        _selectedBvids.value = if (bvid in cur) cur - bvid else cur + bvid
    }

    /** 全选：只覆盖点击当时已加载的可见项（定案 Ch3-5）。 */
    fun selectAllVisible() {
        _selectedBvids.value = _search.value.items.map { it.bvid }.toSet()
    }

    fun clearSelection() { _selectedBvids.value = emptySet() }

    private fun selectedVideos(): List<SearchVideo> {
        val sel = _selectedBvids.value
        return _search.value.items.filter { it.bvid in sel }
    }

    /**
     * 把一条搜索结果按**全部分 P**展开为播放请求（Ch3 批量：取全 P、自然顺序）。
     * 取 pages 失败或单 P 时退化为单条（cid 交由取流层解析）。
     */
    private suspend fun expandToRequests(video: SearchVideo): List<PlaybackRequest.Bili> {
        val pages = network.repository.pages(video.bvid).getOrNull().orEmpty()
        if (pages.size <= 1) {
            return listOf(video.toRequest(cid = pages.firstOrNull()?.cid))
        }
return pages.map { p ->
            PlaybackRequest.Bili(
                bvid = video.bvid,
                cid = p.cid,
                title = "${cleanTitle(video.title)} P${p.page} ${p.part}".trim(),
                coverUrl = coverOf(video),
                author = video.author,
                durationSec = p.duration,
                authorMid = video.mid,
            )
        }
    }

    private fun PlaybackRequest.Bili.toTrackEntity() = TrackEntity(
        bvid = bvid,
        cid = cid ?: 0,
        title = title,
        author = author,
        coverUrl = coverUrl,
        durationSec = durationSec,
    )

    /** 批量下载请求：用设置里记住的默认，不弹窗；公共目录需权限、批量不走权限弹窗 → 回退私有。 */
    private fun toBatchDownloadRequest(r: PlaybackRequest.Bili): com.biliseek.app.download.DownloadRequest {
        val settings = settingsStore.current
        val location = when (settings.lastDownloadLocation) {
            com.biliseek.app.data.DownloadLocation.SAF ->
                if (settings.lastDownloadSafTreeUri.isNotBlank()) com.biliseek.app.data.DownloadLocation.SAF
                else com.biliseek.app.data.DownloadLocation.PRIVATE
            com.biliseek.app.data.DownloadLocation.PUBLIC -> com.biliseek.app.data.DownloadLocation.PRIVATE
            else -> com.biliseek.app.data.DownloadLocation.PRIVATE
        }
        return com.biliseek.app.download.DownloadRequest(
            bvid = r.bvid,
            cid = r.cid,
            title = r.title,
            author = r.author,
            coverUrl = r.coverUrl,
            content = com.biliseek.app.data.DownloadContent.AUDIO,
            location = location,
            safTreeUri = if (location == com.biliseek.app.data.DownloadLocation.SAF) settings.lastDownloadSafTreeUri else "",
        )
    }

    /** 对一组请求执行动作（批量 / 单条选完 P 后共用）。 */
    private fun execute(action: PendingAction, requests: List<PlaybackRequest.Bili>) {
        if (requests.isEmpty()) return
        when (action) {
            PendingAction.AppendToQueue -> {
                requests.forEach { connection.appendToQueue(it) }
                emitMessage("已加入 ${requests.size} 项到播放队列")
            }
            // 逆序 enqueueNext：每次都插在当前项之后，逆序入队才能保住自然顺序
            PendingAction.EnqueueNext -> {
                requests.asReversed().forEach { connection.enqueueNext(it) }
                emitMessage("已加入 ${requests.size} 项到下一首播放")
            }
            PendingAction.AddToPlaylist -> {
                _pendingBatchAdd.value = requests.map { it.toTrackEntity() }
            }
            PendingAction.Download -> {
                requests.forEach { downloadManager.enqueue(toBatchDownloadRequest(it)) }
                emitMessage("已加入 ${requests.size} 项到下载队列")
            }
        }
    }

    /** 批量动作：展开选中集全部 P（自然顺序）后执行。 */
    private fun runBatch(action: PendingAction) = viewModelScope.launch {
        val videos = selectedVideos()
        exitSelectionMode()
        val requests = videos.flatMap { expandToRequests(it) }
        execute(action, requests)
    }

    fun appendSelectionToQueue() = runBatch(PendingAction.AppendToQueue)
    fun enqueueSelectionNext() = runBatch(PendingAction.EnqueueNext)
    fun addSelectionToPlaylist() = runBatch(PendingAction.AddToPlaylist)
    fun downloadSelection() = runBatch(PendingAction.Download)

    // ── 多 P 面板的复选与动作（长按进复选 / 三点单条动作）──────────

    fun enterPageSelection(cid: Long) {
        exitSelectionMode()
        upSelection.exit()
        pageSelection.enter(cid.toString())
    }

    fun togglePageSelection(cid: Long) = pageSelection.toggle(cid.toString())
    fun selectAllPages() = pageSelection.selectAll(_search.value.pages.map { it.cid.toString() })
    fun clearPageSelection() = pageSelection.clear()
    fun exitPageSelection() = pageSelection.exit()

    /** 多 P 条目 → 播放请求：标题带 P 序号，封面 / UP 主从面板头部状态带。 */
    private fun PageEntry.toPageRequest(): PlaybackRequest.Bili {
        val s = _search.value
        return PlaybackRequest.Bili(
            bvid = s.pagesOfBvid,
            cid = cid,
            title = "P$page $title".trim(),
            coverUrl = s.pagesCover,
            author = s.pagesAuthor,
            durationSec = durationSec,
        )
    }

    /** 多 P 面板批量动作：选中 P（保持自然顺序）执行；执行前退出复选。 */
    fun runPageBatch(action: PendingAction) = viewModelScope.launch {
        val sel = pageSelection.selected.value
        val chosen = _search.value.pages.filter { it.cid.toString() in sel }
        pageSelection.exit()
        execute(action, chosen.map { it.toPageRequest() })
    }

    /** 多 P 面板单条动作（三点菜单）。面板里每行就是分 P，不再需要挑 P。 */
    fun pageSingleAction(entry: PageEntry, action: PendingAction) {
        execute(action, listOf(entry.toPageRequest()))
    }

    fun pageVideoUrl(entry: PageEntry) =
        "https://www.bilibili.com/video/${_search.value.pagesOfBvid}?p=${entry.page}"

    // ── UP 空间投稿的复选与动作 ──────────────────────────

    fun enterUpSelection(bvid: String) {
        exitSelectionMode()
        pageSelection.exit()
        upSelection.enter(bvid)
    }

    fun toggleUpSelection(bvid: String) = upSelection.toggle(bvid)
    fun selectAllUp() = upSelection.selectAll(_upSpace.value.posts.items.map { it.bvid })
    fun clearUpSelection() = upSelection.clear()
    fun exitUpSelection() = upSelection.exit()

    private fun selectedUpVideos(): List<SearchVideo> {
        val sel = upSelection.selected.value
        return _upSpace.value.posts.items.filter { it.bvid in sel }.map { it.toSearchVideo() }
    }

    /** UP 空间批量动作：选中投稿展开全 P（自然顺序）执行；执行前退出复选。 */
    fun runUpBatch(action: PendingAction) = viewModelScope.launch {
        val videos = selectedUpVideos()
        upSelection.exit()
        execute(action, videos.flatMap { expandToRequests(it) })
    }

    /** UP 空间单条三点动作：映射为搜索结果，复用 startSingleAction（多 P 自动弹挑 P 页）。 */
    fun startUpSingleAction(video: SpaceVideo, action: PendingAction) =
        startSingleAction(video.toSearchVideo(), action)

    fun upVideoUrl(video: SpaceVideo) = videoUrl(video.toSearchVideo())

    fun dismissBatchAdd() { _pendingBatchAdd.value = null }

    fun confirmAddToPlaylist(playlistId: Long) {
        val tracks = _pendingBatchAdd.value ?: return
        _pendingBatchAdd.value = null
        viewModelScope.launch {
            tracks.forEach { library.addToPlaylist(playlistId, it) }
            emitMessage("已加入 ${tracks.size} 项到歌单")
        }
    }

    fun createPlaylistAndAdd(name: String) {
        val tracks = _pendingBatchAdd.value ?: return
        _pendingBatchAdd.value = null
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = library.createPlaylist(name)
            tracks.forEach { library.addToPlaylist(id, it) }
            emitMessage("已加入 ${tracks.size} 项到歌单")
        }
    }

    // ── B 站收藏夹：加入 / 新建 / 编辑 / 删除 ──
    //
    // dealFavorite 的 rid 要 aid，而待加条目（TrackEntity）只带 bvid——
    // 用 core/network 的 bvToAv 本地换算（纯算法、有单测），不额外打网络请求。
    // 多 P 展开后同视频会重复出现，distinct 去重再逐 aid deal。

    /** 把待加条目逐个 deal 进收藏夹；返回 (成功数, 失败数)。 */
    private suspend fun dealTracksToFolder(tracks: List<TrackEntity>, mediaId: Long): Pair<Int, Int> {
        val aids = tracks.mapNotNull { runCatching { bvToAv(it.bvid) }.getOrNull() }.distinct()
        var failed = 0
        aids.forEach { aid ->
            network.repository.dealFavorite(aid, listOf(mediaId), emptyList())
                .onFailure { failed++ }
        }
        return (aids.size - failed) to failed
    }

    /** 加入到已有 B 站收藏夹。 */
    fun confirmAddToFavoriteFolder(mediaId: Long) {
        val tracks = _pendingBatchAdd.value ?: return
        _pendingBatchAdd.value = null
        if (!session.value.isLoggedIn) {
            emitMessage("请先登录后再收藏")
            return
        }
        viewModelScope.launch {
            val (ok, failed) = dealTracksToFolder(tracks, mediaId)
            when {
                failed == 0 -> emitMessage("已加入 $ok 项到收藏夹")
                ok == 0 -> emitMessage("收藏失败：网络错误，请稍后再试")
                else -> emitMessage("已加入 $ok 项，$failed 项失败")
            }
        }
    }

    /** 新建 B 站收藏夹并加入待加条目。 */
    fun createFavoriteFolderAndAdd(title: String, intro: String = "") {
        val tracks = _pendingBatchAdd.value ?: return
        _pendingBatchAdd.value = null
        if (title.isBlank()) return
        if (!session.value.isLoggedIn) {
            emitMessage("请先登录后再收藏")
            return
        }
        viewModelScope.launch {
            network.repository.addFavoriteFolder(title, intro)
                .onSuccess { mediaId ->
                    val (ok, failed) = dealTracksToFolder(tracks, mediaId)
                    when {
                        failed == 0 -> emitMessage("已创建收藏夹并加入 $ok 项")
                        ok == 0 -> emitMessage("收藏夹已创建，但视频加入失败")
                        else -> emitMessage("已创建收藏夹，加入 $ok 项、$failed 项失败")
                    }
                    syncFavoriteFolders()
                }
                .onFailure { emitMessage("创建收藏夹失败：${it.message ?: "未知错误"}") }
        }
    }

    /** 音乐库页新建 B 站收藏夹（不附带加视频）。 */
    fun createFavoriteFolder(title: String, intro: String = "") {
        if (title.isBlank()) return
        viewModelScope.launch {
            network.repository.addFavoriteFolder(title, intro)
                .onSuccess {
                    emitMessage("已创建收藏夹")
                    syncFavoriteFolders()
                }
                .onFailure { emitMessage("创建收藏夹失败：${it.message ?: "未知错误"}") }
        }
    }

    /** 编辑 B 站收藏夹名称。 */
    fun editFavoriteFolder(mediaId: Long, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            network.repository.editFavoriteFolder(mediaId, newTitle)
                .onSuccess {
                    emitMessage("已修改收藏夹")
                    syncFavoriteFolders()
                }
                .onFailure { emitMessage("编辑失败：${it.message ?: "未知错误"}") }
        }
    }

    /** 删除 B 站收藏夹。先把收藏夹内的视频从该收藏夹移除，再删文件夹。 */
    fun deleteFavoriteFolder(mediaId: Long) {
        viewModelScope.launch {
            _deletingFavoriteMediaId.value = mediaId
            try {
                val bvids = collectAllFavoriteBvids(mediaId)
                val aids = bvids.mapNotNull { runCatching { bvToAv(it) }.getOrNull() }.distinct()
                var failed = 0
                aids.forEach { aid ->
                    network.repository.dealFavorite(aid, addIds = emptyList(), delIds = listOf(mediaId))
                        .onFailure { failed++ }
                }
                // 全部失败就不删文件夹
                if (aids.isNotEmpty() && failed == aids.size) {
                    emitMessage("视频清理失败（$failed/${aids.size}），未删除收藏夹")
                    return@launch
                }
                network.repository.deleteFavoriteFolder(listOf(mediaId))
                    .onSuccess {
                        val total = aids.size
                        when {
                            failed == 0 -> emitMessage("已删除收藏夹（清理 $total 个视频）")
                            else -> emitMessage("已删除收藏夹，${total - failed}/$total 个视频已移除")
                        }
                        syncFavoriteFolders()
                    }
                    .onFailure { emitMessage("删除失败：${it.message ?: "未知错误"}") }
            } finally {
                _deletingFavoriteMediaId.value = null
            }
        }
    }

    /** 分页拉取收藏夹内全部视频的 bvid。失败/空页就停。 */
    private suspend fun collectAllFavoriteBvids(mediaId: Long): List<String> {
        val all = mutableListOf<String>()
        var page = 1
        while (true) {
            val r = network.repository.favoriteResources(mediaId, page, pageSize = 20).getOrNull() ?: break
            all += r.items.map { it.bvid }.filter { it.isNotBlank() }
            if (!r.hasMore) break
            page++
        }
        return all
    }

    // ── 单条三点按钮动作：单 P 直接执行；多 P 进选择页挑 P ──

    fun startSingleAction(video: SearchVideo, action: PendingAction) = viewModelScope.launch {
        val pages = network.repository.pages(video.bvid).getOrNull().orEmpty()
        if (pages.size <= 1) {
            execute(action, listOf(video.toRequest(cid = pages.firstOrNull()?.cid)))
        } else {
            _multiPagePicker.value = MultiPagePickerState(
                bvid = video.bvid,
                cover = coverOf(video),
                author = video.author,
                pages = pages.map { PageEntry(it.cid, it.page, it.part, it.duration) },
                pendingAction = action,
            )
        }
    }

    fun dismissMultiPagePicker() { _multiPagePicker.value = null }

    /** 选择页确认：对选中分 P（保持 pages 自然顺序）执行预选动作。 */
    fun confirmMultiPagePicker(selectedCids: List<Long>) {
        val picker = _multiPagePicker.value ?: return
        _multiPagePicker.value = null
        val chosen = picker.pages.filter { it.cid in selectedCids.toSet() }
        val requests = chosen.map { p ->
            PlaybackRequest.Bili(
                bvid = picker.bvid,
                cid = p.cid,
                title = "P${p.page} ${p.title}".trim(),
                coverUrl = picker.cover,
                author = picker.author,
                durationSec = p.durationSec,
            )
        }
        execute(picker.pendingAction, requests)
    }

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
                    pagesAuthor = video.author,
                )
            }
        }
    }

    fun videoUrl(video: SearchVideo) = "https://www.bilibili.com/video/${video.bvid}"

    /** 动态外链（用于「转发=复制链接」，Ch4 转发按钮的新语义）。 */
    fun dynamicUrl(idStr: String) = "https://t.bilibili.com/$idStr"

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
        // 锁屏 / 通知栏封面。拉图 + 压缩 + 缩放到锁屏合适大小 (512px 长边)：
        // - 拉图必须走我们自己的 ImageLoader（Referer 注入）
        // - allowHardware(false)：MediaMetadata 需要软件位图才能序列化跨进程传递
        // - 失败返回 null——锁屏会显示默认音符，与改动前一致
        // - 内部已在 IO 调度器，且 [PlayerConnection] 侧还有 3s 超时兜底
        connection.coverArtProvider = { url -> fetchCoverArtBytes(url) }
        // 播放会话持久化：跨进程重启恢复 minibar
        connection.sessionSaver = { session -> settingsStore.saveLastSessionSync(session) }
        connection.sessionRestorer = { settingsStore.getLastSessionSync() }
        viewModelScope.launch {
            settingsStore.load()
            network.sessionStore.load()
            // 登录态加载后再启动风控种植：GenWebTicket 需要 bili_jct 作 csrf（登录态下空 csrf → -111）
            network.authGate.start()
            // 老登录态（升级前登录的）没有用户名/头像，启动时补拉一次（失败不打扰）
            network.loginManager.refreshProfileIfNeeded()
            connection.connect()
            // 恢复上次播放会话（设置队列和位置，不自动播放）
            connection.restoreSession()
            // 登录态续期。会话已加载后再查，不阻塞 UI，失败也不打扰用户
            val outcome = network.cookieRefresher.refreshIfNeeded()
            android.util.Log.d("BiliSeekRefresh", "启动时检查登录态续期：$outcome")
            // 中断的下载任务捞回队列（续传 / 重试）
            downloadManager.resumePending()
        }
        // 进后台时强制保存播放会话，确保进程被杀前数据已落盘
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                connection.saveSession()
            }
        })
        // 监听登录状态，登录成功后自动刷新动态页关注列表
        viewModelScope.launch {
            session.collect { s ->
                if (s.isLoggedIn) {
                    initDynamic(forceRefresh = true)
                }
            }
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

    /**
     * 拉取封面图并压缩为 JPEG 字节，供 MediaSession 元数据嵌入锁屏 / 通知栏。
     *
     * 为什么必须发生在我们自己的进程：
     * - B 站 CDN（`i0.hdslb.com`）有 Referer 防盗链。系统 SystemUI 进程拉图时不会带 Referer，会 403。
     * - Coil 全局 ImageLoader 已经注入 Referer + UA，可以拿到图。
     *
     * 为什么必须用软件位图（`allowHardware(false)`）：
     * - MediaMetadata 的 artworkData 要**序列化**给系统进程，必须是软件 Bitmap。
     * - 否则部分设备会回退成默认图标，甚至 ANR。
     *
     * 返回 null 表示拿不到——**锁屏会显示默认音符**，与改动前一致。起播不依赖该返回值。
     */
    private suspend fun fetchCoverArtBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        runCatching {
            val request = ImageRequest.Builder(getApplication<Application>())
                .data(url)
                .allowHardware(false)
                .build()
            val result = getApplication<Application>().imageLoader.execute(request)
                as? SuccessResult ?: return@runCatching null
            val bitmap = result.image.toBitmap()
            val maxDim = COVER_ART_MAX_DIM
            val longest = maxOf(bitmap.width, bitmap.height)
            val scaled = if (longest > maxDim) {
                val ratio = maxDim.toFloat() / longest
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else bitmap
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, COVER_ART_JPEG_QUALITY, baos)
            baos.toByteArray().takeIf { it.isNotEmpty() }
        }.getOrNull()
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
        loadFollowingState(user.mid)
        // 如果头像或粉丝数为空，从 API 获取完整信息
        if (user.upic.isBlank() || user.fans == 0L) {
            loadUserInfo(user.mid)
        }
        // 计一次访问（最常访问排序用）。
        recordUpVisit(user.mid)
    }

    /** 获取用户完整信息（头像、粉丝数）。 */
    private fun loadUserInfo(mid: Long) = viewModelScope.launch {
        network.repository.userCard(mid)
            .onSuccess { fullUser ->
                _upSpace.value = _upSpace.value.copy(up = fullUser)
            }
            .onFailure { err ->
                android.util.Log.e("BiliSeekUser", "loadUserInfo failed: ${err.message}")
            }
    }

    fun closeUpSpace() {
        upSelection.exit()
        _upSpace.value = UpSpaceUiState()
    }

    /**
     * 进空间页时拉一次关注状态。`relation()` 未登录会 -101，
     * 由 interceptor 链提前抛成 SearchException；runCatching 兜成 null = 未知。
     */
    private fun loadFollowingState(mid: Long) = viewModelScope.launch {
        android.util.Log.d("BiliSeekRelation", "loadFollowingState: mid=$mid")
        network.repository.relation(mid)
            .onSuccess { stat ->
                android.util.Log.d("BiliSeekRelation", "relation ok: stat=$stat, relationStatus=${stat.relationStatus}, following=${stat.relationStatus == com.biliseek.core.network.dto.RelationStatus.FOLLOWING}")
                val following = stat.relationStatus == com.biliseek.core.network.dto.RelationStatus.FOLLOWING
                _upSpace.value = _upSpace.value.copy(following = following)
            }
            .onFailure { err ->
                android.util.Log.e("BiliSeekRelation", "relation FAILED: ${err.javaClass.simpleName}: ${err.message}", err)
            }
    }

    /** 关注 / 取关：乐观更新，失败回滚。 */
    fun toggleRelation(mid: Long, follow: Boolean) = viewModelScope.launch {
        if (!session.value.isLoggedIn) {
            emitMessage("请先登录后再关注")
            return@launch
        }
        val cur = _upSpace.value
        // 防止双击：true→true 已是最终态，不重复发
        if (cur.followingLoading) return@launch
        // 乐观更新
        _upSpace.value = cur.copy(followingLoading = true, following = follow)
        network.repository.modifyRelation(mid, follow)
            .onSuccess {
                _upSpace.value = _upSpace.value.copy(followingLoading = false)
                emitMessage(if (follow) "已关注" else "已取消关注")
                // 关注/取关成功后刷新动态页的关注列表
                refreshUpChips()
            }
            .onFailure { err ->
                // 回滚 + 报错提示
                _upSpace.value = _upSpace.value.copy(followingLoading = false, following = !follow)
                emitMessage("关注失败：${err.message ?: "未知错误"}")
            }
    }

    // ── Ch4 动态互动（点赞 / 转发 / 评论 / 图片预览） ──

    /**
     * 乐观更新一条动态的 stat.like：用于点赞 +1/-1 双状态。
     * 同步更新关注流（`_dynamic.feed`）与 UP 空间页（`_upSpace.dynamic`）两个 StateFlow。
     */
    private fun patchEntry(idStr: String, transform: (DynamicEntry) -> DynamicEntry) {
        val dyn = _dynamic.value
        val idx = dyn.feed.items.indexOfFirst { it.idStr == idStr }
        if (idx >= 0) {
            val newItems = dyn.feed.items.toMutableList().also { it[idx] = transform(it[idx]) }
            _dynamic.value = dyn.copy(feed = dyn.feed.copy(items = newItems))
        }
        val space = _upSpace.value
        val sIdx = space.dynamic.items.indexOfFirst { it.idStr == idStr }
        if (sIdx >= 0) {
            val newItems = space.dynamic.items.toMutableList().also { it[sIdx] = transform(it[sIdx]) }
            _upSpace.value = space.copy(dynamic = space.dynamic.copy(items = newItems))
        }
    }

    /** 点赞：500ms 内只发一次请求；乐观更新；失败回滚。 */
    fun toggleDynamicLike(idStr: String, like: Boolean) = viewModelScope.launch {
        if (!session.value.isLoggedIn) {
            emitMessage("请先登录后再点赞")
            return@launch
        }
        // 防抖：500ms 闸门
        val now = System.currentTimeMillis()
        val last = likeRequestGate[idStr] ?: 0L
        if (now - last < 500) return@launch
        likeRequestGate[idStr] = now

        val original = snapshotEntry(idStr)
        // 乐观更新：status 反转、count ±1
        patchEntry(idStr) { e ->
            val stat = e.stat ?: return@patchEntry e
            e.copy(stat = stat.copy(like = stat.like.copy(
                count = (stat.like.count + if (like) 1 else -1).coerceAtLeast(0),
                status = like,
            )))
        }
        network.repository.likeDynamic(idStr, like)
            .onSuccess { emitMessage(if (like) "已点赞" else "已取消点赞") }
            .onFailure { err ->
                // 回滚：把 entry 的 stat 还原
                original?.let { orig -> patchEntry(idStr) { _ -> orig } }
                emitMessage("点赞失败：${err.message ?: "未知错误"}")
            }
    }

    /** 取一份 entry 快照（用于失败回滚）。 */
    private fun snapshotEntry(idStr: String): DynamicEntry? {
        _dynamic.value.feed.items.firstOrNull { it.idStr == idStr }?.let { return it }
        return _upSpace.value.dynamic.items.firstOrNull { it.idStr == idStr }
    }

    /** 打开评论对话框：根据 dynamic entry 计算 oid/type，并立即加载第一页。未登录也可读。 */
    fun openCommentDialog(entry: DynamicEntry) {
        val (oid, type) = com.biliseek.core.network.internal.resolveDynamicCommentTargetFromEntry(
            idStr = entry.idStr,
            rawType = entry.rawType,
            drawId = entry.drawId,
            commentOid = entry.commentOid,
            commentType = entry.commentType,
        )
        dynamicComments.open(oid = oid, type = type, key = entry.idStr)
    }

    private fun patchCommentCount(idStr: String, delta: Int) {
        patchEntry(idStr) { e ->
            val stat = e.stat ?: return@patchEntry e
            e.copy(stat = stat.copy(comment = stat.comment.copy(
                count = (stat.comment.count + delta).coerceAtLeast(0),
            )))
        }
    }

    // ── 动态卡片正文原地展开/收起（四级降级拉全文）────────────────

    private val _dynamicExpand = MutableStateFlow<Map<String, DynamicExpandState>>(emptyMap())
    val dynamicExpand: StateFlow<Map<String, DynamicExpandState>> = _dynamicExpand.asStateFlow()

    /**
     * 点"展开/收起"：状态转换交给纯函数 [nextDynamicExpandState]，这里只负责按需
     * 发起网络请求（web detail → opus/detail → desktop → rid，与旧版全文弹层同一路径，
     * repository 层有 LRU 缓存）。按 idStr 独立维护，允许多张卡片同时展开。
     */
    fun toggleDynamicExpand(entry: DynamicEntry) {
        val idStr = entry.idStr
        val current = _dynamicExpand.value[idStr]
        val alreadyFetching = current?.expanded == true && current.entry == null && current.error == null
        val next = nextDynamicExpandState(current)
        _dynamicExpand.value = _dynamicExpand.value + (idStr to next)
        if (alreadyFetching || !shouldFetchDynamicDetail(next)) return
        viewModelScope.launch {
            network.repository.dynamicDetail(idStr, seed = entry)
                .onSuccess { detail ->
                    val cur = _dynamicExpand.value[idStr]
                    if (cur?.expanded == true && cur.entry == null) {
                        _dynamicExpand.value = _dynamicExpand.value + (idStr to cur.copy(entry = detail))
                    }
                }
                .onFailure { e ->
                    val cur = _dynamicExpand.value[idStr]
                    if (cur?.expanded == true && cur.entry == null) {
                        _dynamicExpand.value = _dynamicExpand.value + (idStr to cur.copy(error = e.message ?: "加载失败"))
                    }
                }
        }
    }

    /** 打开图片预览（全屏）。 */
    fun openImagePreview(pics: List<String>, initialIndex: Int = 0) {
        if (pics.isEmpty()) return
        _previewImage.value = ImagePreviewState(pics = pics, initialIndex = initialIndex.coerceIn(0, pics.lastIndex))
    }

    fun dismissImagePreview() { _previewImage.value = null }

    /** 保存当前预览图片到系统相册。 */
    fun saveImageToAlbum(url: String) {
        viewModelScope.launch {
            val result = downloadManager.saveImage(url)
            val ctx = getApplication<Application>()
            if (result.isSuccess) {
                android.widget.Toast.makeText(ctx, "已保存到相册", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(ctx, "保存失败：${result.exceptionOrNull()?.message ?: "未知错误"}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 投稿 / 动态 视图切换：各视图独立分页状态，互不干扰。 */
    fun setUpSpaceView(view: UpSpaceView) {
        // 切视图退出投稿复选：另一视图的列表与勾选集无关
        upSelection.exit()
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
                            pagesAuthor = "",
                        )
                        play(video.toRequest(cid = pages.firstOrNull()?.cid))
                    } else {
                        _search.value = _search.value.copy(
                            pages = pages.map {
                                PageEntry(it.cid, it.page, it.part, it.duration)
                            },
                            pagesOfBvid = video.bvid,
                            pagesCover = coverOf(video),
                            pagesAuthor = video.author,
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
        authorMid = mid,
    )

    private fun coverOf(video: SearchVideo) =
        com.biliseek.core.network.normalizeCoverUrl(video.pic)

    /** 点某个分 P：整张多 P 视频作为队列，从该 P 开始播。 */
    fun playPage(bvid: String, entry: PageEntry) {
        val all = _search.value.pages
        val cover = _search.value.pagesCover
        val author = _search.value.pagesAuthor
        val index = all.indexOfFirst { it.cid == entry.cid }.coerceAtLeast(0)
        viewModelScope.launch {
            connection.playQueue(
                all.map {
                    PlaybackRequest.Bili(
                        bvid,
                        it.cid,
                        it.title,
                        cover,
                        author = author,
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
        pageSelection.exit()
        _search.value = _search.value.copy(pages = emptyList(), pagesOfBvid = "", pagesCover = "", pagesAuthor = "")
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
            // 播放即访问：最常访问排序用。authorMid 未填的旧路径（播放历史/收藏夹）跳过。
            if (request is com.biliseek.core.player.PlaybackRequest.Bili && request.authorMid > 0) {
                recordUpVisit(request.authorMid)
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
        // 进程死亡前强制保存播放会话
        connection.saveSession()
        connection.release()
        super.onCleared()
    }

    // ── 消息中心（私信 + 互动消息）：见 MessageCenterController ──

    val messages = com.biliseek.app.message.MessageCenterController(
        scope = viewModelScope,
        repository = network.repository,
        session = session,
        emitMessage = ::emitMessage,
        play = ::play,
        openUpSpace = ::openUpSpace,
    )

    // ── 播放页：评论区、弹幕开关、分享 ──

    /** 播放页评论区（视频评论 oid=aid, type=1）。 */
    val playerComments = CommentThreadController(
        scope = viewModelScope,
        repository = network.repository,
        session = session,
        emitMessage = ::emitMessage,
    )

    /** 打开播放页评论区对话框。 */
    fun openPlayerComment() {
        val bvid = connection.state.value.bvid
        if (bvid.isBlank()) return
        val aid = com.biliseek.core.network.bvToAv(bvid)
        if (aid <= 0L) {
            emitMessage("无法解析视频 ID")
            return
        }
        playerComments.open(oid = aid, type = 1, key = bvid)
    }

    // ── 全屏弹幕开关（UI 占位） ──

    private val _danmakuEnabled = MutableStateFlow(false)
    val danmakuEnabled: StateFlow<Boolean> = _danmakuEnabled.asStateFlow()

    fun toggleDanmaku() {
        _danmakuEnabled.value = !_danmakuEnabled.value
    }

    // ── 分享 ──

    /** 获取当前播放视频的 B站链接。 */
    fun currentVideoUrl(): String? {
        val bvid = connection.state.value.bvid
        if (bvid.isBlank()) return null
        return "https://www.bilibili.com/video/$bvid"
    }

    // ── 播放页：添加到列表 / 查看作者 ──

    /** 当前播放条目（带 author/authorMid），拿不到就是 null。 */
    private fun currentPlayingTrack(): PlaybackRequest.Bili? {
        val s = connection.state.value
        return s.queue.getOrNull(s.queueIndex) as? PlaybackRequest.Bili
    }

    /**
     * 播放页"添加到列表"：拼一个 [SearchVideo] 复用搜索页同款链路
     * （[startSingleAction] 判断单P/多P，落地到 [AddToListPickerDialog]，
     * 一个弹窗里同时可选 B站收藏夹 / 本地歌单，不需要另外做"合并"）。
     */
    fun openPlayerAddToList() {
        val track = currentPlayingTrack()
        if (track == null || track.bvid.isBlank()) {
            emitMessage("无法获取视频信息")
            return
        }
        startSingleAction(
            SearchVideo(
                bvid = track.bvid,
                title = track.title,
                author = track.author,
                mid = track.authorMid,
                pic = track.coverUrl,
            ),
            PendingAction.AddToPlaylist,
        )
    }

    /**
     * 播放页"查看作者"：优先用队列条目自带的 authorMid（搜索起播必有）；
     * 缺失时（历史记录/收藏夹/本地播放列表起播，未必带 authorMid）现查一次
     * 视频详情兜底。返回 true 才应该真正触发导航。
     */
    suspend fun resolvePlayerAuthor(): Boolean {
        val track = currentPlayingTrack()
        if (track == null || track.bvid.isBlank()) {
            emitMessage("无法获取UP主信息")
            return false
        }
        if (track.authorMid > 0) {
            openUpSpace(SearchUser(mid = track.authorMid, uname = track.author))
            return true
        }
        val owner = network.repository.videoView(track.bvid).getOrNull()?.owner
        if (owner == null || owner.mid <= 0) {
            emitMessage("无法获取UP主信息")
            return false
        }
        openUpSpace(SearchUser(mid = owner.mid, uname = owner.name))
        return true
    }
}
