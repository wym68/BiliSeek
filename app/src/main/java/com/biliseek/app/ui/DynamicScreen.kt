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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.DynamicInitState
import com.biliseek.app.DynamicUiState
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
    // 点开图文卡片：四级降级拉详情（可选）
    onOpenDetail: (DynamicEntry) -> Unit = {},
    // Ch4
    onOpenImagePreview: (List<String>, Int) -> Unit = { _, _ -> },
    onOpenRepost: (String) -> Unit = {},
    onOpenComment: (DynamicEntry) -> Unit = {},
    onToggleLike: (String, Boolean) -> Unit = { _, _ -> },
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
                                    onDetailClick = { onOpenDetail(entry) },
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
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "all") {
                    FilterChip(
                        selected = selectedUpMid == null,
                        onClick = { onSelectUp(null) },
                        label = { Text("全部 UP") },
                    )
                }
                items(upChips, key = { it.mid }) { up ->
                    FilterChip(
                        selected = selectedUpMid == up.mid,
                        onClick = { onSelectUp(up) },
                        label = { Text(up.uname) },
                    )
                }
            }
        }
    }
}

/** 一条动态卡片：UP 头像/名字/时间 + 文案 + 封面（视频显示标题与时长，图文用 DrawGrid）。
 * 动态 tab 与 UP 空间页「动态」视图共用（M11-D）。Ch4 接入互动操作。 */
@Composable
internal fun DynamicCard(
    entry: DynamicEntry,
    onClick: () -> Unit,
    onDetailClick: () -> Unit = {},
    onImageClick: (Int) -> Unit = {},
    onRepostClick: () -> Unit = {},
    onCommentClick: () -> Unit = {},
    onLikeClick: () -> Unit = {},
) {
    // 视频动态点播放（图音）才播；图文/转发点开弹层看全文。
    val clickable = if (entry.video != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier.clickable(onClick = onDetailClick)
    }
    Column(Modifier.fillMaxWidth().then(clickable).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            Text(
                entry.descText,
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
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
                Modifier.padding(top = 8.dp).fillMaxWidth(),
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
        // 无图类型（纯文字 WORD，或 DRAW/OPUS 确实没有配图）：不渲染图片区域，
        // 文字本身已经可点开看全文（本函数顶部整行绑定了 onDetailClick）。

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
