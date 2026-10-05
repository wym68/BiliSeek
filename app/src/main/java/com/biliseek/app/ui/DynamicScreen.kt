package com.biliseek.app.ui

import com.biliseek.app.ui.components.miniBarContentPadding
import com.biliseek.designsystem.component.BsLoadMoreFooter
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
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
import com.biliseek.app.data.DynamicExpandState
import com.biliseek.app.LoginPanel
import com.biliseek.app.ui.richtext.RichText
import com.biliseek.app.ui.richtext.RichTextSource
import com.biliseek.designsystem.component.BsIconAction
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.DynamicEntry
import com.biliseek.core.network.DynamicType
import com.biliseek.core.network.LoginState
import com.biliseek.core.network.dto.DynamicBlock
import com.biliseek.core.network.dto.FollowingUp

/**
 * 动态 tab（M11-C）。
 *
 * 结构：「全部/仅视频」分段筛选（固定）→ 动态流列表；
 * 「最常访问」头像栏作为列表 header，随滚动向上滑走。
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
    // 视频行长按菜单（加入列表 / 加入队列 / 下一首播放 / 下载）
    videoActions: DynamicVideoActions = DynamicVideoActions(),
    // Ch4
    onOpenImagePreview: (List<String>, Int) -> Unit = { _, _ -> },
    onOpenRepost: (String) -> Unit = {},
    onOpenComment: (DynamicEntry) -> Unit = {},
    onToggleLike: (String, Boolean) -> Unit = { _, _ -> },
    onOpenFollowList: () -> Unit = {},
    // 消息中心入口：未读小红点 + 点击回调
    unreadCount: Int = 0,
    onOpenMessages: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
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

    PullRefreshContainer(
        isRefreshing = state.feed.isLoading && state.feed.items.isNotEmpty(),
        errorMessage = state.feed.errorMessage,
        successText = "动态已刷新",
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            DynamicFilterBar(
                typeFilter = state.typeFilter,
                onTypeFilterChange = onTypeFilterChange,
                unreadCount = unreadCount,
                onOpenMessages = onOpenMessages,
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
                    Column(Modifier.fillMaxSize()) {
                        // 空态下保留「最常访问」，便于切换 UP 筛选
                        if (state.upChips.isNotEmpty()) {
                            MostVisitedUpRow(
                                upChips = state.upChips,
                                selectedUpMid = state.selectedUpMid,
                                onSelectUp = onSelectUp,
                                onOpenFollowList = onOpenFollowList,
                            )
                        }
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
                }

                else -> {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = miniBarContentPadding()) {
                        // 「最常访问」作为列表 header，随滚动向上滑走
                        if (state.upChips.isNotEmpty()) {
                            item(key = "most-visited") {
                                MostVisitedUpRow(
                                    upChips = state.upChips,
                                    selectedUpMid = state.selectedUpMid,
                                    onSelectUp = onSelectUp,
                                    onOpenFollowList = onOpenFollowList,
                                )
                            }
                        }
                        items(state.feed.items, key = { it.idStr }) { entry ->
                            DynamicCard(
                                entry = entry,
                                onClick = { onEntryClick(entry) },
                                onAuthorClick = { onAuthorClick(entry) },
                                expandState = dynamicExpand[entry.idStr],
                                onToggleExpand = { onToggleExpand(entry) },
                                onImageClick = { idx -> onOpenImagePreview(entry.pics.ifEmpty { listOf(entry.coverUrl).filter { it.isNotBlank() } }, idx) },
                                onBodyImageClick = onOpenImagePreview,
                                onRepostClick = { onOpenRepost(entry.idStr) },
                                onCommentClick = { onOpenComment(entry) },
                                onLikeClick = { onToggleLike(entry.idStr, entry.stat?.like?.status != true) },
                                videoActions = videoActions,
                            )
                            HorizontalDivider()
                        }
                        if (state.feed.hasMore || state.feed.isLoadingMore) {
                            item(key = "footer") {
                                // 失败后不自动再来（否则会一直重打 B 站），等用户点「重试」
                                if (shouldAutoLoadMore(state.feed.hasMore, state.feed.isLoadingMore, state.feed.errorMessage)) {
                                    LaunchedEffect(state.feedOffset) { onLoadMore() }
                                }
                                BsLoadMoreFooter(
                                    isLoadingMore = state.feed.isLoadingMore,
                                    errorMessage = state.feed.errorMessage,
                                    onRetry = onLoadMore,
                                    endText = "加载中…",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 筛选行：「全部 / 仅视频」分段 + 右侧消息入口图标（带未读小红点）。 */
@Composable
private fun DynamicFilterBar(
    typeFilter: String,
    onTypeFilterChange: (String) -> Unit,
    unreadCount: Int,
    onOpenMessages: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
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
        IconButton(onClick = onOpenMessages) {
            BadgedBox(
                badge = {
                    if (unreadCount > 0) {
                        Badge { Text(minOf(unreadCount, 99).toString()) }
                    }
                },
            ) {
                // 信封：与消息中心里「私信」入口同一个图标
                Icon(
                    imageVector = Icons.Filled.Email,
                    contentDescription = "消息",
                )
            }
        }
    }
}

/**
 * 「最常访问」标题行 + UP 头像横滑列表。作为动态流列表的 header，随滚动向上滑走。
 *
 * 点击头像触发筛选；再点已选中的头像 = 取消筛选。昵称宽度对齐头像，避免长昵称撑宽 item。
 */
@Composable
private fun MostVisitedUpRow(
    upChips: List<FollowingUp>,
    selectedUpMid: Long?,
    onSelectUp: (FollowingUp?) -> Unit,
    onOpenFollowList: () -> Unit,
) {
    // 标题行：最常访问 + 更多按钮（底部不留距，紧贴头像列表）
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "最常访问",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenFollowList) {
            Text("更多")
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }

    // UP 头像横滑列表
    LazyRow(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(upChips.take(10), key = { it.mid }) { up ->
            UpAvatarChip(
                up = up,
                selected = selectedUpMid == up.mid,
                onClick = { onSelectUp(up) },
            )
        }
    }
}

@Composable
private fun UpAvatarChip(
    up: FollowingUp,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(48.dp)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = up.face,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .let { if (selected) it.border(2.dp, primary, CircleShape) else it },
            contentScale = ContentScale.Crop,
        )
        Text(
            up.uname,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 视频动态长按菜单的动作集合。动态 tab 与 UP 空间「动态」共用一份，由宿主逐层透传。 */
data class DynamicVideoActions(
    val addToPlaylist: (DynamicEntry) -> Unit = {},
    /** 追加到播放队列末尾。 */
    val appendToQueue: (DynamicEntry) -> Unit = {},
    /** 插到当前曲目之后。 */
    val playNext: (DynamicEntry) -> Unit = {},
    val download: (DynamicEntry) -> Unit = {},
    /** AI 总结（工单 AI-6）。 */
    val summarize: (DynamicEntry) -> Unit = {},
)

/** 一条动态卡片：UP 头像/名字/时间 + 文案 + 封面（视频显示标题与时长，图文用 DrawGrid）。
 * 动态 tab 与 UP 空间页「动态」视图共用（M11-D）。Ch4 接入互动操作。
 * 点击区域拆成三块独立热区——头像/昵称跳 UP 主页、视频行播放、正文原地展开/收起，
 * 不再有"整卡可点"的大热区（避免图文动态点空白处误触、视频动态点文字误播放）。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun DynamicCard(
    entry: DynamicEntry,
    onClick: () -> Unit,
    onAuthorClick: () -> Unit = {},
    expandState: DynamicExpandState? = null,
    onToggleExpand: () -> Unit = {},
    onImageClick: (Int) -> Unit = {},
    /** 展开后正文里的图被点开：正文全部图片（按顺序）+ 点的是第几张。 */
    onBodyImageClick: (List<String>, Int) -> Unit = { _, _ -> },
    onRepostClick: () -> Unit = {},
    onCommentClick: () -> Unit = {},
    onLikeClick: () -> Unit = {},
    /** 视频行长按弹出的动作菜单。null = 不挂长按。 */
    videoActions: DynamicVideoActions? = null,
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

        // 长图文展开后按原文段落画（图文穿插）；没拉到段落时仍是整段文字
        val body = expandedBody(expandState)
        if (body.isNotEmpty()) {
            DynamicBody(
                body = body,
                emotes = entry.emotes + expandState?.entry?.emotes.orEmpty(),
                onImageClick = onBodyImageClick,
                onCollapse = onToggleExpand,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (entry.descText.isNotBlank()) {
            DynamicDescText(
                text = entry.descText,
                emotes = entry.emotes,
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
            // 点按播放，长按弹动作菜单。早先只挂了 clickable：长按松手也被当成一次点按，直接开播
            var showActions by remember { mutableStateOf(false) }
            if (showActions && videoActions != null) {
                ActionListSheet(
                    title = video.title,
                    items = listOf(
                        "加入列表" to { showActions = false; videoActions.addToPlaylist(entry) },
                        "加入队列" to { showActions = false; videoActions.appendToQueue(entry) },
                        "下一首播放" to { showActions = false; videoActions.playNext(entry) },
                        "下载" to { showActions = false; videoActions.download(entry) },
                        "AI 总结" to { showActions = false; videoActions.summarize(entry) },
                    ),
                    onDismiss = { showActions = false },
                )
            }
            Row(
                Modifier.padding(top = 8.dp).fillMaxWidth().combinedClickable(
                    onClick = onClick,
                    onLongClick = videoActions?.let { { showActions = true } },
                ),
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
                    // 充电专属：封面右上角粉色「充电」；限时免费：绿色「限免」（ChargeBadgePolicy）
                    chargeBadge(video)?.let { badge ->
                        CoverBadgeLabel(badge, small = false, modifier = Modifier.align(Alignment.TopEnd))
                    }
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
        } else if (bodyImageUrls(body).isNotEmpty()) {
            // 图已在展开的正文段落里按原位置显示，不再重复画动态流的预览图
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
                BsIconAction(
                    icon = Icons.AutoMirrored.Outlined.Reply,
                    label = "转发",
                    count = stat.forward.count.toLong(),
                    enabled = !stat.forward.forbidden,
                    onClick = onRepostClick,
                )
                BsIconAction(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    label = "评论",
                    count = stat.comment.count.toLong(),
                    onClick = onCommentClick,
                )
                BsIconAction(
                    icon = Icons.Outlined.ThumbUp,
                    activeIcon = Icons.Filled.ThumbUp,
                    label = "点赞",
                    count = stat.like.count.toLong(),
                    active = stat.like.status,
                    onClick = onLikeClick,
                )
            }
        }
    }
}

/**
 * 动态正文：折叠态最多 3 行，溢出时右下角叠一个可点的"…展开"；
 * 展开态按 [expandState] 显示 loading / 完整正文 + 下方"收起" / 失败+重试。
 * 正文长按可选中复制；单击正文不收起，只有"收起"收起。
 */
@Composable
private fun DynamicDescText(
    text: String,
    emotes: Map<String, com.biliseek.core.network.dto.Emote>,
    expandState: DynamicExpandState?,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.bodyMedium
    val source = RichTextSource(text = text, emotes = emotes, topics = true)

    if (expandState?.expanded == true) {
        when {
            expandState.error != null -> Column(modifier) {
                SelectionContainer {
                    RichText(source, style = style, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "加载失败，点击重试",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp).clickable(onClick = onToggleExpand),
                )
            }
            expandState.entry == null -> Column(modifier) {
                SelectionContainer {
                    RichText(source, style = style, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                CircularProgressIndicator(
                    Modifier.padding(top = 4.dp).size(14.dp),
                    strokeWidth = 2.dp,
                )
            }
            else -> {
                val full = expandState.entry
                Column(modifier) {
                    SelectionContainer {
                        RichText(
                            RichTextSource(
                                text = full.descText.ifBlank { text },
                                emotes = emotes + full.emotes,
                                topics = true,
                            ),
                            style = style,
                        )
                    }
                    CollapseLink(onToggleExpand, Modifier.align(Alignment.End))
                }
            }
        }
        return
    }

    var overflowing by remember(text) { mutableStateOf(false) }
    Box(modifier) {
        SelectionContainer {
            RichText(
                source,
                style = style,
                maxLines = 3,
                overflow = TextOverflow.Clip,
                onTextLayout = { result -> overflowing = result.hasVisualOverflow },
            )
        }
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

/**
 * 长图文展开后的正文：按原文顺序画文字段落与图片（opus/detail 的 paragraphs）。
 * 一段一张图铺满宽度、按原图比例（[inlineImageAspect]：特别长的图裁到约一屏，点开看全图）；
 * 一段多张图用九宫格。文字长按可选中复制；单击不收起，只有末尾"收起"收起。
 */
@Composable
private fun DynamicBody(
    body: List<DynamicBlock>,
    emotes: Map<String, com.biliseek.core.network.dto.Emote>,
    onImageClick: (List<String>, Int) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.bodyMedium
    val urls = remember(body) { bodyImageUrls(body) }
    val offsets = remember(body) { bodyImageOffsets(body) }
    Column(modifier) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                body.forEachIndexed { index, block ->
                    when (block) {
                        is DynamicBlock.Text -> RichText(
                            RichTextSource(text = block.text, emotes = emotes, topics = true),
                            style = style,
                        )
                        is DynamicBlock.Images -> if (block.pics.size == 1) {
                            val pic = block.pics.single()
                            AsyncImage(
                                model = pic.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(inlineImageAspect(pic.width, pic.height))
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { onImageClick(urls, offsets[index]) },
                                contentScale = ContentScale.Crop,
                                alignment = Alignment.TopCenter,
                            )
                        } else {
                            com.biliseek.app.ui.components.DrawGrid(
                                pics = block.pics.map { it.url },
                                onImageClick = { i -> onImageClick(urls, offsets[index] + i) },
                            )
                        }
                    }
                }
            }
        }
        CollapseLink(onCollapse, Modifier.align(Alignment.End))
    }
}

/** 展开态末尾的"收起"：单独一行靠右，与折叠态的"…展开"对称；不在可选中的文字里，复制时不会带上。 */
@Composable
private fun CollapseLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        "收起",
        modifier
            .padding(top = 4.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}
