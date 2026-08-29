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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
@Composable
fun DynamicScreen(
    state: DynamicUiState,
    session: BiliSession,
    loginState: LoginState,
    onInit: () -> Unit,
    onTypeFilterChange: (String) -> Unit,
    onSelectUp: (FollowingUp?) -> Unit,
    onLoadMore: () -> Unit,
    onRetryInit: () -> Unit,
    onEntryClick: (DynamicEntry) -> Unit,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
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

    Column(modifier.fillMaxSize()) {
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
                        DynamicCard(entry, onClick = { onEntryClick(entry) })
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

/** 一条动态卡片：UP 头像/名字/时间 + 文案 + 封面（视频显示标题与时长，图文仅展示）。 */
@Composable
private fun DynamicCard(entry: DynamicEntry, onClick: () -> Unit) {
    val clickable = if (entry.video != null) Modifier.clickable(onClick = onClick) else Modifier
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
                        Surface(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
                            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(3.dp),
                        ) {
                            Text(
                                video.durationText,
                                Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
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
                    if (entry.rawType == "DYNAMIC_TYPE_FORWARD") {
                        Text(
                            "转发动态",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        } else if (entry.coverUrl.isNotBlank()) {
            // 图文动态：仅展示首图
            AsyncImage(
                model = entry.coverUrl,
                contentDescription = null,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().size(width = 200.dp, height = 120.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
