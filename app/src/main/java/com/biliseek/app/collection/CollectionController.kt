package com.biliseek.app.collection

import com.biliseek.app.data.CollectedListLoadState
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.data.TrackListId
import com.biliseek.app.remote.ListPlayer
import com.biliseek.app.remote.PagedQueueSource
import com.biliseek.app.remote.QueuePage
import com.biliseek.app.remote.appendUnseen
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.SeriesArchivesPage
import com.biliseek.core.network.dto.CollectedItem
import com.biliseek.core.network.dto.CollectionMediaItem
import com.biliseek.core.network.dto.SeriesArchive
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.player.PlaybackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 合集：我订阅的合集列表、合集内容页（「列表」tab 与 UP 空间各一份）、播放页合集弹层的起播与订阅。
 *
 * 订阅合集部分从 RemoteLibraryController 迁来（设计稿 docs/superpowers/specs/2026-10-26-collections-design.md）。
 * 内容只在内存里分页，不落库。当前视频属于哪个合集不在这里，是 VideoInteractionController 那次 view 请求顺带解析的。
 * 订阅状态变了经 [onSubscriptionChanged] 告诉宿主（同步播放页按钮），提示经 [notify]。
 */
class CollectionController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val session: StateFlow<BiliSession>,
    private val playList: ListPlayer,
    private val notify: (String) -> Unit,
    private val onSubscriptionChanged: (seasonId: Long, subscribed: Boolean) -> Unit,
) {

    // ── 我订阅的合集列表（「列表 → 合集」）──

    private val _collectedList = MutableStateFlow<List<CollectedItem>>(emptyList())
    val collectedList: StateFlow<List<CollectedItem>> = _collectedList.asStateFlow()

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

    // ── 合集内容页（两个槽）──

    private val slots = CollectionSlot.entries.associateWith {
        MutableStateFlow<RemoteListState<SeriesArchive>?>(null)
    }

    /** 「列表」tab 里打开的合集内容页。 */
    val openedInLibrary: StateFlow<RemoteListState<SeriesArchive>?> =
        slots.getValue(CollectionSlot.LIBRARY).asStateFlow()

    /** UP 空间页上打开的合集 / 系列内容页。 */
    val openedInUpSpace: StateFlow<RemoteListState<SeriesArchive>?> =
        slots.getValue(CollectionSlot.UP_SPACE).asStateFlow()

    private fun slot(slot: CollectionSlot) = slots.getValue(slot)

    private fun RemoteListState<*>.ref(): CollectionRef? = (id as? TrackListId.Collection)?.ref

    /** 打开合集内容页并取第 1 页。 */
    fun open(slot: CollectionSlot, ref: CollectionRef, title: String) {
        slot(slot).value = RemoteListState(id = TrackListId.Collection(ref), title = title)
        loadPage(slot, 1)
    }

    fun close(slot: CollectionSlot) {
        slot(slot).value = null
    }

    /** 加载内容第 [page] 页。结果回来时页面已换成别的合集就丢掉。 */
    fun loadPage(slot: CollectionSlot, page: Int) {
        val target = slot(slot)
        val state = target.value ?: return
        val ref = state.ref() ?: return
        if (state.isLoading || state.isLoadingMore) return
        if (page > 1 && !state.hasMore) return
        scope.launch {
            val loadingMore = page > 1
            target.value = state.copy(
                isLoading = !loadingMore,
                isLoadingMore = loadingMore,
                errorMessage = null,
            )
            fetchPage(ref, page)
                .onSuccess { result ->
                    val base = target.value ?: return@onSuccess
                    if (base.ref() != ref) return@onSuccess
                    val existing = if (loadingMore) base.items else emptyList()
                    target.value = base.copy(
                        // 列表 key 是 bvid：重复的去掉，免得 LazyColumn 撞 key
                        items = appendUnseen(existing, result.items.distinctBy { it.bvid }, emptySet()) { it.bvid },
                        page = result.page,
                        hasMore = result.hasMore,
                        isLoading = false,
                        isLoadingMore = false,
                    )
                }
                .onFailure {
                    val base = target.value ?: return@onFailure
                    if (base.ref() != ref) return@onFailure
                    target.value = base.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        errorMessage = it.message ?: "加载失败",
                    )
                }
        }
    }

    /**
     * 点合集内容页里的一项：已加载的内容作为临时块，从这一项开始播；播到末尾附近自动续下一页（docs/10）。
     * 续页用自己的页码，不动界面上的列表状态。
     */
    fun play(slot: CollectionSlot, archive: SeriesArchive) {
        val state = slot(slot).value ?: return
        val ref = state.ref() ?: return
        val index = state.items.indexOf(archive)
        if (index < 0) {
            playList.play(listOf(archive.toRequest()), 0, loadMore = null)
            return
        }
        val source = PagedQueueSource(nextPage = state.page + 1, hasMore = state.hasMore) { page ->
            fetchPage(ref, page).map { result -> QueuePage(result.items.map { it.toRequest() }, result.hasMore) }
        }
        playList.play(state.items.map { it.toRequest() }, index, loadMore = source::next)
    }

    /** 不同来源的一页统一成 [SeriesArchivesPage]。UP 空间的条目接口不带 UP 主，用 ref 里的补上。 */
    private suspend fun fetchPage(ref: CollectionRef, page: Int): Result<SeriesArchivesPage> = when (ref) {
        is CollectionRef.Subscribed -> repository.collectionSeasonList(ref.seasonId, page).map { result ->
            SeriesArchivesPage(result.items.map { it.toArchive() }, result.page, result.hasMore)
        }
        is CollectionRef.Season -> repository.spaceSeasonArchives(ref.mid, ref.seasonId, page)
            .map { it.withAuthor(ref.upName, ref.mid) }
        is CollectionRef.Series -> repository.seriesArchives(ref.mid, ref.seriesId, page)
            .map { it.withAuthor(ref.upName, ref.mid) }
    }

    private fun SeriesArchivesPage.withAuthor(name: String, mid: Long) = copy(
        items = items.map {
            it.copy(
                author = it.author.ifBlank { name },
                authorMid = if (it.authorMid > 0) it.authorMid else mid,
            )
        },
    )

    // ── 播放页合集弹层 ──

    /** 合集弹层里点第 [index] 集：整个合集作为临时块，从这一集开始播（带图钉的条目不动，docs/10）。 */
    fun playEpisodes(collection: VideoCollection, index: Int) {
        if (index !in collection.episodes.indices) return
        playList.play(collection.episodes.map { it.toRequest() }, index, loadMore = null)
    }

    // ── 订阅 / 取消订阅 ──

    private val _subscribeBusy = MutableStateFlow(false)

    /** 订阅请求进行中：按钮不能点，再点也不重复发。 */
    val subscribeBusy: StateFlow<Boolean> = _subscribeBusy.asStateFlow()

    /** 取消订阅「列表」tab 里打开的合集（内容页标题右侧「取消订阅」）。 */
    fun unsubscribeOpenedInLibrary() {
        val state = slot(CollectionSlot.LIBRARY).value ?: return
        val ref = state.ref() as? CollectionRef.Subscribed ?: return
        setSubscribed(ref.seasonId, state.title, subscribe = false)
    }

    /**
     * 订阅 / 取消订阅合集。成功后：订阅 → 重新取订阅列表第 1 页；取消 → 列表里有就去掉、
     * 「列表」tab 开着的正是它就关掉；两种都经 [onSubscriptionChanged] 同步播放页。失败只提示，状态不动。
     */
    fun setSubscribed(seasonId: Long, title: String, subscribe: Boolean) {
        if (!session.value.isLoggedIn) {
            notify("请先登录")
            return
        }
        if (_subscribeBusy.value) return
        _subscribeBusy.value = true
        scope.launch {
            try {
                val result = if (subscribe) repository.favSeason(seasonId) else repository.unfavSeason(seasonId)
                result
                    .onSuccess {
                        if (subscribe) loadCollectedList(1) else removeFromCollected(seasonId)
                        onSubscriptionChanged(seasonId, subscribe)
                        notify(if (subscribe) "已订阅「$title」" else "已取消订阅「$title」")
                    }
                    .onFailure {
                        notify("${if (subscribe) "订阅" else "取消订阅"}失败：${it.message ?: "网络错误"}")
                    }
            } finally {
                _subscribeBusy.value = false
            }
        }
    }

    private fun removeFromCollected(seasonId: Long) {
        val before = _collectedList.value
        val after = before.filterNot { it.id == seasonId }
        if (after.size < before.size) {
            _collectedList.value = after
            _collectedListState.value = _collectedListState.value.let {
                it.copy(totalCount = (it.totalCount - 1).coerceAtLeast(0))
            }
        }
        val library = slot(CollectionSlot.LIBRARY)
        if ((library.value?.ref() as? CollectionRef.Subscribed)?.seasonId == seasonId) library.value = null
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

    // 条目 → 播放请求。cid 留空：轮到播放时取分 P，多 P 原地展开（docs/10）
    private fun SeriesArchive.toRequest() = PlaybackRequest.Bili(
        bvid = bvid,
        title = title,
        coverUrl = normalizeCoverUrl(cover),
        author = author,
        durationSec = duration,
        authorMid = authorMid,
    )

    private fun CollectionEpisode.toRequest() = PlaybackRequest.Bili(
        bvid = bvid,
        title = title,
        coverUrl = normalizeCoverUrl(cover),
        author = author,
        durationSec = durationSec,
        authorMid = authorMid,
    )
}
