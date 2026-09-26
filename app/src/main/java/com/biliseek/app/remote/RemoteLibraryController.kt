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
import com.biliseek.core.network.dto.FavoriteMedia
import com.biliseek.core.network.dto.SeriesArchive
import com.biliseek.core.network.dto.WatchLaterItem
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.network.stripHighlight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 按 bvid 起播（单 P 直接播，多 P 整张作队列）。宿主实现；fun interface 以便调用处用具名参数。 */
fun interface BvidPlayer {
    fun play(bvid: String, title: String, coverUrl: String, author: String, durationSec: Long)
}

/**
 * B 站远程列表：收藏夹（目录快照 + 内容分页）、稍后再看、订阅合集（列表 + 内容分页）。
 *
 * 从 AppViewModel 拆出，由 `AppViewModel.remoteLibrary` 持有；生命周期挂在宿主的 [scope] 上。
 * 只有收藏夹「目录」落库（断网可见）；内容一律只在内存里分页，不落库（见 CLAUDE.md）。
 * 点条目起播经 [playBvid] 回到宿主。收藏夹的增删改仍在宿主里，改完调 [syncFavoriteFolders]。
 */
class RemoteLibraryController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val library: LibraryRepository,
    private val session: StateFlow<BiliSession>,
    private val playBvid: BvidPlayer,
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
    fun playWatchLaterItem(item: WatchLaterItem) {
        playBvid.play(
            bvid = item.bvid,
            title = item.title,
            coverUrl = normalizeCoverUrl(item.pic),
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
        playBvid.play(
            bvid = archive.bvid,
            title = archive.title,
            coverUrl = normalizeCoverUrl(archive.cover),
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
        scope.launch {
            val loadingMore = page > 1
            _openedFavorite.value = state.copy(
                isLoading = !loadingMore,
                isLoadingMore = loadingMore,
                errorMessage = null,
            )
            repository.favoriteResources(id.mediaId, page)
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
        playBvid.play(
            bvid = item.bvid,
            title = item.title,
            coverUrl = item.coverUrl,
            author = item.author,
            durationSec = item.durationSec,
        )
    }
}
