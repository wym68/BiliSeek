package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.DynamicInitState
import com.biliseek.app.DynamicUiState
import com.biliseek.app.data.DynamicExpandState
import com.biliseek.app.LoginPanel
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.DynamicEntry
import com.biliseek.core.network.DynamicType
import com.biliseek.core.network.LoginState
import com.biliseek.core.network.dto.FollowingUp

/**
 * 动态 tab（M11-C）。
 *
 * 结构：筛选行（全部/仅视频 + 按 UP 筛选 chips）→ 动态流列表。
 * 未登录整页引导；视频动态可播，图文动态仅展示（不响应点击）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicScreen(
    state: DynamicUiState,
    session: BiliSession,
    loginState: LoginState,
    onInit: () -> Unit,
    onRefresh: () -> Unit,
    onTypeFilterChange: (String) -> Unit,
    onSelectUp: (FollowingUp?) -> Unit,
    onLoadMore: () -> Unit,
    onRetryInit: () -> Unit,
    onEntryClick: (DynamicEntry) -> Unit,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    // 头像/昵称跳 UP 主页
    onAuthorClick: (DynamicEntry) -> Unit = {},
    // 正文原地展开/收起（四级降级拉详情，按 idStr 独立维护）
    dynamicExpand: Map<String, DynamicExpandState> = emptyMap(),
    onToggleExpand: (DynamicEntry) -> Unit = {},
    // Ch4
    onOpenImagePreview: (List<String>, Int) -> Unit = { _, _ -> },
    onOpenRepost: (String) -> Unit = {},
    onOpenComment: (DynamicEntry) -> Unit = {},
    onToggleLike: (String, Boolean) -> Unit = { _, _ -> },
    onOpenFollowList: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var refreshMessage by remember { mutableStateOf<String?>(null) }
    var refreshIsError by remember { mutableStateOf(false) }

    // 监听刷新状态变化，显示提示
    LaunchedEffect(state.feed.isLoading, state.feed.errorMessage) {
        if (!state.feed.isLoading && state.feed.items.isNotEmpty()) {
            if (state.feed.errorMessage != null) {
                refreshMessage = "刷新失败：${state.feed.errorMessage}"
                refreshIsError = true
            } else if (refreshMessage == null) {
                // 首次加载完成不显示提示
            } else {
                refreshMessage = "动态已刷新"
                refreshIsError = false
            }
        }
    }

    if (!session.isLoggedIn) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("关注动态", style = MaterialTheme.typography.titleMedium)
            Text(
                "登录后查看你关注的 UP 的新动态",
                Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            LoginPanel(
                loginState = loginState,
                session = session,
                onStart = onStartLogin,
                onCancel = onCancelLogin,
                onLogout = {},
            )
        }
        return
    }

    // 登录后首次进入自动初始化（拉关注列表 + 第一页）
    LaunchedEffect(Unit) { onInit() }

    val refreshState = rememberPullToRefreshState()
    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.feed.isLoading && state.feed.items.isEmpty(),
            onRefresh = onRefresh,
            state = refreshState,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                DynamicFilterBar(
                    typeFilter = state.typeFilter,
                    upChips = state.upChips,
                    selectedUpMid = state.selectedUpMid,
                    onTypeFilterChange = onTypeFilterChange,
                    onSelectUp = onSelectUp,
                    onOpenFollowList = onOpenFollowList,
                )

                when {
                    state.initState is DynamicInitState.Loading &&
                        state.upChips.isEmpty() && state.feed.items.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    }

                    state.initState is DynamicInitState.Error &&
                        state.upChips.isEmpty() && state.feed.items.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    state.initState.message,
                                    Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(onClick = onRetryInit) { Text("重试") }
                            }
                        }
                    }

                    state.feed.items.isEmpty() && !state.feed.isLoading -> {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text(
                                when (state.feed.errorMessage) {
                                    null -> "没有动态" + (if (state.selectedUpMid != null) "，换个 UP 看看" else "")
                                    else -> state.feed.errorMessage
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    else -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.feed.items, key = { it.idStr }) { entry ->
                                DynamicCard(
                                    entry = entry,
                                    onClick = { onEntryClick(entry) },
                                    onAuthorClick = { onAuthorClick(entry) },
                                    expandState = dynamicExpand[entry.idStr],
                                    onToggleExpand = { onToggleExpand(entry) },
                                    onImageClick = { idx -> onOpenImagePreview(entry.pics.ifEmpty { listOf(entry.coverUrl).filter { it.isNotBlank() } }, idx) },
                                    onRepostClick = { onOpenRepost(entry.idStr) },
                                    onCommentClick = { onOpenComment(entry) },
                                    onLikeClick = { onToggleLike(entry.idStr, entry.stat?.like?.status != true) },
                                )
                                HorizontalDivider()
                            }
                            if (state.feed.hasMore || state.feed.isLoadingMore) {
                                item(key = "footer") {
                                    if (state.feed.hasMore && !state.feed.isLoadingMore) {
                                        LaunchedEffect(state.feedOffset) { onLoadMore() }
                                    }
                                    Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                                        if (state.feed.isLoadingMore) {
                                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        } else if (state.feed.errorMessage != null) {
                                            Text(
                                                state.feed.errorMessage,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        } else {
                                            Text("加载中…", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        RefreshStateSnackbar(
            message = refreshMessage,
            isError = refreshIsError,
            onDismiss = { refreshMessage = null },
        )
    }
}

/** 筛选行：「全部 / 仅视频」分段 + 按 UP 筛选 chips（横向滚动）。 */
@Composable
private fun DynamicFilterBar(
    typeFilter: String,
    upChips: List<FollowingUp>,
    selectedUpMid: Long?,
    onTypeFilterChange: (String) -> Unit,
    onSelectUp: (FollowingUp?) -> Unit,
    onOpenFollowList: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SegmentedButton(
                selected = typeFilter == DynamicType.ALL,
                onClick = { onTypeFilterChange(DynamicType.ALL) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("全部") }
            SegmentedButton(
                selected = typeFilter == DynamicType.VIDEO,
                onClick = { onTypeFilterChange(DynamicType.VIDEO) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("仅视频") }
        }

        if (upChips.isNotEmpty()) {
            // 标题行：最常访问 + 更多按钮
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "最常访问",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenFollowList) {
                    Text("更多 >")
                }
            }

            // UP 头像横滑列表
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(upChips.take(6)) { up ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onSelectUp(up) },
                    ) {
                        AsyncImage(
                            model = up.face,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentScale = ContentScale.Crop,
                        )
                        Text(
                            up.uname,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 一条动态卡片：UP 头像/名字/时间 + 文案 + 封面（视频显示标题与时长，图文用 DrawGrid）。
 * 动态 tab 与 UP 空间页「动态」视图共用（M11-D）。Ch4 接入互动操作。
 * 点击区域拆成三块独立热区——头像/昵称跳 UP 主页、视频行播放、正文原地展开/收起，
 * 不再有"整卡可点"的大热区（避免图文动态点空白处误触、视频动态点文字误播放）。 */
@Composable
internal fun DynamicCard(
    entry: DynamicEntry,
    onClick: () -> Unit,
    onAuthorClick: () -> Unit = {},
    expandState: DynamicExpandState? = null,
    onToggleExpand: () -> Unit = {},
    onImageClick: (Int) -> Unit = {},
    onRepostClick: () -> Unit = {},
    onCommentClick: () -> Unit = {},
    onLikeClick: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            Modifier.clickable(onClick = onAuthorClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = entry.authorFace,
                contentDescription = null,
                modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.padding(start = 8.dp)) {
                Text(
                    entry.authorName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatRelativeTime(entry.pubTs * 1000),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (entry.descText.isNotBlank()) {
            DynamicDescText(
                text = entry.descText,
                expandState = expandState,
                onToggleExpand = onToggleExpand,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // OPUS 图文标题（无标题为空串，不渲染）；转发图文取 orig 的标题
        if (entry.video == null && entry.opusTitle.isNotBlank()) {
            Text(
                entry.opusTitle,
                Modifier.padding(top = if (entry.descText.isNotBlank()) 8.dp else 0.dp),
                style = MaterialTheme.typography.titleSmall,
            )
        }

        val video = entry.video
        if (video != null) {
            Row(
                Modifier.padding(top = 8.dp).fillMaxWidth().clickable(onClick = onClick),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    AsyncImage(
                        model = video.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.size(width = 96.dp, height = 54.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                    if (video.durationText.isNotBlank()) {
                        // 深底白字。不能用主题里的 onSurface / onScrim：
                        // 亮色主题下 onSurface 接近黑，与 scrim 同色，文本不可见。
                        // 跨主题保持黑底白字与 B 站 / 抖音 / 微信平台惯例一致。
                        Surface(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
                            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(3.dp),
                        ) {
                            Text(
                                video.durationText,
                                Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = androidx.compose.ui.graphics.Color.White,
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        video.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val hasPlay = video.playText.isNotBlank()
                    val hasDanmaku = video.danmakuText.isNotBlank()
                    if (hasPlay || hasDanmaku) {
                        val parts = buildList {
                            if (hasPlay) add("${video.playText}播放")
                            if (hasDanmaku) add("${video.danmakuText}弹幕")
                        }
                        Text(
                            parts.joinToString(" · "),
                            Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (entry.rawType == "DYNAMIC_TYPE_FORWARD") {
                        Text("转发动态", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else if (entry.pics.size > 1) {
            // 多图图文动态：用 DrawGrid
            com.biliseek.app.ui.components.DrawGrid(
                pics = entry.pics,
                onImageClick = onImageClick,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (entry.coverUrl.isNotBlank()) {
            // 单图兜底：与原版同款（点击进入全屏预览）
            AsyncImage(
                model = entry.coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(width = 200.dp, height = 120.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onImageClick(0) },
                contentScale = ContentScale.Crop,
            )
        }
        // 无图类型（纯文字 WORD，或 DRAW/OPUS 确实没有配图）：不渲染图片区域。
        // 文字过长时靠 DynamicDescText 自带的"…展开"看全文，不再有整卡点击兜底。

        // Ch4：底部互动按钮
        val stat = entry.stat
        if (stat != null) {
            HorizontalDivider(
                Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                com.biliseek.app.ui.components.ActionButton(
                    count = stat.forward.count,
                    label = "转发",
                    enabled = !stat.forward.forbidden,
                    onClick = onRepostClick,
                )
                com.biliseek.app.ui.components.ActionButton(
                    count = stat.comment.count,
                    label = "评论",
                    onClick = onCommentClick,
                )
                com.biliseek.app.ui.components.ActionButton(
                    count = stat.like.count,
                    label = "点赞",
                    active = stat.like.status,
                    onClick = onLikeClick,
                )
            }
        }
    }
}

/**
 * 动态正文：折叠态最多 3 行，溢出时右下角叠一个可点的"…展开"；
 * 展开态按 [expandState] 显示 loading / 完整正文+"收起" / 失败+重试。
 */
@Composable
private fun DynamicDescText(
    text: String,
    expandState: DynamicExpandState?,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.bodyMedium

    if (expandState?.expanded == true) {
        when {
            expandState.error != null -> Column(modifier) {
                Text(text, style = style, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    "加载失败，点击重试",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp).clickable(onClick = onToggleExpand),
                )
            }
            expandState.entry == null -> Column(modifier) {
                Text(text, style = style, maxLines = 3, overflow = TextOverflow.Ellipsis)
                CircularProgressIndicator(
                    Modifier.padding(top = 4.dp).size(14.dp),
                    strokeWidth = 2.dp,
                )
            }
            else -> {
                val fullText = expandState.entry.descText.ifBlank { text }
                Text(
                    buildAnnotatedString {
                        append(fullText)
                        append("  ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("收起") }
                    },
                    style = style,
                    modifier = modifier.clickable(onClick = onToggleExpand),
                )
            }
        }
        return
    }

    var overflowing by remember(text) { mutableStateOf(false) }
    Box(modifier) {
        Text(
            text,
            style = style,
            maxLines = 3,
            overflow = TextOverflow.Clip,
            onTextLayout = { result -> overflowing = result.hasVisualOverflow },
        )
        if (overflowing) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.align(Alignment.BottomEnd).clickable(onClick = onToggleExpand),
            ) {
                Text(
                    "…展开",
                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    style = style,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
