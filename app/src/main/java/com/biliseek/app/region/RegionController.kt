package com.biliseek.app.region

import com.biliseek.app.remote.ListPlayer
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.ChartItem
import com.biliseek.core.player.PlaybackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 一个子分区的列表（只在内存）。[items] 是原始结果，显示时按 [RegionSettings.hideShort] 过滤。 */
data class RegionListState(
    val items: List<ChartItem> = emptyList(),
    /** 已加载到第几页；0 = 还没加载。 */
    val page: Int = 0,
    val hasMore: Boolean = true,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
)

/** 打开的分区详情页：哪个大类、停在哪个子分区。 */
data class RegionDetailState(val group: RegionGroup, val selectedTid: Int)

/**
 * 分区入口（设计稿：docs/superpowers/specs/2026-10-21-region-entries-design.md）：总览页 / 详情页的打开状态、
 * 每个子分区的列表、加载与播放。列表不落库；时间范围、排序、短视频开关存 DataStore（经 [saveSettings]）。
 * 风控由 [BiliRepository.regionVideos] 的读出口负责；这里不重试、不循环。失败只记错误，等用户点「重试」。
 */
class RegionController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val playList: ListPlayer,
    initialSettings: RegionSettings,
    private val saveSettings: (RegionSettings) -> Unit,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val _overviewOpen = MutableStateFlow(false)
    val overviewOpen: StateFlow<Boolean> = _overviewOpen.asStateFlow()

    private val _detail = MutableStateFlow<RegionDetailState?>(null)
    val detail: StateFlow<RegionDetailState?> = _detail.asStateFlow()

    private val _lists = MutableStateFlow<Map<Int, RegionListState>>(emptyMap())
    val lists: StateFlow<Map<Int, RegionListState>> = _lists.asStateFlow()

    private val _settings = MutableStateFlow(initialSettings)
    val settings: StateFlow<RegionSettings> = _settings.asStateFlow()

    /**
     * 列表代号：时间或排序变了、列表整体清空时加一。每次请求记下发出时的代号，回来时代号变了就整个丢掉
     * （成功、失败都不碰列表）；续播的队列也凭它判断列表是否已换了一批。
     */
    private var generation = 0

    /**
     * 设置在启动时异步读完：宿主收到最新值后调一次，只在没打开过任何列表时生效。
     * 读得太晚（用户已经打开过列表）就不再覆盖——此时界面上的设置已是用户看到并在用的那一份。
     */
    fun onSettingsLoaded(value: RegionSettings) {
        if (_lists.value.isEmpty()) _settings.value = value
    }

    fun openOverview() { _overviewOpen.value = true }

    fun closeOverview() { _overviewOpen.value = false }

    /** 打开 [tid] 所在大类的详情页并停在它上面；没取过就取第 1 页。 */
    fun openDetail(tid: Int) {
        val region = RegionCatalog.byTid(tid) ?: return
        _detail.value = RegionDetailState(region.group, tid)
        ensureLoaded(tid)
    }

    /** 关详情页：从总览进来的回到总览，从快捷入口直接进来的回首页。 */
    fun closeDetail() { _detail.value = null }

    /** 搜索框来了关键词（如外部分享）：分区页都收起。 */
    fun closeAll() {
        _detail.value = null
        _overviewOpen.value = false
    }

    /** 详情页切到 [tid]（点标签或左右滑）。 */
    fun selectTab(tid: Int) {
        val current = _detail.value ?: return
        if (current.selectedTid == tid) return
        _detail.value = current.copy(selectedTid = tid)
        ensureLoaded(tid)
    }

    /** 滚到底：取下一页。正在取、没有下一页、上次失败（等用户点重试）时不动。 */
    fun loadMore(tid: Int) {
        val state = _lists.value[tid] ?: return
        if (state.isLoading || state.isLoadingMore || !state.hasMore || state.errorMessage != null) return
        fetch(tid, state.page + 1)
    }

    /** 点「重试」：没有内容时重取第 1 页，有内容时重取下一页。 */
    fun retry(tid: Int) {
        val state = _lists.value[tid] ?: RegionListState()
        if (state.isLoading || state.isLoadingMore) return
        fetch(tid, if (state.items.isEmpty()) 1 else state.page + 1)
    }

    /**
     * 改时间范围 / 排序 / 短视频开关：存下来。时间或排序变了，清掉所有子分区的列表，当前详情页重取第 1 页；
     * 只改短视频开关不重取（显示时过滤）。
     */
    fun updateSettings(value: RegionSettings) {
        val old = _settings.value
        if (value == old) return
        _settings.value = value
        saveSettings(value)
        val requeryNeeded = value.order != old.order || value.span(today()) != old.span(today())
        if (requeryNeeded) {
            generation++
            _lists.value = emptyMap()
            _detail.value?.let { ensureLoaded(it.selectedTid) }
        }
    }

    /**
     * 点条目：这个子分区当前显示的条目作为临时队列，从这一条开始播（docs/10）；
     * 续播时先补列表里已有、还没进队列的条目，不够再和列表共用同一条取页路径往后取。
     */
    fun play(tid: Int, item: ChartItem) {
        val hideShort = _settings.value.hideShort
        val visible = visibleRegionItems(_lists.value[tid]?.items.orEmpty(), hideShort)
        val index = visible.indexOfFirst { it.bvid == item.bvid }.takeIf { it >= 0 } ?: return
        val gen = generation
        val given = visible.mapTo(HashSet()) { it.bvid }
        playList.play(visible.map { it.toRequest() }, index) { continueQueue(tid, hideShort, gen, given) }
    }

    /**
     * 队列续页（播放器同一来源同时只问一次，返回 null 或空表就不再问）：
     * 列表里有还没给过队列的条目就直接给；没有就往后取页，最多 [QUEUE_PAGE_SCAN_LIMIT] 页
     * （整页都是被过滤掉的短视频时才会多取）。列表正在取（滚到底触发的）就等它取完，不另发一次；
     * 取失败或没有下一页就结束续播，不重试——列表上显示「重试」，由用户决定。
     */
    private suspend fun continueQueue(
        tid: Int,
        hideShort: Boolean,
        gen: Int,
        given: MutableSet<String>,
    ): List<PlaybackRequest>? {
        // 列表还是点播放时那一批：挑出没给过队列的显示条目
        fun takeFresh(): List<PlaybackRequest>? {
            if (generation != gen) return null
            val fresh = visibleRegionItems(_lists.value[tid]?.items.orEmpty(), hideShort)
                .filter { it.bvid !in given }
            if (fresh.isEmpty()) return null
            fresh.mapTo(given) { it.bvid }
            return fresh.map { it.toRequest() }
        }

        repeat(QUEUE_PAGE_SCAN_LIMIT) {
            _lists.first { map -> map[tid]?.let { !it.isLoading && !it.isLoadingMore } ?: true }
            if (generation != gen) return null
            takeFresh()?.let { return it }
            val state = _lists.value[tid] ?: return null
            if (state.errorMessage != null || !state.hasMore) return null
            fetchPage(tid, state.page + 1)
        }
        return takeFresh()
    }

    private fun ensureLoaded(tid: Int) {
        val state = _lists.value[tid]
        if (state == null || (state.page == 0 && !state.isLoading && state.errorMessage == null)) fetch(tid, 1)
    }

    // 不等调度、立刻跑到第一次挂起：「正在取」标记同步打上，连点两下也只发一次
    private fun fetch(tid: Int, page: Int) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { fetchPage(tid, page) }
    }

    /**
     * 取 [tid] 的第 [page] 页——列表和队列续页唯一的请求路径。这个子分区已在取就什么都不做；
     * 回来时列表代号变了（期间改了时间或排序，列表已清空重来）就丢掉结果，成功失败都不碰列表。
     */
    private suspend fun fetchPage(tid: Int, page: Int) {
        val first = page == 1
        var claimed = false
        _lists.update { map ->
            val current = map[tid] ?: RegionListState()
            claimed = !current.isLoading && !current.isLoadingMore
            if (!claimed) map
            else map + (tid to current.copy(isLoading = first, isLoadingMore = !first, errorMessage = null))
        }
        if (!claimed) return
        val gen = generation
        val settings = _settings.value
        val span = settings.span(today())
        val result = repository.regionVideos(
            tid, settings.order.param, page, formatApiDate(span.from), formatApiDate(span.to),
        )
        if (gen != generation) return
        result
            .onSuccess { applyPage(tid, page, it.items, it.totalPages, append = !first) }
            .onFailure { error ->
                _lists.update { map ->
                    val current = map[tid] ?: return@update map
                    map + (tid to current.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        errorMessage = error.message ?: "加载失败",
                    ))
                }
            }
    }

    private fun applyPage(tid: Int, page: Int, incoming: List<ChartItem>, totalPages: Int, append: Boolean) {
        _lists.update { map ->
            val current = map[tid] ?: RegionListState()
            val items = if (append) appendUnseenRegionItems(current.items, incoming) else incoming
            map + (tid to current.copy(
                items = items,
                page = page,
                hasMore = regionHasMore(page, totalPages, incoming.size),
                isLoading = false,
                isLoadingMore = false,
                errorMessage = null,
            ))
        }
    }

    // 条目 → 播放请求。cid 留空：轮到播放时取分 P（与榜单一致）
    private fun ChartItem.toRequest() = PlaybackRequest.Bili(
        bvid = bvid,
        title = title,
        coverUrl = coverUrl,
        author = author,
        durationSec = durationSec,
    )

    private companion object {
        /** 队列续页一次最多往后取几页（整页都被过滤掉时才会连取）。 */
        const val QUEUE_PAGE_SCAN_LIMIT = 3
    }
}
