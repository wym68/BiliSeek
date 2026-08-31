package com.biliseek.app.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.dto.SeriesArchive
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.player.formatDuration

/**
 * 合集内容页（M13-N）。分页拉取，滚动到底自动加载下一页。
 *
 * 结构与 [FavoriteDetail] 一致。
 */
@Composable
fun SeriesScreen(
    state: RemoteListState<SeriesArchive>,
    onClose: () -> Unit,
    onItemClick: (SeriesArchive) -> Unit,
    onLoadMore: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${state.title} · ${state.items.size} 首",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onClose) { Text("返回") }
        }

        when {
            state.isLoading && state.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.errorMessage != null && state.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                        Button(onClick = { onLoadMore(1) }) { Text("重试") }
                    }
                }
            }

            state.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("这个合集是空的", style = MaterialTheme.typography.bodySmall)
                }
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.items, key = { it.bvid }) { archive ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onItemClick(archive) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverThumbnail(normalizeCoverUrl(archive.cover), width = 96.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    archive.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    archive.author + "  " +
                                        formatDuration(archive.duration * 1000),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        HorizontalDivider()
                    }

                    if (state.hasMore || state.isLoadingMore) {
                        item(key = "footer") {
                            if (state.hasMore && !state.isLoadingMore) {
                                LaunchedEffect(state.page) { onLoadMore(state.page + 1) }
                            }
                            Box(
                                Modifier.fillMaxWidth().padding(12.dp),
                                Alignment.Center,
                            ) {
                                if (state.isLoadingMore) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    state.errorMessage?.let { msg ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                msg,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                            TextButton(onClick = { onLoadMore(state.page + 1) }) {
                                                Text("重试")
                                            }
                                        }
                                    } ?: Text(
                                        "加载中…",
                                        style = MaterialTheme.typography.labelSmall,
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