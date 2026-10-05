package com.biliseek.app.remote

import com.biliseek.app.FavoriteTrack
import com.biliseek.app.SyncState
import com.biliseek.app.data.CollectedListLoadState
import com.biliseek.app.data.FavoriteFolderEntity
import com.biliseek.app.data.LibraryRepository
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.data.TrackListId
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.dto.CollectedItem
import com.biliseek.core.network.dto.CollectionMediaItem
import com.biliseek.core.network.dto.FavoriteMedia
import com.biliseek.core.network.dto.SeriesArchive
import com.biliseek.core.network.dto.WatchLaterItem
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.network.stripHighlight
import com.biliseek.core.player.PlaybackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 按 bvid 起播（单 P 直接播，多 P 整张作队列）。宿主实现；fun interface 以便调用处用具名参数。 */
fun interface BvidPlayer {
    fun play(bvid: String, title: String, coverUrl: String, author: String, durationSec: Long)
}

/**
 * 把一整张列表作为临时块起播（docs/10-播放队列工单.md）。宿主实现。
 * 条目轮到播放时才取分 P；[loadMore] 为分页来源的续页，整张已在手里时为 null。
 */
fun interface ListPlayer {
    fun play(items: List<PlaybackRequest>, startIndex: Int, loadMore: (suspend () -> List<PlaybackRequest>?)?)
}

/**
 * B 站远程列表：收藏夹（目录快照 + 内容分页）、稍后再看、订阅合集（列表 + 内容分页）。
 *
 * 从 AppViewModel 拆出，由 `AppViewModel.remoteLibrary` 持有；生命周期挂在宿主的 [scope] 上。
 * 只有收藏夹「目录」落库（断网可见）；内容一律只在内存里分页，不落库（见 CLAUDE.md）。
 * 点条目起播经 [playBvid] 回到宿主。收藏夹的增删改仍在宿主里，改完调 [syncFavoriteFolders]；
 * 从稍后再看 / 收藏夹移除条目、取消订阅合集在这里（只动这里的列表状态），结果经 [notify] 提示；
 * 「加入列表 → 稍后再看」（[addToWatchLater]）也在这里。
 */
class RemoteLibraryController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val library: LibraryRepository,
    private val session: StateFlow<BiliSession>,
    private val playBvid: BvidPlayer,
    private val playList: ListPlayer,
    /** 一次性提示（宿主的 Toast 管道）。 */
    private val notify: (String) -> Unit,
) {

    // ── 收藏夹目录快照（M11-B）──

    /** 收藏夹目录（音乐库「收藏夹」分区）。落库快照，未登录时为空。 */
    val favoriteFolders: StateFlow<List<FavoriteFolderEntity>> = library.observeFavoriteFolders()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        scope.launch {
            _favoriteSync.value = SyncState.Syncing
            repository.favoriteFolders()
                .onSuccess { folders ->
                    val now = System.currentTimeMillis()
                    val entities = folders.map { folder ->
                        val cover = folder.cover.ifBlank {
                            // 串行：N 个空封面就 N 次额外请求。
                            // 并发会马上被 B 站限流，不如让用户感觉到一点进度。
                            repository.firstMediaCover(folder.id).orEmpty()
                        }
                        FavoriteFolderEntity(
                            mediaId = folder.id,
                            title = folder.title,
                            coverUrl = normalizeCoverUrl(cover),
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
        title = stripHighlight(title),
        coverUrl = normalizeCoverUrl(cover),
        durationSec = duration,
        author = upper?.name.orEmpty(),
        authorMid = upper?.mid ?: 0,
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

    /** 收藏夹改名成功后（宿主调），开着的内容页标题跟着改。 */
    fun renameOpenedFavorite(mediaId: Long, title: String) {
        val state = _openedFavorite.value ?: return
        if (state.favoriteMediaId() == mediaId) _openedFavorite.value = state.copy(title = title)
    }

    private fun RemoteListState<*>.favoriteMediaId(): Long? = (id as? TrackListId.Favorite)?.mediaId

    // ── 稍后再看（M12）───────────────────────────────────

    /** 稍后再看列表状态。 */
    private val _watchLater = MutableStateFlow<RemoteListState<WatchLaterItem>?>(null)
    val watchLater: StateFlow<RemoteListState<WatchLaterItem>?> = _watchLater.asStateFlow()

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
        scope.launch {
            _watchLater.value = state.copy(isLoading = true, errorMessage = null)
            repository.watchLater()
                .onSuccess { items ->
                    _watchLater.value = _watchLater.value?.copy(
                        // 撤销窗口内刷新：刚移除、还没提交的不要又冒出来
                        items = appendUnseen(emptyList(), items, pendingWatchLaterBvids) { it.bvid },
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

    /**
     * 点稍后再看条目：整张稍后再看作为来源，从这一条开始播（docs/10）。
     * 接口本来就一次取回全部，不需要续页。
     */
    fun playWatchLaterItem(item: WatchLaterItem) {
        val items = _watchLater.value?.items.orEmpty()
        val index = items.indexOf(item)
        if (index >= 0) {
            playList.play(items.map { it.toRequest() }, index, loadMore = null)
            return
        }
        playBvid.play(
            bvid = item.bvid,
            title = item.title,
            coverUrl = normalizeCoverUrl(item.pic),
            author = item.owner?.name.orEmpty(),
            durationSec = item.duration,
        )
    }

    /**
     * 加入稍后再看（「加入列表」弹窗最上面那一行；设计稿 docs/superpowers/specs/2026-10-04-add-to-watch-later-design.md）。
     *
     * 稍后再看按视频记、不分 P：同一个视频只发一次。aid 用 bvid 本地换算，不额外请求；
     * 一个一个发，间隔由写请求限速拉开，不重试。稍后再看页每次打开都重新拉，这里不用刷新它。
     */
    fun addToWatchLater(bvids: List<String>) {
        if (!session.value.isLoggedIn) {
            notify("请先登录")
            return
        }
        val distinct = bvids.distinct()
        if (distinct.isEmpty()) return
        scope.launch {
            var ok = 0
            var failed = 0
            var firstError: Throwable? = null
            distinct.forEach { bvid ->
                val aid = resolveAid(0, bvid)
                val result = if (aid == null) {
                    Result.failure(IllegalStateException("找不到视频编号"))
                } else {
                    repository.addToView(aid)
                }
                result
                    .onSuccess { ok++ }
                    .onFailure {
                        failed++
                        if (firstError == null) firstError = it
                    }
            }
            notify(watchLaterAddMessage(ok, failed, firstError))
        }
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
        scope.launch {
            val loadingMore = page > 1
            _collectedListState.value = state.copy(
                isLoading = !loadingMore,
                isLoadingMore = loadingMore,
                errorMessage = null,
            )
            repository.collectedList(mid, page)
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
        scope.launch {
            val loadingMore = page > 1
            _openedSeries.value = state.copy(
                isLoading = !loadingMore,
                isLoadingMore = loadingMore,
                errorMessage = null,
            )
            repository.collectionSeasonList(seriesId, page)
                .onSuccess { result ->
                    val base = _openedSeries.value ?: return@onSuccess
                    val archives = result.items.map { it.toArchive() }
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

    /**
     * 点合集条目：已加载的合集内容作为来源，从这一条开始播；播到末尾附近自动续下一页（docs/10）。
     * 续页用自己的页码，不动界面上的列表状态。
     */
    fun playSeriesArchive(archive: SeriesArchive) {
        val state = _openedSeries.value
        val seriesId = (state?.id as? TrackListId.Series)?.seriesId
        val index = state?.items?.indexOf(archive) ?: -1
        if (state != null && seriesId != null && index >= 0) {
            val source = PagedQueueSource(nextPage = state.page + 1, hasMore = state.hasMore) { page ->
                repository.collectionSeasonList(seriesId, page).map { result ->
                    QueuePage(result.items.map { it.toArchive().toRequest() }, result.hasMore)
                }
            }
            playList.play(state.items.map { it.toRequest() }, index, loadMore = source::next)
            return
        }
        playBvid.play(
            bvid = archive.bvid,
            title = archive.title,
            coverUrl = normalizeCoverUrl(archive.cover),
            author = archive.author,
            durationSec = archive.duration,
        )
    }

    /**
     * 加载收藏夹内容。[page] 为 1 是刷新（从第 1 页替换），大于 1 是往下翻（追加）。
     * 往下翻时不直接用 [page]：删过条目后服务器上的序号前移，改按已加载条数算该取哪一页（[favoriteFetchPage]），
     * 重叠和正等着提交移除的按 bvid 去掉（[appendUnseen]）。
     */
    fun loadFavoritePage(page: Int) {
        val state = _openedFavorite.value ?: return
        val id = state.id as? TrackListId.Favorite ?: return
        if (state.isLoading || state.isLoadingMore) return
        if (page > 1 && !state.hasMore) return
        scope.launch {
            val loadingMore = page > 1
            val fetchPage = if (loadingMore) {
                favoriteFetchPage(state.items.size + pendingFavoriteRemovals(id.mediaId).size, FAVORITE_PAGE_SIZE)
            } else {
                1
            }
            _openedFavorite.value = state.copy(
                isLoading = !loadingMore,
                isLoadingMore = loadingMore,
                errorMessage = null,
            )
            repository.favoriteResources(id.mediaId, fetchPage, FAVORITE_PAGE_SIZE)
                .onSuccess { result ->
                    val base = _openedFavorite.value ?: return@onSuccess
                    val existing = if (loadingMore) base.items else emptyList()
                    val items = appendUnseen(
                        existing,
                        result.items.map { it.toTrack() },
                        pendingFavoriteRemovals(id.mediaId),
                    ) { it.bvid }
                    _openedFavorite.value = base.copy(
                        items = items,
                        page = result.page,
                        // 往下翻却一条新的都没有（别处同时改了这个收藏夹）：停止自动加载，免得底部一直转圈
                        hasMore = result.hasMore && (!loadingMore || items.size > existing.size),
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
        playBvid.play(
            bvid = item.bvid,
            title = item.title,
            coverUrl = item.coverUrl,
            author = item.author,
            durationSec = item.durationSec,
        )
    }

    // ── 移除条目（稍后再看 / 收藏夹内容）、取消订阅合集 ─────────────────
    // 设计稿：docs/superpowers/specs/2026-10-04-list-manage-and-swipe-remove-design.md

    /** 「已从…移除」：宿主弹带「撤销」的提示，点了调 [undoRemoval]。 */
    private val _removalNotice = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val removalNotice: SharedFlow<String> = _removalNotice.asSharedFlow()

    /** 一次移除：先从界面拿掉，[REMOVAL_COMMIT_DELAY_MS] 后才提交；撤销 = 取消提交 + 放回原位，不发请求。 */
    private class PendingRemoval(val restore: () -> Unit) {
        var job: Job? = null
    }

    /** 最近一次还没提交的移除，只有它能撤销（与队列的撤销一致）；更早的照常到点提交。 */
    private var lastRemoval: PendingRemoval? = null

    /** 各收藏夹里正等着提交移除的 bvid：翻页时算服务器上还在的条数、接页时去重都要用。 */
    private val pendingFavoriteBvids = mutableMapOf<Long, MutableSet<String>>()

    /** 稍后再看里正等着提交移除的 bvid：撤销窗口内刷新时不让它们又冒出来。 */
    private val pendingWatchLaterBvids = mutableSetOf<String>()

    private fun pendingFavoriteRemovals(mediaId: Long): Set<String> = pendingFavoriteBvids[mediaId].orEmpty()

    private fun scheduleRemoval(
        notice: String,
        restore: () -> Unit,
        commit: suspend () -> Result<Unit>,
        onCommitted: suspend () -> Unit = {},
    ) {
        val removal = PendingRemoval(restore)
        lastRemoval = removal
        removal.job = scope.launch {
            delay(REMOVAL_COMMIT_DELAY_MS)
            if (lastRemoval === removal) lastRemoval = null
            commit()
                .onSuccess { onCommitted() }
                .onFailure {
                    restore()
                    notify("移除失败：${it.message ?: "网络错误"}")
                }
        }
        _removalNotice.tryEmit(notice)
    }

    /** 撤销最近一次还没提交的移除。 */
    fun undoRemoval() {
        val removal = lastRemoval ?: return
        lastRemoval = null
        removal.job?.cancel()
        removal.restore()
    }

    /** 从稍后再看移除（右滑 / 三点菜单「删除」）。 */
    fun removeWatchLaterItem(item: WatchLaterItem) {
        val state = _watchLater.value ?: return
        val index = state.items.indexOfFirst { it.bvid == item.bvid }
        if (index < 0) return
        _watchLater.value = state.copy(items = state.items.filterIndexed { i, _ -> i != index })
        pendingWatchLaterBvids.add(item.bvid)
        scheduleRemoval(
            notice = "已从稍后再看移除",
            restore = {
                pendingWatchLaterBvids.remove(item.bvid)
                _watchLater.value?.let { s ->
                    _watchLater.value = s.copy(items = restoreAt(s.items, item, index) { it.bvid })
                }
            },
            commit = {
                // 拿不到 aid 就按失败处理：delToView(null) 的意思是「清空所有已看过的」
                val aid = resolveAid(item.aid, item.bvid)
                if (aid == null) Result.failure(IllegalStateException("找不到视频编号")) else repository.delToView(aid)
            },
            onCommitted = { pendingWatchLaterBvids.remove(item.bvid) },
        )
    }

    /** 从当前打开的收藏夹移除（右滑 / 三点菜单「删除」）；提交成功后目录快照里这个收藏夹的条数就地减一。 */
    fun removeFavoriteTrack(track: FavoriteTrack) {
        val state = _openedFavorite.value ?: return
        val mediaId = state.favoriteMediaId() ?: return
        val index = state.items.indexOfFirst { it.bvid == track.bvid }
        if (index < 0) return
        _openedFavorite.value = state.copy(items = state.items.filterIndexed { i, _ -> i != index })
        pendingFavoriteBvids.getOrPut(mediaId) { mutableSetOf() }.add(track.bvid)
        scheduleRemoval(
            notice = "已从「${state.title}」移除",
            restore = {
                pendingFavoriteBvids[mediaId]?.remove(track.bvid)
                val s = _openedFavorite.value
                if (s != null && s.favoriteMediaId() == mediaId) {
                    _openedFavorite.value = s.copy(items = restoreAt(s.items, track, index) { it.bvid })
                }
            },
            commit = {
                val aid = resolveAid(0, track.bvid)
                if (aid == null) {
                    Result.failure(IllegalStateException("找不到视频编号"))
                } else {
                    repository.dealFavorite(aid, addIds = emptyList(), delIds = listOf(mediaId))
                }
            },
            onCommitted = {
                pendingFavoriteBvids[mediaId]?.remove(track.bvid)
                library.decrementFavoriteFolderCount(mediaId)
            },
        )
    }

    /** 取消订阅请求进行中：再点不重复发。 */
    private var unsubscribing = false

    /** 取消订阅当前打开的合集：成功后从合集列表里去掉并关掉内容页；失败只提示，页面不动。 */
    fun unsubscribeOpenedSeries() {
        val state = _openedSeries.value ?: return
        val seasonId = (state.id as? TrackListId.Series)?.seriesId ?: return
        if (unsubscribing) return
        unsubscribing = true
        scope.launch {
            try {
                repository.unfavSeason(seasonId)
                    .onSuccess {
                        _collectedList.value = _collectedList.value.filterNot { it.id == seasonId }
                        _collectedListState.value = _collectedListState.value.let {
                            it.copy(totalCount = (it.totalCount - 1).coerceAtLeast(0))
                        }
                        if ((_openedSeries.value?.id as? TrackListId.Series)?.seriesId == seasonId) {
                            _openedSeries.value = null
                        }
                        notify("已取消订阅「${state.title}」")
                    }
                    .onFailure { notify("取消订阅失败：${it.message ?: "网络错误"}") }
            } finally {
                unsubscribing = false
            }
        }
    }

    private fun CollectionMediaItem.toArchive() = SeriesArchive(
        bvid = bvid,
        cid = id,
        title = title,
        cover = cover,
        duration = duration.toLong(),
        author = upper.name,
        authorMid = upper.mid,
    )

    // 列表条目 → 播放请求。cid 一律留空：轮到播放时取分 P，多 P 原地展开（docs/10）
    private fun WatchLaterItem.toRequest() = PlaybackRequest.Bili(
        bvid = bvid,
        title = title,
        coverUrl = normalizeCoverUrl(pic),
        author = owner?.name.orEmpty(),
        durationSec = duration,
    )

    private fun SeriesArchive.toRequest() = PlaybackRequest.Bili(
        bvid = bvid,
        title = title,
        coverUrl = normalizeCoverUrl(cover),
        author = author,
        durationSec = duration,
    )

    companion object {
        /** 「已移除」撤销提示停留多久（同队列的撤销提示）。 */
        const val REMOVAL_UNDO_MS = 4_000L

        /** 移除多久之后才真正提交：比提示多 1 秒，免得最后一刻点了撤销却已经提交。 */
        private const val REMOVAL_COMMIT_DELAY_MS = REMOVAL_UNDO_MS + 1_000L

        /** 收藏夹内容每页条数（与接口默认一致）；删过条目后的翻页换算要用。 */
        private const val FAVORITE_PAGE_SIZE = 20
    }
}
