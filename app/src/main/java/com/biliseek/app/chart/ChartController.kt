package com.biliseek.app.chart

import com.biliseek.app.remote.ListPlayer
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.ChartIssue
import com.biliseek.core.network.ChartItem
import com.biliseek.core.network.ChartPage
import com.biliseek.core.network.EraGroup
import com.biliseek.core.player.PlaybackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 榜单页的种类：首页快捷入口里的四个榜单，加上搜索框里认出来的活动页。 */
enum class ChartKind(val title: String, val isEraPage: Boolean = false) {
    WEEKLY("每周必看"),
    PRECIOUS("入站必刷"),
    HOT_SONGS("热歌榜"),
    PODCAST("热门播客", isEraPage = true),

    /** 搜索框里认出来的活动页（b23 短链展开，或直接粘贴的网址）；标题用页面自己的 `<title>`。 */
    ERA_PAGE("活动页", isEraPage = true),
}

/** 榜单页状态。只在内存里，不落库。 */
data class ChartState(
    val kind: ChartKind,
    /** 顶栏标题。活动页用页面自己的标题，其余用 [ChartKind.title]。 */
    val title: String = kind.title,
    /** 页头一行说明（期名 · 主题 / 官方说明 / 第 N 期 / N 个视频）。 */
    val subtitle: String = "",
    val items: List<ChartItem> = emptyList(),
    /** 有期数的榜单（活动页的分组、每周必看、热歌榜；最新在前）的标签；其他榜单、或只有一期时为空，页面不显示选择条。 */
    val issues: List<String> = emptyList(),
    /** [issues] 里当前选中的下标。 */
    val selectedIssue: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * 首页榜单（每周必看 / 入站必刷 / 热歌榜 / 热门播客）与搜索框里的活动页。公开接口，不需要登录。
 *
 * 从 AppViewModel 拆出，由 `AppViewModel.charts` 持有。每次打开或下拉刷新重新取，不落库
 * （设计稿：docs/superpowers/specs/ 下的 home-charts、home-podcast、search-era-and-av、chart-issues）。
 * 风控由 [BiliRepository] 的读请求出口负责；这里不重试、不循环。
 * 点条目起播经 [playList] 回到宿主，整张榜单（有期数的是当前这一期）作为临时队列（docs/10）。
 *
 * 有期数的榜单共用一套切换机制（[selectIssue]）：
 * - 每周必看、热歌榜：先取全部期数（最新在前），再取要看的那一期；切到没看过的期发一次请求。
 * - 活动页类（热门播客、搜索框里的活动页）是页面里按标签页分组的 BV 号：先取页面得到各组的 BV 号，
 *   再按组批量取标题封面。
 *
 * 取过的期缓存在内存里，关闭榜单页或下拉刷新时清掉。
 */
class ChartController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val playList: ListPlayer,
) {

    private val _opened = MutableStateFlow<ChartState?>(null)

    /** 当前打开的榜单页。null = 未打开。 */
    val opened: StateFlow<ChartState?> = _opened.asStateFlow()

    /** [ChartKind.ERA_PAGE] 的网址（热门播客的网址在仓库里）。 */
    private var eraUrl: String = ""

    /** 活动页取到的各组（BV 号）；每周必看、热歌榜取到的各期（最新在前）。 */
    private var eraGroups: List<EraGroup> = emptyList()
    private var chartIssues: List<ChartIssue> = emptyList()

    /** 已取过的期（下标 → 内容），各类榜单共用。 */
    private val issueCache = mutableMapOf<Int, ChartPage>()

    fun open(kind: ChartKind) {
        resetIssues()
        _opened.value = ChartState(kind, isLoading = true)
        fetch(kind)
    }

    /** 打开搜索框里认出来的活动页。[url] 已规整成 `https://www.bilibili.com/blackboard/era/<id>.html`。 */
    fun openEraPage(url: String) {
        resetIssues()
        eraUrl = url
        _opened.value = ChartState(ChartKind.ERA_PAGE, isLoading = true)
        fetch(ChartKind.ERA_PAGE)
    }

    fun close() {
        _opened.value = null
    }

    /** 下拉刷新 / 失败后重试。已在加载时忽略。有期数的榜单会重取期数（或活动页），并重取当前这一期。 */
    fun retry() {
        val state = _opened.value ?: return
        if (state.isLoading) return
        _opened.value = state.copy(isLoading = true, errorMessage = null)
        fetch(state.kind, issue = state.selectedIssue)
    }

    /** 切到第 [index] 期（活动页的分组、每周必看、热歌榜）。取过的期直接用缓存，没取过的发一次请求。 */
    fun selectIssue(index: Int) {
        val state = _opened.value?.takeIf { it.issues.isNotEmpty() } ?: return
        if (index == state.selectedIssue || index !in state.issues.indices) return
        val cached = issueCache[index]
        _opened.value = if (cached != null) {
            state.copy(
                selectedIssue = index, items = cached.items, subtitle = cached.subtitle,
                isLoading = false, errorMessage = null,
            )
        } else {
            state.copy(
                selectedIssue = index, items = emptyList(), subtitle = "",
                isLoading = true, errorMessage = null,
            )
        }
        if (cached == null) scope.launch { loadIssue(state.kind, index) }
    }

    private fun resetIssues() {
        eraUrl = ""
        eraGroups = emptyList()
        chartIssues = emptyList()
        issueCache.clear()
    }

    private fun fetch(kind: ChartKind, issue: Int = 0) {
        scope.launch {
            when (kind) {
                ChartKind.PRECIOUS -> fetchChart(kind)
                ChartKind.WEEKLY, ChartKind.HOT_SONGS -> fetchIssued(kind, issue)
                ChartKind.PODCAST, ChartKind.ERA_PAGE -> fetchEra(kind, issue)
            }
        }
    }

    /** 入站必刷：没有期数，一次取全。 */
    private suspend fun fetchChart(kind: ChartKind) {
        val result = repository.preciousVideos()
        update(kind) { current ->
            result.fold(
                onSuccess = { page ->
                    current.copy(
                        subtitle = page.subtitle,
                        items = page.items,
                        isLoading = false,
                        errorMessage = null,
                    )
                },
                onFailure = { current.copy(isLoading = false, errorMessage = it.message ?: "加载失败") },
            )
        }
    }

    /** 每周必看、热歌榜：先取全部期数（刷新时重取），再取要看的那一期。 */
    private suspend fun fetchIssued(kind: ChartKind, issue: Int) {
        issueCache.clear()
        val issues = when (kind) {
            ChartKind.WEEKLY -> repository.weeklyIssues()
            else -> repository.hotSongIssues()
        }.getOrElse { error ->
            failIssue(kind, index = null, error)
            return
        }
        chartIssues = issues
        if (issues.isEmpty()) {
            // 接口给了空列表：显示空榜单，不当作错误
            update(kind) { it.copy(isLoading = false, errorMessage = null) }
            return
        }
        val index = issue.coerceIn(0, issues.lastIndex)
        // 先把期数选择条显示出来，再等这一期的内容；只有一期时不需要选择条
        update(kind) {
            it.copy(
                issues = if (issues.size > 1) issues.map { i -> i.label } else emptyList(),
                selectedIssue = index,
            )
        }
        loadIssue(kind, index)
    }

    /** 活动页类：先取页面里的各组（刷新时重取），再取要看的那一组的卡片。 */
    private suspend fun fetchEra(kind: ChartKind, issue: Int) {
        issueCache.clear()
        val page = when (kind) {
            ChartKind.PODCAST -> repository.podcastPage()
            else -> repository.eraPage(eraUrl)
        }.getOrElse { error ->
            failIssue(kind, index = null, error)
            return
        }
        eraGroups = page.groups
        val index = issue.coerceIn(0, page.groups.lastIndex)
        // 先把标题和分组选择条显示出来，再等这一组的卡片；只有一组时不需要选择条
        update(kind) {
            it.copy(
                title = if (kind == ChartKind.ERA_PAGE) page.title.ifBlank { kind.title } else it.title,
                issues = if (page.groups.size > 1) page.groups.map { g -> g.label } else emptyList(),
                selectedIssue = index,
            )
        }
        loadIssue(kind, index)
    }

    private suspend fun loadIssue(kind: ChartKind, index: Int) {
        val page = issueCache[index] ?: run {
            val result = fetchIssue(kind, index) ?: return
            result.getOrElse { error ->
                failIssue(kind, index, error)
                return
            }.also { issueCache[index] = it }
        }
        // 等待期间用户又切到了别的期：丢弃
        update(kind) { current ->
            if (current.selectedIssue != index) {
                current
            } else {
                current.copy(
                    items = page.items, subtitle = page.subtitle,
                    isLoading = false, errorMessage = null,
                )
            }
        }
    }

    /**
     * 取第 [index] 期的内容；下标越界（列表刚被刷新换掉）返回 null。
     * 活动页是这一组的 BV 号再补卡片，每周必看、热歌榜是一次期数请求。
     */
    private suspend fun fetchIssue(kind: ChartKind, index: Int): Result<ChartPage>? = when (kind) {
        ChartKind.WEEKLY -> chartIssues.getOrNull(index)?.let { repository.weeklyIssue(it) }
        ChartKind.HOT_SONGS -> chartIssues.getOrNull(index)?.let { repository.hotSongIssue(it) }
        ChartKind.PODCAST, ChartKind.ERA_PAGE -> eraGroups.getOrNull(index)?.let { group ->
            repository.videoCards(group.bvids).map { ChartPage(subtitle = eraSubtitle(it), items = it) }
        }
        ChartKind.PRECIOUS -> null
    }

    /** [index] 为 null 表示期数列表（或活动页本身）没取到；否则只在用户还停在这一期时才显示错误。 */
    private fun failIssue(kind: ChartKind, index: Int?, error: Throwable) {
        update(kind) { current ->
            if (index != null && current.selectedIssue != index) {
                current
            } else {
                current.copy(isLoading = false, errorMessage = error.message ?: "加载失败")
            }
        }
    }

    private fun eraSubtitle(items: List<ChartItem>) = "${items.size} 个视频"

    /** 只在榜单页还开着、且还是 [kind] 这一榜时改状态；关了或换榜了就丢弃（异步结果回来得晚）。 */
    private fun update(kind: ChartKind, transform: (ChartState) -> ChartState) {
        val current = _opened.value?.takeIf { it.kind == kind } ?: return
        _opened.value = transform(current)
    }

    /**
     * 点榜单条目：整张榜单作为临时队列，从这一条开始播，不覆盖用户加入的队列（docs/10）。
     * 榜单接口一次取全，不需要续页。
     */
    fun play(item: ChartItem) {
        val items = _opened.value?.items.orEmpty()
        val index = items.indexOf(item).takeIf { it >= 0 } ?: return
        playList.play(items.map { it.toRequest() }, index, loadMore = null)
    }

    // 榜单条目 → 播放请求。cid 留空：轮到播放时取分 P，多 P 原地展开（与稍后再看一致）
    private fun ChartItem.toRequest() = PlaybackRequest.Bili(
        bvid = bvid,
        title = title,
        coverUrl = coverUrl,
        author = author,
        durationSec = durationSec,
    )
}
