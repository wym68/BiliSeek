package com.biliseek.app.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.LoginPanel
import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.LoginState
import com.biliseek.core.network.dto.SeriesItem
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.player.formatDuration

/**
 * 合集列表页（M13-N）。订阅合集列表。
 *
 * - 未登录：内嵌 [LoginPanel] 引导（复用设置页同一组件）
 * - 加载中 / 失败可重试 / 空列表：如实呈现
 * - 有数据：封面 + 标题 + 描述，点击进入合集内容页
 */
@Composable
fun SeriesListScreen(
    state: RemoteListState<SeriesItem>,
    session: BiliSession,
    loginState: LoginState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onOpenSeries: (seriesId: Long, title: String) -> Unit,
    onItemClick: (SeriesItem) -> Unit,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("合集", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("返回") }
        }

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

            state.isLoading && state.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            }

            state.errorMessage != null && state.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                        Button(onClick = onRetry) { Text("重试") }
                    }
                }
            }

            state.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("还没有订阅合集", style = MaterialTheme.typography.bodySmall)
                }
            }

            else -> {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(state.items, key = { it.id }) { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onItemClick(item) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverThumbnail(normalizeCoverUrl(item.cover), width = 96.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                item.description.takeIf(String::isNotBlank)?.let { desc ->
                                    Text(
                                        desc,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}