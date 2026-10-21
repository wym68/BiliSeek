package com.biliseek.app.region

import com.biliseek.app.remote.ListPlayer
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.ChartItem
import com.biliseek.core.player.PlaybackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** 设置在启动时异步读完：宿主收到最新值后调一次，只在没打开过任何列表时生效。 */
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
            _lists.value = emptyMap()
            _detail.value?.let { ensureLoaded(it.selectedTid) }
        }
    }

    /**
     * 点条目：这个子分区当前显示的条目作为临时队列，从这一条开始播（docs/10）；
     * 续页按同样的过滤取下一页，追加到列表里。
     */
    fun play(tid: Int, item: ChartItem) {
        val hideShort = _settings.value.hideShort
        val visible = visibleRegionItems(_lists.value[tid]?.items.orEmpty(), hideShort)
        val index = visible.indexOfFirst { it.bvid == item.bvid }.takeIf { it >= 0 } ?: return
        playList.play(visible.map { it.toRequest() }, index) { loadNextForQueue(tid, hideShort) }
    }

    private suspend fun loadNextForQueue(tid: Int, hideShort: Boolean): List<PlaybackRequest>? {
        val state = _lists.value[tid] ?: return null
        if (!state.hasMore) return null
        val page = state.page + 1
        val span = _settings.value.span(today())
        val result = repository.regionVideos(
            tid, _settings.value.order.param, page, formatApiDate(span.from), formatApiDate(span.to),
        ).getOrNull() ?: return null
        val fresh = result.items.filter { new -> state.items.none { it.bvid == new.bvid } }
        applyPage(tid, page, result.items, result.totalPages, append = true)
        return visibleRegionItems(fresh, hideShort).map { it.toRequest() }
    }

    private fun ensureLoaded(tid: Int) {
        val state = _lists.value[tid]
        if (state == null || (state.page == 0 && !state.isLoading && state.errorMessage == null)) fetch(tid, 1)
    }

    private fun fetch(tid: Int, page: Int) {
        val first = page == 1
        _lists.update { map ->
            val current = map[tid] ?: RegionListState()
            map + (tid to current.copy(isLoading = first, isLoadingMore = !first, errorMessage = null))
        }
        val settings = _settings.value
        val span = settings.span(today())
        scope.launch {
            repository.regionVideos(tid, settings.order.param, page, formatApiDate(span.from), formatApiDate(span.to))
                .onSuccess { result ->
                    // 等待期间改了时间或排序（列表被清空重来）：丢弃这次结果
                    if (settings.order != _settings.value.order || span != _settings.value.span(today())) return@onSuccess
                    applyPage(tid, page, result.items, result.totalPages, append = !first)
                }
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
}
