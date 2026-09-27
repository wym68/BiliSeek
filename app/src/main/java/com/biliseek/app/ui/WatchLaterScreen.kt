package com.biliseek.app.ui

import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.component.BsErrorState
import com.biliseek.designsystem.component.BsEmptyState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import com.biliseek.designsystem.component.BsTopBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.LoginPanel
import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.LoginState
import com.biliseek.core.network.dto.WatchLaterItem
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.player.formatDuration

/**
 * 稍后再看页（M12）。远程列表，需登录。
 *
 * - 未登录：内嵌 [LoginPanel] 引导（复用设置页同一组件）
 * - 加载中 / 失败可重试 / 空列表：如实呈现
 * - 有数据：封面 + 标题 + UP + 进度，点击播放
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchLaterScreen(
    state: RemoteListState<WatchLaterItem>,
    session: BiliSession,
    loginState: LoginState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onItemClick: (WatchLaterItem) -> Unit,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var refreshMessage by remember { mutableStateOf<String?>(null) }
    var refreshIsError by remember { mutableStateOf(false) }

    // 监听刷新状态变化，显示提示
    LaunchedEffect(state.isLoading, state.errorMessage) {
        if (!state.isLoading && state.items.isNotEmpty()) {
            if (state.errorMessage != null) {
                refreshMessage = "刷新失败：${state.errorMessage}"
                refreshIsError = true
            } else if (refreshMessage == null) {
                // 首次加载完成不显示提示
            } else {
                refreshMessage = "稍后再看已刷新"
                refreshIsError = false
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        BsTopBar(title = "稍后再看", onNavigate = onClose)

        val refreshState = rememberPullToRefreshState()
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.isLoading && state.items.isEmpty(),
                onRefresh = onRetry,
                state = refreshState,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    !session.isLoggedIn -> {
                        Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                            LoginPanel(
                                loginState = loginState,
                                session = session,
                                onStart = onStartLogin,
                                onCancel = onCancelLogin,
                                onLogout = {},
                            )
                        }
                    }

                    state.isLoading && state.items.isEmpty() -> BsLoadingState()

                    state.errorMessage != null && state.items.isEmpty() ->
                        BsErrorState(state.errorMessage, onRetry = onRetry)

                    state.items.isEmpty() -> BsEmptyState("稍后再看是空的", icon = Icons.Outlined.Schedule)

                    else -> {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(state.items, key = { it.bvid }) { item ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onItemClick(item) }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CoverThumbnail(normalizeCoverUrl(item.pic), width = 96.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            item.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            buildString {
                                                item.owner?.name?.takeIf(String::isNotBlank)?.let {
                                                    append(it); append(" · ")
                                                }
                                                append(formatDuration(item.duration * 1000))
                                                if (item.progress > 0) {
                                                    append(" · 播到 ")
                                                    append(formatDuration(item.progress * 1000))
                                                }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
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
}
