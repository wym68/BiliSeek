package com.biliseek.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Cancel
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.biliseek.app.ui.ActionListSheet
import com.biliseek.app.ui.SelectionToolbar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import coil3.compose.AsyncImage
import com.biliseek.app.SearchMode
import com.biliseek.app.data.SearchHistoryEntity
import com.biliseek.app.ui.CoverThumbnail
import com.biliseek.app.ui.formatFans
import com.biliseek.app.ui.formatPlayCount
import com.biliseek.app.ui.formatPubDate

import com.biliseek.app.ui.SearchHistorySection
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.SearchDuration
import com.biliseek.core.network.SearchError
import com.biliseek.core.network.SearchOrder
import com.biliseek.core.network.retryable
import com.biliseek.core.network.dto.SearchUser
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.network.stripHighlight
import com.biliseek.core.player.formatDuration

@Composable
fun SearchScreen(
    state: SearchUiState,
    /** 多 P 面板。默认关闭，便于不涉及分 P 的调用方 / 测试省略。 */
    pagePanel: PagePanelState = PagePanelState(),
    onKeywordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResultClick: (SearchVideo) -> Unit,
    onAddToPlaylist: (SearchVideo) -> Unit,
    onPlayNext: (SearchVideo) -> Unit,
    onCopyLink: (SearchVideo) -> Unit,
    onShowPages: (SearchVideo) -> Unit,
    onDownload: (SearchVideo) -> Unit = {},
    /** 单条追加到播放队列末尾（三点菜单「加入队列」）。 */
    onAppendToQueue: (SearchVideo) -> Unit = {},
    /** 查看UP主主页。 */
    onViewUpOwner: (SearchVideo) -> Unit = {},
    onLoadMore: () -> Unit,
    onPageClick: (PageEntry) -> Unit,
    /** 多 P 面板：复选与动作回调 */
    isPageSelection: Boolean = false,
    selectedPageCids: Set<Long> = emptySet(),
    onEnterPageSelection: (PageEntry) -> Unit = {},
    onTogglePageSelection: (Long) -> Unit = {},
    onSelectAllPages: () -> Unit = {},
    onClearPageSelection: () -> Unit = {},
    onExitPageSelection: () -> Unit = {},
    onAddPagesToPlaylist: () -> Unit = {},
    onAppendPagesToQueue: () -> Unit = {},
    onEnqueuePagesNext: () -> Unit = {},
    onDownloadPages: () -> Unit = {},
    onPageAddToPlaylist: (PageEntry) -> Unit = {},
    onPageAppendToQueue: (PageEntry) -> Unit = {},
    onPagePlayNext: (PageEntry) -> Unit = {},
    onPageCopyLink: (PageEntry) -> Unit = {},
    onPageDownload: (PageEntry) -> Unit = {},
    /** Ch3：复选模式回调 */
    isSelectionMode: Boolean = false,
    selectedBvids: Set<String> = emptySet(),
    onEnterSelection: (SearchVideo) -> Unit = {},
    onToggleSelection: (String) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onExitSelection: () -> Unit = {},
    onAddSelectionToPlaylist: () -> Unit = {},
    onAppendSelectionToQueue: () -> Unit = {},
    onEnqueueSelectionNext: () -> Unit = {},
    onDownloadSelection: () -> Unit = {},
    isLoggedIn: Boolean = false,
    onDismissPages: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    /** 无搜索时展示的首页空状态 */
    emptyContent: @Composable () -> Unit = {},
    onOrderChange: (SearchOrder) -> Unit = {},
    onDurationChange: (SearchDuration) -> Unit = {},
    /** UP 主搜索（M11-D）。 */
    onModeChange: (SearchMode) -> Unit = {},
    onUserClick: (SearchUser) -> Unit = {},
    onRetryUser: () -> Unit = {},
    onLoadMoreUsers: () -> Unit = {},
    /** 搜索历史（聚焦搜索框时弹出参考，M11 真机反馈）。 */
    searchHistory: List<SearchHistoryEntity> = emptyList(),
    onHistoryClick: (String) -> Unit = {},
    onDeleteHistory: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
    /** 头像点击（跳转登录账户 UP 主页）。 */
    onAvatarClick: () -> Unit = {},
    /** 登录态（标题栏显示账号名与头像，BBPlayer 主页风格）。 */
    session: BiliSession = BiliSession.EMPTY,
) {
    val listState = rememberLazyListState()
    // 搜索框聚焦态：无关键词且聚焦时弹出搜索历史参考，未聚焦显示首页空状态
    var searchFocused by remember { mutableStateOf(false) }
    // 结果页返回手势用：清空关键词后把焦点还给搜索框并弹键盘（模拟「撤销这次搜索」）
    val searchFieldFocus = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 搜索一旦发起（点历史 / IME 回车），主动收键盘并取消聚焦，
    // 否则键盘会浮在结果页上不撤回（需手动返回手势才收）。
    val dismissSearchInput = {
        searchFocused = false
        focusManager.clearFocus()
        keyboardController?.hide()
    }
    val submitSearch = {
        dismissSearchInput()
        onSubmit()
    }
    val historyClick: (String) -> Unit = { kw ->
        dismissSearchInput()
        onHistoryClick(kw)
    }

    // 长按不再是「直接加歌单」这一个动作，而是打开一组动作
    var actionsFor by remember { mutableStateOf<SearchVideo?>(null) }
    actionsFor?.let { video ->
        ActionListSheet(
            title = stripHighlight(video.title),
            items = listOf(
                "加入列表" to { actionsFor = null; onAddToPlaylist(video) },
                // 加入队列 = 追加到播放队列末尾（多 P 走挑 P 页，默认全选）
                "加入队列" to { actionsFor = null; onAppendToQueue(video) },
                "下一首播放" to { actionsFor = null; onPlayNext(video) },
                "查看UP主" to { actionsFor = null; onViewUpOwner(video) },
                "复制链接" to { actionsFor = null; onCopyLink(video) },
                "下载" to { actionsFor = null; onDownload(video) },
            ),
            onDismiss = { actionsFor = null },
        )
    }

    // 滑到底部自动加载下一页。
    //
    // 注意 remember 必须带 key：state 是普通参数而非 State，
    // 无 key 时 derivedStateOf 会永久捕获首次组合的空列表，条件永远不成立。
    val itemCount = state.items.size
    val shouldLoadMore by remember(itemCount) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            itemCount > 0 && last >= itemCount - 3
        }
    }
    LaunchedEffect(shouldLoadMore, itemCount) { if (shouldLoadMore) onLoadMore() }

    // 标题栏折叠：搜索框 / 模式切换 / 筛选钉在顶部不动，只有标题栏随结果列表滚动上移并消失
    // （滚回顶部再出现），让出更多空间展示搜索结果。titleOffset ∈ [-titleHeightPx, 0]。
    // 只有「视频结果页」才折叠标题（复选态无标题栏），离开该视图时复位，避免标题停在半收起态。
    val isResultsVideoView = !isSelectionMode &&
        state.keyword.isNotBlank() &&
        state.searchMode == SearchMode.VIDEO &&
        !pagePanel.isOpen
    var titleHeightPx by remember { mutableIntStateOf(0) }
    var titleOffset by remember { mutableFloatStateOf(0f) }
    val collapsingActive = androidx.compose.runtime.rememberUpdatedState(isResultsVideoView)
    val collapseConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val limit = titleHeightPx.toFloat()
                if (!collapsingActive.value || limit <= 0f) return Offset.Zero
                val prev = titleOffset
                titleOffset = (titleOffset + available.y).coerceIn(-limit, 0f)
                return Offset(0f, titleOffset - prev)
            }
        }
    }
    LaunchedEffect(isResultsVideoView) { if (!isResultsVideoView) titleOffset = 0f }

    Column(modifier = modifier.fillMaxSize()) {
        // Ch3：复选态下整栏换工具栏、搜索框隐藏
        if (isSelectionMode) {
            // 系统返回键：清空勾选并退出复选态
            BackHandler {
                onExitSelection()
            }
            SelectionToolbar(
                selectedCount = selectedBvids.size,
                visibleCount = state.items.size,
                allSelected = selectedBvids.size == state.items.size && state.items.isNotEmpty(),
                // 未登录时禁「加入列表 / 下载」（定案 Ch3-4）
                canAddToPlaylist = isLoggedIn,
                canDownload = isLoggedIn,
                onSelectAll = onSelectAll,
                onClearSelection = onClearSelection,
                onAddToPlaylist = onAddSelectionToPlaylist,
                onAppendToQueue = onAppendSelectionToQueue,
                onEnqueueNext = onEnqueueSelectionNext,
                onDownload = onDownloadSelection,
                onExit = onExitSelection,
            )
        } else {
            // 标题栏（BBPlayer 主页风格）：软件名 + B 站账号名，右侧头像圆圈。
            // 头像的「更多功能」入口暂不做（M11 真机反馈预留）。
            // 外层 Box 承担折叠：measure 拿到标题原始高度，按 titleOffset 缩减可视高度并整体上移。
            Box(
                Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        if (titleHeightPx != placeable.height) titleHeightPx = placeable.height
                        val visible = (placeable.height + titleOffset).roundToInt()
                            .coerceIn(0, placeable.height)
                        layout(placeable.width, visible) {
                            placeable.place(0, titleOffset.roundToInt())
                        }
                    },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "BiliSeek",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        // 当前小时（每分钟 tick 一次，保证跨午 / 跨晚自然切换）
                        val hour by produceState(initialValue = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                            while (true) {
                                delay(60_000)
                                value = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                            }
                        }
                        val greeting = greetingForHour(hour)
                        Text(
                            text = if (greeting != null) {
                                val name = if (session.isLoggedIn) session.uname.ifBlank { "已登录" } else "未登录"
                                "$greeting，$name"
                            } else {
                                if (session.isLoggedIn) session.uname.ifBlank { "已登录" } else "未登录"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AsyncImage(
                        model = if (session.isLoggedIn) normalizeCoverUrl(session.face) else null,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(enabled = session.isLoggedIn) { onAvatarClick() }
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            // 无「搜索」按钮：输入后按输入法回车键即可搜索（IME Search action）
            OutlinedTextField(
                value = state.keyword,
                onValueChange = onKeywordChange,
                label = { Text("搜索全 B 站，或粘贴 BV 号 / 链接") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(searchFieldFocus)
                    .onFocusChanged { searchFocused = it.isFocused },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                trailingIcon = {
                    // 圆叉：点击快速清空。仅有关键词时显示
                    if (state.keyword.isNotEmpty()) {
                        androidx.compose.material3.IconButton(onClick = { onKeywordChange("") }) {
                            Icon(Icons.Filled.Cancel, contentDescription = "清空")
                        }
                    }
                },
            )
        }

        // ── 无关键词：聚焦弹搜索历史参考；未聚焦显示首页空状态 ──
        if (state.keyword.isBlank()) {
            if (searchFocused) {
                // 空+聚焦态返回：先取消聚焦回首页（键盘已由系统在上一次返回收起），
                // 再按一次才走 Activity 默认退栈退出 App——而不是聚焦时直接退桌面。
                BackHandler {
                    searchFocused = false
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
                // 点空白处收起历史（并收键盘）：背景 Box 拦截点击，子 chips 点击优先消费
                val keyboard = LocalSoftwareKeyboardController.current
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            searchFocused = false
                            keyboard?.hide()
                        },
                ) {
                    if (searchHistory.isEmpty()) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text("暂无搜索历史", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        ) {
                            SearchHistorySection(
                                searchHistory = searchHistory,
                                onKeywordClick = historyClick,
                                onDeleteKeyword = onDeleteHistory,
                                onClearHistory = onClearHistory,
                            )
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize()) { emptyContent() }
            }
            return@Column
        }

        // 结果页系统返回手势：先收子页（多 P 面板自带 BackHandler，见下方），否则清空关键词并
        // 把焦点还给搜索框、弹起键盘——回到空状态显示历史，模拟「撤销这次搜索」。
        // 清空后 keyword 变空走上面的空状态分支（不拦截返回），下一次返回即系统默认退栈。
        BackHandler(enabled = !pagePanel.isOpen) {
            onKeywordChange("")
            searchFieldFocus.requestFocus()
            keyboardController?.show()
        }

        // ── 有关键词（已搜索）：筛选区作为结果列表的 header，跟着结果一起滚
        // （M11 真机反馈：希望滚动时红框一起被推上去，腾出更多空间）。

        // ── UP 主结果分支（M11-D）：保持独立，走自身 LazyColumn，不进 A3 结果列表 ──
        if (state.searchMode == SearchMode.USER) {
            UserResults(
                state = state,
                onUserClick = onUserClick,
                onRetry = onRetryUser,
                onLoadMore = onLoadMoreUsers,
                emptyContent = emptyContent,
            )
            return@Column
        }

        // 多 P 展开：一个 BV 在结果里只占一条，点入才展开成列表。
        // 面板自带纵向 LazyColumn，塞进结果列表的 LazyColumn 会触发嵌套同向滚动崩溃，
        // 故作为独立整屏子页处理（M11 真机：返回手势收面板而非退桌面）。
        if (pagePanel.isOpen) {
            BackHandler(onBack = onDismissPages)
            MultiPagePanel(
                state = pagePanel,
                isLoggedIn = isLoggedIn,
                isSelection = isPageSelection,
                selectedCids = selectedPageCids,
                onPageClick = onPageClick,
                onDismiss = onDismissPages,
                onEnterSelection = onEnterPageSelection,
                onToggleSelection = onTogglePageSelection,
                onSelectAll = onSelectAllPages,
                onClearSelection = onClearPageSelection,
                onExitSelection = onExitPageSelection,
                onAddSelectionToPlaylist = onAddPagesToPlaylist,
                onAppendSelectionToQueue = onAppendPagesToQueue,
                onEnqueueSelectionNext = onEnqueuePagesNext,
                onDownloadSelection = onDownloadPages,
                onEntryAddToPlaylist = onPageAddToPlaylist,
                onEntryAppendToQueue = onPageAppendToQueue,
                onEntryPlayNext = onPagePlayNext,
                onEntryCopyLink = onPageCopyLink,
                onEntryDownload = onPageDownload,
            )
            return@Column
        }

        // A3：结果 + 加载更多在 LazyColumn 内滚动；滚动经 collapseConnection 折叠标题栏，
        // 让出更多空间展示结果。统计行「约 N 个结果 · 第 X/Y 页」已按定稿删除。
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(collapseConnection),
        ) {
            // 筛选条作为 header item，随结果一起滚动（红框跟着划走）
            // pages 非空已在上方提前 return，不会到这里
            item(key = "filterBar") {
                SearchFilterBar(
                    order = state.order,
                    duration = state.duration,
                    searchMode = state.searchMode,
                    // 复选态只对视频结果有效，隐藏 UP 主 chip，避免切到 UP 主
                    showModeChip = !isSelectionMode,
                    onOrderChange = onOrderChange,
                    onDurationChange = onDurationChange,
                    onModeChange = onModeChange,
                )
            }
            when {
                state.isLoading -> item(key = "loading") {
                    Box(Modifier.fillParentMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                // 修白屏：有关键词却 0 结果时，早期条件把这一支漏成了空白页
                state.items.isEmpty() && state.error != null -> item(key = "errorPanel") {
                    Box(Modifier.fillParentMaxSize(), Alignment.Center) {
                        ErrorPanel(state.error, onRetry)
                    }
                }
                state.items.isEmpty() -> item(key = "empty") {
                    Box(Modifier.fillParentMaxSize()) { emptyContent() }
                }
                else -> {
                    // 已有结果时，失败（多为加载下一页）收窄成一条，随列表一起滚动
                    state.error?.let { error ->
                        item(key = "error") {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                ),
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        errorMessage(error),
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (error.retryable) {
                                        TextButton(onClick = onRetry) { Text("重试") }
                                    }
                                }
                            }
                        }
                    }
                    items(state.items, key = { it.bvid }) { video ->
                        val selected = video.bvid in selectedBvids
                        ResultRow(
                            video = video,
                            onClick = {
                                if (isSelectionMode) onToggleSelection(video.bvid)
                                else onResultClick(video)
                            },
                            onLongClick = {
                                // 复选态下长按不做事；正常态长按进入复选模式并勾选该项
                                if (!isSelectionMode) onEnterSelection(video)
                            },
                            trailing = {
                                if (isSelectionMode) {
                                    androidx.compose.material3.Checkbox(
                                        checked = selected,
                                        onCheckedChange = { onToggleSelection(video.bvid) },
                                    )
                                } else {
                                    androidx.compose.material3.IconButton(onClick = { actionsFor = video }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                    if (state.isLoadingMore) {
                        item(key = "loadingMore") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 搜索筛选条：搜索目标（UP 主）+ 排序 + 时长。
 *
 * 换筛选即整页重搜（页码归 1）。筛选对「听」场景价值极高——
 * 筛长视频听专辑、筛短视频听单曲，这是视频客户端不会做的差异化。
 *
 * 所有 chips 均可再次点击取消、回到默认（综合 / 不限 / 视频）。
 * 视频是默认搜索目标，故无「视频」chip；「UP 主」与排序同行、同款同高。
 * UP 主模式下排序 / 时长隐藏（接口不支持这两个维度），仅留「UP 主✓」供退回视频。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SearchFilterBar(
    order: SearchOrder,
    duration: SearchDuration,
    searchMode: SearchMode,
    showModeChip: Boolean,
    onOrderChange: (SearchOrder) -> Unit,
    onDurationChange: (SearchDuration) -> Unit,
    onModeChange: (SearchMode) -> Unit,
) {
    // 排序与时长拆成两行：时长四个按钮独占一行（M11 真机反馈：
    // 默认 labelLarge + 混排会让时长四个按钮换行）
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showModeChip) {
            androidx.compose.material3.FilterChip(
                selected = searchMode == SearchMode.USER,
                // 再点已选中的「UP 主」= 退回视频（默认搜索目标）
                onClick = {
                    onModeChange(
                        if (searchMode == SearchMode.USER) SearchMode.VIDEO else SearchMode.USER,
                    )
                },
                label = { Text("UP 主", style = MaterialTheme.typography.labelSmall) },
            )
        }
        // UP 主搜索不支持排序 / 时长，选中后隐藏这些 chips
        if (searchMode == SearchMode.VIDEO) {
            ORDER_OPTIONS.forEach { (label, value) ->
                androidx.compose.material3.FilterChip(
                    selected = order == value,
                    // 再点已选中的排序 = 取消筛选，回综合（接口默认）
                    onClick = { onOrderChange(if (order == value) SearchOrder.COMPREHENSIVE else value) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
    // UP 主模式下时长同样隐藏
    if (searchMode == SearchMode.VIDEO) {
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DURATION_OPTIONS.forEach { (label, value) ->
                androidx.compose.material3.FilterChip(
                    selected = duration == value,
                    // 再点已选中的时长 = 取消筛选，回不限
                    onClick = { onDurationChange(if (duration == value) SearchDuration.ANY else value) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

/**
 * UP 主搜索结果列表（M11-D）。
 *
 * 行：头像 + 昵称 + 签名 + 粉丝/视频数。点击进 UP 空间页。
 * 分页与视频搜索同款：滚动到底自动加载下一页。
 */
@Composable
private fun UserResults(
    state: SearchUiState,
    onUserClick: (SearchUser) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    emptyContent: @Composable () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val itemCount = state.users.size
    val shouldLoadMore by remember(itemCount) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            itemCount > 0 && last >= itemCount - 3
        }
    }
    LaunchedEffect(shouldLoadMore, itemCount) { if (shouldLoadMore) onLoadMore() }

    when {
        state.userLoading && state.users.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
        state.users.isEmpty() && state.userError != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            ErrorPanel(state.userError, onRetry)
        }
        state.users.isEmpty() -> if (state.keyword.isBlank()) {
            // 无关键词：与视频模式一致，展示首页空状态（搜索历史 / 最近播放）
            Box(Modifier.fillMaxSize()) { emptyContent() }
        } else {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("没有找到相关 UP 主", style = MaterialTheme.typography.bodySmall)
            }
        }
        else -> {
            if (state.userTotalResults > 0) {
                Text(
                    "约 ${state.userTotalResults} 个 UP · 第 ${state.userPage}/${state.userTotalPages} 页",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.users, key = { it.mid }) { user ->
                    UserRow(user, onClick = { onUserClick(user) })
                    HorizontalDivider()
                }
                if (state.userLoading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserRow(user: SearchUser, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = normalizeCoverUrl(user.upic),
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f)) {
            Text(
                user.uname,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                user.usign,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "粉丝 ${formatFans(user.fans)} · ${user.videos} 个视频",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val ORDER_OPTIONS = listOf(
    "最多播放" to SearchOrder.CLICK,
    "最新" to SearchOrder.PUBDATE,
    "最多弹幕" to SearchOrder.DM,
)

// 「不限时长」按钮去掉：不选任何档位 = 不限（请求不带 duration 参数，接口默认）
private val DURATION_OPTIONS = listOf(
    "10 分内" to SearchDuration.UNDER_10,
    "10–30 分" to SearchDuration._10_30,
    "30–60 分" to SearchDuration._30_60,
    "60 分+" to SearchDuration.OVER_60,
)

/**
 * 空结果与错误的统一落点。
 *
 * **[SearchError.Blocked] 不给重试按钮。** 见 [retryable]：放出按钮就是在诱导用户猛戳，
 * 把一次临时限流推成长期封禁。
 */
@Composable
private fun ErrorPanel(error: SearchError, onRetry: () -> Unit) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            errorMessage(error),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        errorHint(error)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
        if (error.retryable) {
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

private fun errorMessage(error: SearchError): String = when (error) {
    SearchError.Blocked -> "请求太频繁，B 站暂时拦截了搜索"
    SearchError.CredentialsInvalid -> "设备凭据已失效，已重新种植"
    SearchError.NotActivated -> "设备凭据尚未激活，搜索暂时拿不到结果"
    is SearchError.Network -> "网络不可用"
    is SearchError.Empty -> "没有找到「${error.keyword}」"
    is SearchError.Unknown -> "搜索失败：${error.message}"
}

private fun errorHint(error: SearchError): String? = when (error) {
    // 明确告诉用户「等」，而不是留一个能戳的按钮
    SearchError.Blocked -> "请过几分钟再试，反复重试会让拦截时间变长"
    is SearchError.Empty -> "换个关键词，或粘贴 BV 号 / 链接直达"
    is SearchError.Network -> error.message.takeIf { it.isNotBlank() }
    else -> null
}

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
private fun ResultRow(
    video: SearchVideo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            // 长按弹操作菜单
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 封面 64dp 宽（16:9）：够辨认即可，把空间让给标题与元信息（M11 真机反馈）
        CoverThumbnail(
            url = normalizeCoverUrl(video.pic),
            width = 64.dp,
            badge = if (video.badgePay) "充电" else null,
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stripHighlight(video.title),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 元信息用 FlowRow：空间不够时整个词条换到下一行，
            // 避免 Row 单行布局把「230.4万播放」拆字硬折（M11 真机反馈）
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(video.author, style = MaterialTheme.typography.bodySmall)
                Text(
                    com.biliseek.app.ui.normalizeDurationText(video.duration),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("${formatPlayCount(video.play)} 播放", style = MaterialTheme.typography.bodySmall)
            }
            // 发布时间
            val pubDateText = formatPubDate(video.pubdate)
            if (pubDateText.isNotEmpty()) {
                Text(pubDateText, style = MaterialTheme.typography.labelSmall)
            }
        }
        trailing()
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MultiPagePanel(
    state: PagePanelState,
    isLoggedIn: Boolean,
    isSelection: Boolean,
    selectedCids: Set<Long>,
    onPageClick: (PageEntry) -> Unit,
    onDismiss: () -> Unit,
    onEnterSelection: (PageEntry) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onExitSelection: () -> Unit,
    onAddSelectionToPlaylist: () -> Unit,
    onAppendSelectionToQueue: () -> Unit,
    onEnqueueSelectionNext: () -> Unit,
    onDownloadSelection: () -> Unit,
    onEntryAddToPlaylist: (PageEntry) -> Unit,
    onEntryAppendToQueue: (PageEntry) -> Unit,
    onEntryPlayNext: (PageEntry) -> Unit,
    onEntryCopyLink: (PageEntry) -> Unit,
    onEntryDownload: (PageEntry) -> Unit,
) {
    // 复选态系统返回先退复选，再退面板（外层 dismiss 的 BackHandler 在复选态被本Handler拦截）
    BackHandler(enabled = isSelection) { onExitSelection() }
    // 单条三点菜单
    var actionsFor by remember { mutableStateOf<PageEntry?>(null) }
    actionsFor?.let { entry ->
        ActionListSheet(
            title = "P${entry.page}  ${entry.title}",
            items = listOf(
                "加入列表" to { actionsFor = null; onEntryAddToPlaylist(entry) },
                "加入队列" to { actionsFor = null; onEntryAppendToQueue(entry) },
                "下一首播放" to { actionsFor = null; onEntryPlayNext(entry) },
                "复制链接" to { actionsFor = null; onEntryCopyLink(entry) },
                "下载" to { actionsFor = null; onEntryDownload(entry) },
            ),
            onDismiss = { actionsFor = null },
        )
    }
    Column(Modifier.fillMaxSize()) {
        if (isSelection) {
            // 复选态整栏换工具栏（与搜索结果复选同一组件）
            SelectionToolbar(
                selectedCount = selectedCids.size,
                visibleCount = state.pages.size,
                allSelected = selectedCids.size == state.pages.size && state.pages.isNotEmpty(),
                canAddToPlaylist = isLoggedIn,
                canDownload = isLoggedIn,
                onSelectAll = onSelectAll,
                onClearSelection = onClearSelection,
                onAddToPlaylist = onAddSelectionToPlaylist,
                onAppendToQueue = onAppendSelectionToQueue,
                onEnqueueNext = onEnqueueSelectionNext,
                onDownload = onDownloadSelection,
                onExit = onExitSelection,
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${state.bvid} · ${state.pages.size} 个分 P",
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = onDismiss) { Text("返回结果") }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.pages, key = { it.cid }) { entry ->
                val selected = entry.cid in selectedCids
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (isSelection) onToggleSelection(entry.cid) else onPageClick(entry)
                            },
                            onLongClick = { if (!isSelection) onEnterSelection(entry) },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("P${entry.page}  ${entry.title}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            formatDuration(entry.durationSec * 1000),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (isSelection) {
                        androidx.compose.material3.Checkbox(
                            checked = selected,
                            onCheckedChange = { onToggleSelection(entry.cid) },
                        )
                    } else {
                        androidx.compose.material3.IconButton(onClick = { actionsFor = entry }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * 把一天的小时映射为问候语。
 *
 * 时间分段按中文日常习惯：
 * - 上午 5–11 点
 * - 中午 12–13 点
 * - 下午 14–17 点
 * - 晚上 18–次日 4 点（用户没列这档，但留空字符串会让问候断档，故补「晚上好」）
 *
 * 返回 `null` 表示当前时间不显示问候（理论不会触发，留作扩展）。
 */
private fun greetingForHour(hour: Int): String? = when (hour) {
    in 5..11 -> "上午好"
    in 12..13 -> "中午好"
    in 14..17 -> "下午好"
    in 18..23, in 0..4 -> "晚上好"
    else -> null
}
