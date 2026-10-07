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
import com.biliseek.core.network.SearchOrder
import com.biliseek.core.network.bvToAv
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.dto.FavoriteFolderItem
import com.biliseek.core.network.dto.SearchUser
import com.biliseek.core.network.dto.SpaceVideo
import com.biliseek.core.network.dto.SeriesItem
import com.biliseek.core.network.SeriesArchivesPage
import android.view.SurfaceView
import android.graphics.Bitmap
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.biliseek.core.player.PlaybackMode
import com.biliseek.core.player.PlaybackPreferences
import com.biliseek.core.player.PlaybackRequest
import com.biliseek.core.player.PlayerConnection
import com.biliseek.core.player.PresentationMode
import com.biliseek.app.data.AppSettings
import com.biliseek.app.data.BiliSeekDatabase
import com.biliseek.app.data.DefaultPresentation
import com.biliseek.app.ui.shouldAutoWatchOnPlayerPage
import com.biliseek.app.data.LibraryRepository
import com.biliseek.app.data.ListenTimeTracker
import com.biliseek.app.data.PlayHistoryEntity
import com.biliseek.app.data.PlaylistEntity
import com.biliseek.app.data.PlaylistTrack
import com.biliseek.app.data.SettingsStore
import com.biliseek.app.data.TrackEntity
import com.biliseek.app.data.VideoGate
import com.biliseek.app.data.defaultVideoQuality
import com.biliseek.app.data.resolveVideoGate
import com.biliseek.app.data.TrackListId
import com.biliseek.app.comment.CommentThreadController
import com.biliseek.app.dynamic.DynamicFeedController
import com.biliseek.app.remote.RemoteLibraryController
import com.biliseek.app.chart.ChartController
import com.biliseek.app.upspace.UpSpaceController
import com.biliseek.app.search.SearchController
import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.DynamicEntry
import com.biliseek.core.network.DynamicType
import com.biliseek.core.network.DynamicItemType
import com.biliseek.core.network.dto.FollowingUp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
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
enum class PendingAction { AddToPlaylist, AppendToQueue, EnqueueNext, Download, Summarize }

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
 * 视频动态 → 搜索结果模型，图文动态（没有视频）返回 null。
 *
 * 与上面同理：长按菜单的加入列表 / 队列 / 下一首 / 下载全部复用搜索结果那套机制。
 */
fun DynamicEntry.toSearchVideo(): SearchVideo? {
    val video = video ?: return null
    return SearchVideo(
        bvid = video.bvid,
        title = video.title,
        author = authorName,
        mid = authorMid,
        duration = video.durationText,
        pic = video.coverUrl,
    )
}

/**
 * 单条多 P 视频的分 P 选择态（Ch3）。**仅正常态三点按钮触发**；批量场景取全 P、不经此路径。
 */
data class MultiPagePickerState(
    val bvid: String,
    val cover: String,
    val author: String,
    val pages: List<PageEntry>,
    val pendingAction: PendingAction,
    /** 视频标题：下载时补在分 P 标题前（[com.biliseek.app.data.downloadTitleFor]）。 */
    val videoTitle: String = "",
)

/**
 * 多 P 面板：点多 P 视频或「查看分 P」时在搜索页展开。`pages` 为空 = 面板关闭。
 * 与搜索结果无关（原先塞在 SearchUiState 里），独立成一份状态。
 */
data class PagePanelState(
    val bvid: String = "",
    val pages: List<PageEntry> = emptyList(),
    /** 分 P 接口不返回封面，只能从点入的搜索结果带过来。 */
    val cover: String = "",
    /** 加歌单 / 下载 / 播放历史落库要用，同封面一起带过来。 */
    val author: String = "",
    /** 视频标题：下载时补在分 P 标题前。 */
    val videoTitle: String = "",
) {
    val isOpen: Boolean get() = pages.isNotEmpty()
}

/** 收藏夹内容条目（可播放形状）。 */
data class FavoriteTrack(
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val durationSec: Long,
    val author: String,
    /** UP 主编号；三点菜单的「查看UP主」要用。 */
    val authorMid: Long = 0,
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
    /** 是当前登录账号本人的空间：不显示关注按钮（见 UpSpacePolicy.isSelf）。 */
    val isSelf: Boolean = false,
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

    /** 「已下载」页右滑 / 三点「删除」后先藏起来的记录（撤销期内不显示，过期才真删）。 */
    private val hiddenDownloads = MutableStateFlow<Set<Long>>(emptySet())

    /** 下载任务列表（含进度 / 失败可重试）；撤销期内删掉的不显示。 */
    val downloadTasks: StateFlow<List<com.biliseek.app.download.DownloadTask>> =
        combine(downloadManager.tasks, hiddenDownloads) { tasks, hidden ->
            if (hidden.isEmpty()) tasks else tasks.filterNot { it.id in hidden }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, downloadManager.tasks.value)

    val authState = network.authGate.state
    val loginState: StateFlow<LoginState> = network.loginManager.state
    val session: StateFlow<BiliSession> = network.sessionStore.session

    /**
     * AI 总结任务队列（工单 AI-5）。它持有 AI 专用的 HTTP 客户端：与 B 站的 client **完全分开**，
     * 不挂任何 B 站拦截器与 Cookie（工单 §3.6；`:core:ai` 内部还会再派生一次隔离的 client 兜底）。
     */
    val summaryManager = com.biliseek.app.summary.SummaryManager.get(app)

    /** 当前播放的视频（这一 P）是否已有总结：播放页「总结」按钮据此显示为已激活。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentHasSummary: StateFlow<Boolean> = connection.state
        .map { s -> s.bvid to (s.resolvedCid.takeIf { it > 0 } ?: s.cid) }
        .distinctUntilChanged()
        .flatMapLatest { (bvid, cid) ->
            if (bvid.isBlank() || cid <= 0) flowOf(false) else summaryManager.hasSummary(bvid, cid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** AI 总结的界面编排：入口 → 风格弹窗 → 阅读页（工单 AI-6）。 */
    val summary = com.biliseek.app.summary.SummaryController(
        scope = viewModelScope,
        manager = summaryManager,
        repository = network.repository,
        session = session,
        emitMessage = ::emitMessage,
    )

    /** 「AI 总结」本地列表：播放列表页里，和「已下载」并列，看得见有哪些、占多少空间、能删。 */
    val summaryLibrary = com.biliseek.app.summary.SummaryLibrary(viewModelScope, summaryManager)

    /** 设置 → AI 总结（工单 AI-3）。 */
    val aiSettings = com.biliseek.app.summary.AiSettingsController(
        scope = viewModelScope,
        store = summaryManager.settings,
        aiHttp = summaryManager.aiHttp,
        emitMessage = ::emitMessage,
    )

    /** 设置 → AI 总结 → 离线识别模型（工单 AI-8）。 */
    val asrModels = com.biliseek.app.summary.AsrModelController(
        models = com.biliseek.app.summary.LocalAsrSource.models(app),
        network = summaryManager.network,
        emitMessage = ::emitMessage,
    )

    /** 瞬时提示（Ch3 批量操作成功等）。UI 侧 collect 后弹 Toast。 */
    private val _transientMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val transientMessage: SharedFlow<String> = _transientMessage.asSharedFlow()

    private fun emitMessage(text: String) { _transientMessage.tryEmit(text) }

    /** 正在删除的收藏夹 mediaId（清空视频 + 删文件夹的整段过程）。非空 = 忙碌。 */
    private val _deletingFavoriteMediaId = MutableStateFlow<Long?>(null)
    val deletingFavoriteMediaId: StateFlow<Long?> = _deletingFavoriteMediaId.asStateFlow()

    /** 复制链接后的一次性提示（Toast），走既有 [transientMessage] 管道。 */
    fun notifyLinkCopied() = emitMessage("链接已复制")

    /** 界面层（如 SAF 导出回调）需要提示时用。 */
    fun notifyMessage(text: String) = emitMessage(text)

    // ── Ch5 MiniPlayer 状态 ─────────────────────────

    /** 播放列表 sheet 是否打开。 */
    private val _queueSheetOpen = MutableStateFlow(false)
    val queueSheetOpen: StateFlow<Boolean> = _queueSheetOpen.asStateFlow()

    fun openQueueSheet() { _queueSheetOpen.value = true }
    fun closeQueueSheet() { _queueSheetOpen.value = false }

    /**
     * 可撤销的队列变动提示（清空、右滑删除、点击替换掉整张列表）。UI 弹带「撤销」的 Snackbar，
     * 点了调 [undoQueueChange]。与 [transientMessage] 分开：那条管道是 Toast，没有按钮。
     */
    private val _queueUndo = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val queueUndo: SharedFlow<String> = _queueUndo.asSharedFlow()

    /** 一键清空：保留正在播放的那一项，其余全清。只剩一项时是 no-op，不弹提示。 */
    fun clearQueue() {
        if (connection.clearQueue()) _queueUndo.tryEmit("队列已清空")
    }

    /** 队列面板右滑删除单条。正在播的那一项删不掉（面板里那一行本来就滑不动）。 */
    fun removeFromQueue(index: Int) {
        val title = connection.state.value.queue.getOrNull(index)?.title.orEmpty()
        if (connection.removeFromQueue(index)) {
            _queueUndo.tryEmit(if (title.isBlank()) "已移除 1 项" else "已移除「$title」")
        }
    }

    fun undoQueueChange() {
        connection.undoQueueChange()
    }

    /** 首次使用演示（设计稿 2026-10-07-first-use-hints）。界面经 LocalHints 取用。 */
    val hints = com.biliseek.app.hint.HintController(com.biliseek.app.hint.SharedPrefsHintRecords(app))

    /** 设置页改 minibar 风格：直接落库，UI 订阅 settings.miniBarStyle 即时切换。 */
    fun setMiniBarStyle(style: com.biliseek.app.data.MiniBarStyle) = viewModelScope.launch {
        settingsStore.setMiniBarStyle(style)
    }

    /** 设置页改深色模式：落库后 MainActivity 订阅 settings.themeMode 即时换主题。 */
    fun setThemeMode(mode: com.biliseek.app.data.ThemeMode) = viewModelScope.launch {
        settingsStore.setThemeMode(mode)
    }

    // ── 表情（面板 + 进程级字典）：见 EmoteController ──

    val emotes: com.biliseek.app.emote.EmoteController = com.biliseek.app.emote.EmoteController(
        scope = viewModelScope,
        repository = network.repository,
    )

    // ── Ch4 状态 ──────────────────────────────────────

    /** 动态：关注流 + 卡片互动（评论对话框在 `dynamics.comments`）。 */
    val dynamics: DynamicFeedController = DynamicFeedController(
        scope = viewModelScope,
        repository = network.repository,
        library = library,
        session = session,
        emitMessage = ::emitMessage,
        patchOtherEntry = { idStr, transform -> upSpace.patchDynamicEntry(idStr, transform) },
        findOtherEntry = { idStr -> upSpace.state.value.dynamic.items.firstOrNull { it.idStr == idStr } },
    )

    /** 图文动态图片全屏预览。null=关闭。 */
    private val _previewImage = MutableStateFlow<ImagePreviewState?>(null)
    val previewImage: StateFlow<ImagePreviewState?> = _previewImage.asStateFlow()

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

    // ── B 站远程列表（收藏夹 / 稍后再看 / 合集）：见 RemoteLibraryController ──

    val remoteLibrary: RemoteLibraryController = RemoteLibraryController(
        scope = viewModelScope,
        repository = network.repository,
        library = library,
        session = session,
        playBvid = { bvid, title, coverUrl, author, durationSec ->
            playBvid(bvid, title, coverUrl, author, durationSec)
        },
        playList = { items, startIndex, loadMore ->
            viewModelScope.launch {
                connection.playFromContext(items, startIndex, expandParts = true, loadMore = loadMore)
            }
        },
        notify = { emitMessage(it) },
    )

    // ── 首页榜单（每周必看 / 入站必刷 / 热歌榜）：见 ChartController ──

    val charts: ChartController = ChartController(
        scope = viewModelScope,
        repository = network.repository,
        playList = { items, startIndex, loadMore ->
            viewModelScope.launch {
                connection.playFromContext(items, startIndex, expandParts = true, loadMore = loadMore)
            }
        },
    )

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

    /** 日历史页那天的真实收听时长；那天没有记录（开始统计之前）为 null。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dayListenedMs: StateFlow<Long?> = _dayHistoryDate
        .flatMapLatest { date ->
            if (date == null) flowOf(null) else library.observeListenedMs(date.toEpochDay())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 真实收听时长：只在正在播放时计时，按本地日期落库（日历史页的「当日听歌时长」）。 */
    private val listenTimeTracker = ListenTimeTracker(
        scope = viewModelScope,
        isPlaying = connection.state.map { it.isPlaying },
        record = { library.addListenedTime(it.epochDay, it.ms) },
    )

    fun openDayHistory(date: LocalDate) { _dayHistoryDate.value = date }
    fun closeDayHistory() { _dayHistoryDate.value = null }

    /** 日历史页点条目：与「最近播放」同一条播放路径。 */
    fun playDayHistoryEntry(entry: PlayHistoryEntity) = playHistoryEntry(entry)

    /**
     * 取分 P，换算成可直接播的请求。单 P 只补上 cid、保留视频标题；多 P 每 P 用分 P 标题。
     * 失败返回 null，播放内核按单 P 播。
     */
    private suspend fun expandParts(
        request: PlaybackRequest.Bili,
    ): List<PlaybackRequest.Bili>? {
        val pages = network.repository.pages(request.bvid).getOrNull() ?: return null
        if (pages.size <= 1) {
            val cid = pages.firstOrNull()?.cid?.takeIf { it > 0 } ?: return null
            return listOf(request.copy(cid = cid))
        }
        return pages.map {
            request.copy(
                cid = it.cid,
                title = it.part.ifBlank { request.title },
                durationSec = it.duration,
            )
        }
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
                        connection.playFromContext(
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
                    }
                }
                .onFailure { /* 失败静默：取流层会有自己的错误呈现 */ }
        }
    }

    // ── 动态（M11-C）：见 DynamicFeedController ──

    /** 计一次 UP 访问。失败静默：不影响主流程。 */
    private fun recordUpVisit(mid: Long) {
        if (mid <= 0) return
        viewModelScope.launch { library.recordUpVisit(mid) }
    }

    /** 从关注列表页点击 UP 主 → 打开 UP 空间页。 */
    fun openUpSpaceFromFollowing(up: FollowingUp) {
        openUpSpace(
            SearchUser(mid = up.mid, uname = up.uname, upic = up.face)
        )
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

    fun createPlaylist(name: String, icon: String) = viewModelScope.launch { library.createPlaylist(name, icon) }

    /** 改列表的名字和图标；正开着这个列表时，内页标题跟着更新。 */
    fun editPlaylist(id: Long, name: String, icon: String) = viewModelScope.launch {
        library.editPlaylist(id, name, icon)
        _openedPlaylist.value?.takeIf { it.id == id }?.let { _openedPlaylist.value = it.copy(name = name, icon = icon) }
    }
    fun deletePlaylist(id: Long) = viewModelScope.launch {
        library.deletePlaylist(id)
        if (_openedPlaylist.value?.id == id) _openedPlaylist.value = null
    }
    fun openPlaylist(p: PlaylistEntity) { _openedPlaylist.value = p }
    fun closePlaylist() { _openedPlaylist.value = null }
    /** 从开着的本地列表移除一条（右滑 / 三点菜单「删除」）。本地数据直接移除，撤销时按原 sortKey 放回。 */
    fun removeFromPlaylist(item: PlaylistTrack) {
        val playlist = _openedPlaylist.value ?: return
        viewModelScope.launch {
            library.removeFromPlaylist(playlist.id, item.track.id)
            lastPlaylistRemoval = PlaylistRemoval(playlist.id, item.track.id, item.sortKey)
            _playlistRemovalNotice.tryEmit("已从「${playlist.name}」移除")
        }
    }

    /** 撤销最近一次从本地列表的移除（与队列、远程列表一样，只有最近一次能撤销）。 */
    fun undoPlaylistRemoval() {
        val removal = lastPlaylistRemoval ?: return
        lastPlaylistRemoval = null
        viewModelScope.launch { library.restoreToPlaylist(removal.playlistId, removal.trackId, removal.sortKey) }
    }

    private data class PlaylistRemoval(val playlistId: Long, val trackId: Long, val sortKey: Double)
    private var lastPlaylistRemoval: PlaylistRemoval? = null

    /** 「已从…移除」：UI 弹带「撤销」的提示，点了调 [undoPlaylistRemoval]。 */
    private val _playlistRemovalNotice = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playlistRemovalNotice: SharedFlow<String> = _playlistRemovalNotice.asSharedFlow()

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
        _selectedBvids.value = search.state.value.items.map { it.bvid }.toSet()
    }

    fun clearSelection() { _selectedBvids.value = emptySet() }

    private fun selectedVideos(): List<SearchVideo> {
        val sel = _selectedBvids.value
        return search.state.value.items.filter { it.bvid in sel }
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

    private fun PlaybackRequest.Bili.toDownloadTarget() = com.biliseek.app.ui.DownloadTarget(
        bvid = bvid,
        cid = cid ?: 0,
        title = title,
        coverUrl = coverUrl,
        author = author,
    )

    /** 下载动作的分 P 请求：标题前补上视频标题（「已下载」里才分得清是哪个视频）。 */
    private fun List<PlaybackRequest.Bili>.forDownload(action: PendingAction, videoTitle: String) =
        if (action == PendingAction.Download) {
            map { it.copy(title = com.biliseek.app.data.downloadTitleFor(videoTitle, it.title)) }
        } else {
            this
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
            // 先弹下载对话框选内容与位置（选一次对全部生效），确认后才入队
            PendingAction.Download -> {
                _pendingDownload.value = requests.map { it.toDownloadTarget() }
            }
            PendingAction.Summarize -> summary.open(requests.map { it.toSummaryVideo() })
        }
    }

    private fun PlaybackRequest.Bili.toSummaryVideo() = com.biliseek.app.summary.SummaryVideo(
        bvid = bvid,
        cid = cid ?: 0,
        title = title,
        author = author,
        upMid = authorMid,
        coverUrl = coverUrl,
        durationMs = durationSec * 1000,
    )

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
    fun selectAllPages() = pageSelection.selectAll(_pagePanel.value.pages.map { it.cid.toString() })
    fun clearPageSelection() = pageSelection.clear()
    fun exitPageSelection() = pageSelection.exit()

    /** 多 P 条目 → 播放请求：标题带 P 序号，封面 / UP 主从面板头部状态带。 */
    private fun PageEntry.toPageRequest(): PlaybackRequest.Bili {
        val p = _pagePanel.value
        return PlaybackRequest.Bili(
            bvid = p.bvid,
            cid = cid,
            title = "P$page $title".trim(),
            coverUrl = p.cover,
            author = p.author,
            durationSec = durationSec,
        )
    }

    /** 多 P 面板批量动作：选中 P（保持自然顺序）执行；执行前退出复选。 */
    fun runPageBatch(action: PendingAction) = viewModelScope.launch {
        val sel = pageSelection.selected.value
        val chosen = _pagePanel.value.pages.filter { it.cid.toString() in sel }
        pageSelection.exit()
        execute(action, chosen.map { it.toPageRequest() }.forDownload(action, _pagePanel.value.videoTitle))
    }

    /** 多 P 面板单条动作（三点菜单）。面板里每行就是分 P，不再需要挑 P。 */
    fun pageSingleAction(entry: PageEntry, action: PendingAction) {
        execute(action, listOf(entry.toPageRequest()).forDownload(action, _pagePanel.value.videoTitle))
    }

    fun pageVideoUrl(entry: PageEntry) =
        "https://www.bilibili.com/video/${_pagePanel.value.bvid}?p=${entry.page}"

    // ── UP 空间投稿的复选与动作 ──────────────────────────

    fun enterUpSelection(bvid: String) {
        exitSelectionMode()
        pageSelection.exit()
        upSelection.enter(bvid)
    }

    fun toggleUpSelection(bvid: String) = upSelection.toggle(bvid)
    fun selectAllUp() = upSelection.selectAll(upSpace.state.value.posts.items.map { it.bvid })
    fun clearUpSelection() = upSelection.clear()
    fun exitUpSelection() = upSelection.exit()

    private fun selectedUpVideos(): List<SearchVideo> {
        val sel = upSelection.selected.value
        return upSpace.state.value.posts.items.filter { it.bvid in sel }.map { it.toSearchVideo() }
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

    /** 视频动态长按菜单的单条动作：同样映射为搜索结果复用 startSingleAction。图文动态没有视频，忽略。 */
    fun startDynamicSingleAction(entry: DynamicEntry, action: PendingAction) {
        entry.toSearchVideo()?.let { startSingleAction(it, action) }
    }

    fun dismissBatchAdd() { _pendingBatchAdd.value = null }

    fun confirmAddToPlaylist(playlistId: Long) {
        val tracks = _pendingBatchAdd.value ?: return
        _pendingBatchAdd.value = null
        viewModelScope.launch {
            tracks.forEach { library.addToPlaylist(playlistId, it) }
            emitMessage("已加入 ${tracks.size} 项到歌单")
        }
    }

    fun createPlaylistAndAdd(name: String, icon: String) {
        val tracks = _pendingBatchAdd.value ?: return
        _pendingBatchAdd.value = null
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = library.createPlaylist(name, icon)
            tracks.forEach { library.addToPlaylist(id, it) }
            emitMessage("已加入 ${tracks.size} 项到歌单")
        }
    }

    /** 「加入列表」弹窗最上面的「稍后再看」：交给 [RemoteLibraryController.addToWatchLater]。 */
    fun confirmAddToWatchLater() {
        val tracks = _pendingBatchAdd.value ?: return
        _pendingBatchAdd.value = null
        remoteLibrary.addToWatchLater(tracks.map { it.bvid })
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
            if (ok > 0) videoInteraction.markFavored(tracks.map { it.bvid })
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
                    if (ok > 0) videoInteraction.markFavored(tracks.map { it.bvid })
                    when {
                        failed == 0 -> emitMessage("已创建收藏夹并加入 $ok 项")
                        ok == 0 -> emitMessage("收藏夹已创建，但视频加入失败")
                        else -> emitMessage("已创建收藏夹，加入 $ok 项、$failed 项失败")
                    }
                    remoteLibrary.syncFavoriteFolders()
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
                    remoteLibrary.syncFavoriteFolders()
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
                    remoteLibrary.renameOpenedFavorite(mediaId, newTitle)
                    remoteLibrary.syncFavoriteFolders()
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
                        remoteLibrary.syncFavoriteFolders()
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
                videoTitle = cleanTitle(video.title),
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
        execute(picker.pendingAction, requests.forDownload(picker.pendingAction, picker.videoTitle))
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
                _pagePanel.value = PagePanelState(
                    bvid = video.bvid,
                    pages = pages.map { PageEntry(it.cid, it.page, it.part, it.duration) },
                    cover = coverOf(video),
                    author = video.author,
                    videoTitle = cleanTitle(video.title),
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

    init {
        // 下载完成的浮框提示：下载在后台服务里跑，这里只比较任务列表前后两次的状态
        viewModelScope.launch {
            var previous: List<com.biliseek.app.download.DownloadTask>? = null
            downloadManager.tasks.collect { current ->
                previous?.let { prev ->
                    com.biliseek.app.download.DownloadNoticePolicy.completionMessage(prev, current)?.let(::emitMessage)
                }
                previous = current
            }
        }
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
        downloadManager.concurrencyProvider = { settingsStore.current.downloadConcurrency }
        // 本地识别（工单 AI-8）：音频走与下载同一个取流解析器，最低音质档
        summaryManager.localAsrRunner = com.biliseek.app.summary.LocalAsrSource(
            app = app,
            transcriber = com.biliseek.app.summary.LocalAsrSource.transcriber(app),
            settings = summaryManager.settings,
            resolveAudioUrl = { bvid, cid ->
                connection.resolveDownloadSource(bvid, cid.takeIf { it > 0 }, com.biliseek.core.player.AudioQuality.SAVER).audioUrl
            },
        )
        summaryManager.asrModelReady = { asrModels.state.value == com.biliseek.core.asr.ModelState.Ready }
        summaryManager.hasLocalAudio = { bvid, cid ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                library.lookupOffline(bvid, cid) { uri -> fileExists(uri) } != null
            }
        }
        // 取流时现读，用户改完设置下一首即生效
        connection.preferenceProvider = {
            val s = settingsStore.current
            PlaybackPreferences(
                audioQuality = s.audioQuality,
                loudnessNormalization = s.loudnessNormalization,
                videoQuality = s.defaultVideoQuality(isMetered()),
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
        // 列表来源里的视频轮到播放时才取分 P（docs/10）：一次只为真正要播的那一条发请求
        connection.partsExpander = { request -> expandParts(request) }
        // 点击替换掉了整张列表：给一次撤销的机会
        connection.onQueueReplaced = { count -> _queueUndo.tryEmit("已替换队列中的 $count 项") }
        connection.sessionRestorer = { settingsStore.getLastSessionSync() }
        viewModelScope.launch {
            settingsStore.load()
            aiSettings.store.load()
            // 进程被杀前没做完的总结按断点重新入队
            summaryManager.resumePending()
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
                    dynamics.initDynamic(forceRefresh = true)
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

    // ── 搜索 / UP 主搜索：见 SearchController ──

    val search: SearchController = SearchController(
        scope = viewModelScope,
        repository = network.repository,
        library = library,
        play = ::play,
        // 新一轮搜索收起多 P 面板（原先是搜索第 1 页时顺手清掉 SearchUiState.pages）
        onNewSearch = { _pagePanel.value = PagePanelState() },
        openEraPage = charts::openEraPage,
    )

    /** 多 P 面板（搜索页内展开的分 P 列表）。 */
    private val _pagePanel = MutableStateFlow(PagePanelState())
    val pagePanel: StateFlow<PagePanelState> = _pagePanel.asStateFlow()

    /** 取流失败后的「重试」：重放队列里当前这条。 */
    fun retryPlayback() = connection.retry()

    // ── UP 空间页（M11-D）：见 UpSpaceController ──

    val upSpace: UpSpaceController = UpSpaceController(
        scope = viewModelScope,
        repository = network.repository,
        session = session,
        emitMessage = ::emitMessage,
        recordUpVisit = ::recordUpVisit,
        onExitSelection = { upSelection.exit() },
        onFollowChanged = { dynamics.refreshUpChips() },
    )

    /** 打开 UP 空间页。消息中心、动态、关注列表、播放页「查看作者」等多处入口共用。 */
    fun openUpSpace(user: SearchUser) = upSpace.openUpSpace(user)

    // ── 图片预览（动态卡片 / UP 空间页共用） ──

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
                        _pagePanel.value = PagePanelState()
                        play(video.toRequest(cid = pages.firstOrNull()?.cid))
                    } else {
                        _pagePanel.value = PagePanelState(
                            bvid = video.bvid,
                            pages = pages.map {
                                PageEntry(it.cid, it.page, it.part, it.duration)
                            },
                            cover = coverOf(video),
                            author = video.author,
                            videoTitle = cleanTitle(video.title),
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
        val panel = _pagePanel.value
        val all = panel.pages
        val cover = panel.cover
        val author = panel.author
        val index = all.indexOfFirst { it.cid == entry.cid }.coerceAtLeast(0)
        viewModelScope.launch {
            connection.playFromContext(
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
        }
    }

    /**
     * 播放整个本地歌单：整张作为临时块进队列，用户手动加的固定条目排在它之后（docs/10）。
     * 本地条目带着 cid，不需要播到时再展开分 P。
     */
    fun playPlaylist(items: List<PlaylistTrack>, startIndex: Int) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            connection.playFromContext(
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
        }
    }

    fun skipNext() = connection.skipToNext()
    fun skipPrevious() = connection.skipToPrevious()
    fun seekTo(positionMs: Long) = connection.seekTo(positionMs)
    fun setSpeed(speed: Float) = connection.setSpeed(speed)

    /** 播放页切画质：只对本次会话生效，默认值在设置页改。 */
    fun setVideoQuality(qn: Int) = connection.setVideoQuality(qn)

    /** [durationMs] 为 0 表示取消；负数表示「播完当前曲目」。 */
    fun setSleepTimer(durationMs: Long) = when {
        durationMs < 0 -> connection.setSleepTimerEndOfTrack()
        durationMs == 0L -> connection.cancelSleepTimer()
        else -> connection.setSleepTimer(durationMs)
    }

    /** 设置页直接选列表播放模式（与播放页按钮是同一个值）。 */
    fun setPlayMode(mode: com.biliseek.core.player.PlayMode) = connection.setPlayMode(mode)

    /** 切到下一个播放模式，返回新模式（界面拿它弹提示）。 */
    fun cyclePlayMode(): com.biliseek.core.player.PlayMode {
        val next = com.biliseek.app.ui.nextPlayMode(connection.state.value.playMode)
        connection.setPlayMode(next)
        return next
    }

    fun onEnterBackground() = connection.onEnterBackground()

    // ── 下载 ────────────────────────────────────────────

    /** 入队下载。内容（只音频/视频）与位置在调用前由 UI 弹窗定好。 */
    fun enqueueDownloads(requests: List<com.biliseek.app.download.DownloadRequest>) {
        if (requests.isEmpty()) return
        downloadManager.enqueueAll(requests)
        // 与「加入播放队列」同一套浮框提示，否则点了「开始下载」看不出有没有生效
        emitMessage(if (requests.size > 1) "已加入 ${requests.size} 项到下载队列" else "已加入下载队列")
    }

    /** 待确认的下载（选完分 P / 批量选择后弹下载对话框）。null = 没有。 */
    private val _pendingDownload = MutableStateFlow<List<com.biliseek.app.ui.DownloadTarget>?>(null)
    val pendingDownload: StateFlow<List<com.biliseek.app.ui.DownloadTarget>?> = _pendingDownload.asStateFlow()

    fun dismissPendingDownload() { _pendingDownload.value = null }

    fun retryDownload(id: Long) = downloadManager.retry(id)

    fun retryAllFailedDownloads() = downloadManager.retryAllFailed()

    fun clearFailedDownloads() = downloadManager.clearFailed()

    /**
     * 「已下载」页已完成条目右滑 / 三点「删除」（一条，或多 P 组的全部分 P）：先从列表拿掉，
     * 提示里可撤销，过 [DOWNLOAD_REMOVAL_COMMIT_MS] 才真的删（同稍后再看、收藏夹）。[deleteFile] 同 [deleteDownload]。
     */
    fun removeDownloads(tasks: List<com.biliseek.app.download.DownloadTask>, deleteFile: Boolean) {
        if (tasks.isEmpty()) return
        val ids = tasks.map { it.id }.toSet()
        hiddenDownloads.value = hiddenDownloads.value + ids
        val job = viewModelScope.launch {
            kotlinx.coroutines.delay(DOWNLOAD_REMOVAL_COMMIT_MS)
            ids.forEach { downloadManager.delete(it, deleteFile) }
        }
        lastDownloadRemoval = PendingDownloadRemoval(ids, job)
        val first = tasks.first()
        _downloadRemovalNotice.tryEmit(
            if (tasks.size > 1) {
                "已删除「${com.biliseek.app.download.splitPartTitle(first.title).first}」（${tasks.size} 个分 P）"
            } else {
                "已删除「${first.title.ifBlank { "未命名" }}」"
            },
        )
    }

    /** 撤销最近一次「已下载」页的删除：还没提交就放回列表。 */
    fun undoDownloadRemoval() {
        val removal = lastDownloadRemoval ?: return
        lastDownloadRemoval = null
        removal.job.cancel()
        hiddenDownloads.value = hiddenDownloads.value - removal.ids
    }

    private class PendingDownloadRemoval(val ids: Set<Long>, val job: Job)
    private var lastDownloadRemoval: PendingDownloadRemoval? = null
    private val _downloadRemovalNotice = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val downloadRemovalNotice: SharedFlow<String> = _downloadRemovalNotice.asSharedFlow()

    /** 多 P 组的分 P 页点一条：从这一 P 开始播，其余已下载的分 P 作为一块临时条目进队列（同点本地列表）。 */
    fun playDownloadGroup(parts: List<com.biliseek.app.download.DownloadTask>, startIndex: Int) {
        if (parts.isEmpty()) return
        viewModelScope.launch {
            connection.playFromContext(
                parts.map {
                    PlaybackRequest.Bili(
                        bvid = it.bvid,
                        cid = it.cid.takeIf { c -> c > 0 },
                        title = it.title,
                        coverUrl = it.coverUrl,
                        author = it.author,
                        durationSec = it.durationSec,
                    )
                },
                startIndex = startIndex,
            )
        }
    }

    /** 「已下载」页下载中段：暂停 / 继续一条，全部暂停 / 全部继续 / 全部取消。 */
    fun pauseDownload(id: Long) = downloadManager.pause(id)
    fun resumeDownload(id: Long) = downloadManager.resume(id)
    fun pauseAllDownloads() = downloadManager.pauseAll()
    fun resumeAllDownloads() = downloadManager.resumeAll()
    fun cancelAllDownloads() = downloadManager.cancelAll()

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

    /** 「AI 总结」列表一行右侧的播放键：播这份总结对应的视频（这一 P）。 */
    fun playSummaryVideo(group: com.biliseek.app.summary.SummaryGroup) {
        play(
            PlaybackRequest.Bili(
                bvid = group.bvid,
                cid = group.cid.takeIf { it > 0 },
                title = group.title,
                coverUrl = group.coverUrl,
                author = group.author,
                durationSec = group.durationMs / 1000,
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

    /** 设置页改默认音质：以设置为准，清掉播放页的临时选择（下一首起生效）。 */
    fun setAudioQuality(value: com.biliseek.core.player.AudioQuality) =
        viewModelScope.launch {
            settingsStore.setAudioQuality(value)
            connection.clearAudioQualityOverride()
        }

    /** 播放页换音质：只对本次会话生效，当前视频带进度重新取流。 */
    fun setAudioQualityForSession(value: com.biliseek.core.player.AudioQuality) =
        connection.setAudioQuality(value)

    fun setVideoQualityWifi(value: Int) =
        viewModelScope.launch { settingsStore.setVideoQualityWifi(value) }

    fun setVideoQualityMetered(value: Int) =
        viewModelScope.launch { settingsStore.setVideoQualityMetered(value) }

    fun setDownloadConcurrency(value: Int) =
        viewModelScope.launch { settingsStore.setDownloadConcurrency(value) }

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
    /**
     * @param automatic 起播时按「记住上次在看」自动进看模式（不是用户点的）。
     *        只下了音频的本地文件此时不为它联网，留在听（[com.biliseek.app.data.resolveOfflineWatch]）。
     */
    fun requestPresentation(target: PresentationMode, surfaceView: SurfaceView?, automatic: Boolean = false) {
        val isVideo = target != PresentationMode.NONE
        if (!isVideo || target == PresentationMode.PIP) {
            applyPresentation(target, surfaceView)
            return
        }
        // 只下了音频的本地文件没有画面：主动点「看」改为联网播放，没网就提示
        when (com.biliseek.app.data.resolveOfflineWatch(connection.needsOnlineForVideo, isOnline(), automatic)) {
            com.biliseek.app.data.OfflineWatch.STAY_LISTENING -> return
            com.biliseek.app.data.OfflineWatch.NO_NETWORK -> {
                emitMessage("这条只下载了音频，联网后才能看画面")
                return
            }
            com.biliseek.app.data.OfflineWatch.NORMAL, com.biliseek.app.data.OfflineWatch.ONLINE -> Unit
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
        if (target != PresentationMode.NONE && connection.needsOnlineForVideo) {
            // 只下了音频的本地文件：从当前位置改为在线播放才有画面（流量闸门已在 requestPresentation 过）
            connection.watchOnline(target, surfaceView)
        } else {
            connection.setPresentation(target, surfaceView)
        }
        viewModelScope.launch {
            settingsStore.setLastPresentationWasVideo(target != PresentationMode.NONE)
        }
    }

    fun dismissVideoBlocked() { _videoBlocked.value = null }

    /**
     * 离开播放页、回到迷你播放条时自动切成听：撤掉画面。
     * 不写回「上次在看」——这不是用户在播放页上的选择（见 PlayerPagePolicy）。
     */
    fun leaveToListen() {
        connection.setPresentation(PresentationMode.NONE, null)
    }

    /** 打开播放页时：「起播模式：记住上次」开着、上次离开播放页时在看，就自动切回看。 */
    fun onPlayerPageShown(surfaceView: SurfaceView?) {
        val s = settingsStore.current
        val state = connection.state.value
        if (shouldAutoWatchOnPlayerPage(
                s.defaultPresentation,
                s.lastPresentationWasVideo,
                state.presentation,
                hasMedia = state.mode != PlaybackMode.IDLE,
            )
        ) {
            requestPresentation(PresentationMode.INLINE, surfaceView, automatic = true)
        }
    }

    /** 当前有可用网络（只问系统，不发请求）。 */
    private fun isOnline(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 计量网络（移动数据 / 计费热点）判定。策略本身是纯函数，这里只负责问系统。 */
    private fun isMetered(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return false
        return cm.isActiveNetworkMetered
    }

    /** 进 PiP 之前的呈现态。退出 PiP 时回到它（全屏进的就回全屏）。 */
    private var presentationBeforePip: PresentationMode? = null

    /** PiP 进出。PiP 是可见呈现态，视频轨保持启用。 */
    fun onPipChanged(inPip: Boolean) {
        if (inPip) {
            presentationBeforePip = connection.state.value.presentation
            connection.setPresentation(PresentationMode.PIP)
        } else {
            connection.setPresentation(
                com.biliseek.app.ui.resolvePresentationAfterPip(presentationBeforePip),
            )
            presentationBeforePip = null
        }
    }

    fun dismissPages() {
        pageSelection.exit()
        _pagePanel.value = PagePanelState()
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

    val messages: com.biliseek.app.message.MessageCenterController = com.biliseek.app.message.MessageCenterController(
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

    init {
        // 2026-10-05 去掉了「已充电」角标：删掉它存在本地的 UP 名单（DataStore biliseek_charged_ups），删过之后是空操作
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            java.io.File(app.filesDir, "datastore/biliseek_charged_ups.preferences_pb").delete()
        }
    }

    /** 播放页视频互动栏（点赞 / 投币 / 收藏 / 三连）。 */
    val videoInteraction = com.biliseek.app.interaction.VideoInteractionController(
        scope = viewModelScope,
        repository = network.repository,
        session = session,
        emitMessage = ::emitMessage,
    )



    /** 列表三点菜单 / 动态长按的「AI 总结」：单 P 直接进弹窗，多 P 先挑 P。 */
    fun summarizeVideo(video: SearchVideo) = startSingleAction(video, PendingAction.Summarize)

    /** 播放页操作栏的「总结」：总结当前播放的这一 P。 */
    fun summarizeCurrent() {
        val s = connection.state.value
        val track = currentPlayingTrack()
        val cid = s.resolvedCid.takeIf { it > 0 } ?: s.cid
        if (s.bvid.isBlank() || cid <= 0) {
            emitMessage("无法获取视频信息")
            return
        }
        summary.open(
            listOf(
                com.biliseek.app.summary.SummaryVideo(
                    bvid = s.bvid,
                    cid = cid,
                    title = s.title,
                    author = track?.author.orEmpty(),
                    upMid = track?.authorMid ?: 0,
                    coverUrl = s.coverUrl,
                    durationMs = s.durationMs.takeIf { it > 0 } ?: ((track?.durationSec ?: 0) * 1000),
                ),
            ),
        )
    }

    // ── 播放页简介 ────────────────────────────────────────

    /** 简介里点时间点：原地跳转并接着播（暂停中也开始播）。播放器里没有已加载媒体时什么也不做。 */
    fun seekFromDescription(positionMs: Long) {
        connection.seekAndPlay(positionMs)
    }

    /**
     * 简介里点链接 / 视频号：B 站视频应用内播放（与搜索框直达同一条路径），
     * b23 短链先展开（用户点击才发这一次请求）再判断，其余网址交给浏览器。
     */
    fun openDescriptionLink(target: com.biliseek.app.ui.DescLinkTarget) {
        when (target) {
            is com.biliseek.app.ui.DescLinkTarget.Video -> play(PlaybackRequest.Bili(target.bvid, title = target.bvid))
            is com.biliseek.app.ui.DescLinkTarget.Web -> openExternalUrl(target.url)
            is com.biliseek.app.ui.DescLinkTarget.ShortLink -> viewModelScope.launch {
                val finalUrl = network.repository.expandShortLink(target.url).getOrNull()
                val resolved = finalUrl?.let(com.biliseek.app.ui.VideoDescriptionPolicy::classifyUrl)
                if (resolved is com.biliseek.app.ui.DescLinkTarget.Video) {
                    play(PlaybackRequest.Bili(resolved.bvid, title = resolved.bvid))
                } else {
                    // 展不开或落点不是视频：交给浏览器（能展开就用展开后的地址）
                    openExternalUrl(finalUrl ?: target.url)
                }
            }
        }
    }

    private fun openExternalUrl(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { emitMessage("没有可以打开链接的应用") }
    }

    /** 阅读页点时间戳：正在播同一 P 就原地跳，否则从该处开始听（默认只听）。 */
    fun openSummaryTimestamp(positionMs: Long) {
        val target = summary.reader.value ?: return
        val s = connection.state.value
        val currentCid = s.resolvedCid.takeIf { it > 0 } ?: s.cid
        if (com.biliseek.app.summary.TimestampLinkPolicy.shouldSeekInPlace(s.bvid, currentCid, target.bvid, target.cid)) {
            // 暂停中也要接着播；播放器里没有已加载媒体（冷恢复）时返回 false，落到下面的「从该处开始播放」
            if (connection.seekAndPlay(positionMs)) return
        }
        viewModelScope.launch {
            val row = summaryManager.find(target.bvid, target.cid)
            val request = PlaybackRequest.Bili(
                bvid = target.bvid,
                cid = target.cid,
                title = row?.title ?: target.video?.title.orEmpty(),
                coverUrl = row?.coverUrl ?: target.video?.coverUrl.orEmpty(),
                author = row?.author ?: target.video?.author.orEmpty(),
                durationSec = (row?.durationMs ?: 0) / 1000,
                authorMid = row?.upMid ?: 0,
            )
            runCatching { connection.play(request, startPositionOverride = positionMs) }
                .onFailure { emitMessage("播放失败：${it.message ?: "未知错误"}") }
        }
    }

    /** 请求打开 设置 → AI 总结（失败出路「去设置」、弹窗里的「去设置」）。 */
    private val _openAiSettingsRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openAiSettingsRequests: kotlinx.coroutines.flow.SharedFlow<Unit> = _openAiSettingsRequests

    fun requestOpenAiSettings() {
        summary.dismissSheet()
        summary.closeReader()
        _openAiSettingsRequests.tryEmit(Unit)
    }

    /** 阅读页失败横幅 / 通知的出路。 */
    fun onSummaryFailureAction(action: com.biliseek.app.summary.FailureAction, row: com.biliseek.app.data.AiSummaryEntity) {
        when (action) {
            com.biliseek.app.summary.FailureAction.RETRY -> summaryManager.retry(row.id)
            com.biliseek.app.summary.FailureAction.SWITCH_TO_BACKUP -> summaryManager.switchToBackup(row.id)
            com.biliseek.app.summary.FailureAction.TRANSCRIPT_NOTE -> summaryManager.makeTranscriptNote(row.id)
            com.biliseek.app.summary.FailureAction.OPEN_SETTINGS -> requestOpenAiSettings()
            com.biliseek.app.summary.FailureAction.USE_OFFICIAL -> summary.regenerate(row.toSummaryVideo())
        }
    }

    fun regenerateSummary(row: com.biliseek.app.data.AiSummaryEntity) = summary.regenerate(row.toSummaryVideo())

    private fun com.biliseek.app.data.AiSummaryEntity.toSummaryVideo() = com.biliseek.app.summary.SummaryVideo(
        bvid = bvid,
        cid = cid,
        title = title,
        pageTitle = pageTitle,
        author = author,
        upMid = upMid,
        coverUrl = coverUrl,
        durationMs = durationMs,
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

    // ── 弹幕（只在全屏显示） ──

    /** 弹幕开关，持久化在设置里。 */
    val danmakuEnabled: StateFlow<Boolean> = settingsStore.state
        .map { it.danmaku.enabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsStore.current.danmaku.enabled)

    fun toggleDanmaku() {
        viewModelScope.launch { settingsStore.updateDanmaku { it.copy(enabled = !it.enabled) } }
    }

    fun updateDanmakuSettings(transform: (com.biliseek.app.danmaku.DanmakuSettings) -> com.biliseek.app.danmaku.DanmakuSettings) {
        viewModelScope.launch { settingsStore.updateDanmaku(transform) }
    }

    private val danmakuLoader = com.biliseek.app.danmaku.DanmakuLoader(viewModelScope) { cid, segment ->
        network.repository.danmakuSegment(cid, segment)
    }

    /** 全屏层是否在显示画面。由 UI 告知——全屏是界面状态，ViewModel 看不到。 */
    private val danmakuSurfaceVisible = MutableStateFlow(false)

    fun setDanmakuSurfaceVisible(visible: Boolean) {
        danmakuSurfaceVisible.value = visible
    }

    /** 按当前设置过滤、按时间排好序的弹幕，供全屏渲染层直接用。 */
    val danmakuItems: StateFlow<List<com.biliseek.core.network.dto.DanmakuItem>> =
        kotlinx.coroutines.flow.combine(danmakuLoader.items, settingsStore.state) { items, s ->
            com.biliseek.app.danmaku.filterDanmaku(items, s.danmaku)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // 只有「全屏 + 弹幕开 + 看模式」才拉弹幕；纯听、内联、弹幕关时一个请求都不发。
        // 位置按 10 秒分桶去重，避免 250ms 一次的状态推送每次都去算一遍要不要拉。
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                connection.state,
                danmakuEnabled,
                danmakuSurfaceVisible,
            ) { playback, enabled, visible ->
                if (enabled && visible && playback.presentation != PresentationMode.NONE &&
                    playback.resolvedCid > 0L
                ) {
                    Triple(playback.resolvedCid, playback.positionMs / 10_000L, playback.durationMs)
                } else {
                    null
                }
            }
                .distinctUntilChanged()
                .collect { key ->
                    key ?: return@collect
                    val playback = connection.state.value
                    danmakuLoader.update(key.first, playback.positionMs, playback.durationMs)
                }
        }
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
     * 播放页"加入列表"：拼一个 [SearchVideo] 复用搜索页同款链路
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
     * 播放页"查看UP主"：优先用队列条目自带的 authorMid（搜索起播必有）；
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

/** 「已下载」页删除的提交延迟：比撤销提示多留 1 秒（同 RemoteLibraryController）。 */
private const val DOWNLOAD_REMOVAL_COMMIT_MS = com.biliseek.app.remote.RemoteLibraryController.REMOVAL_UNDO_MS + 1_000L

