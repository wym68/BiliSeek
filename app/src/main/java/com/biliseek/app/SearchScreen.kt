package com.biliseek.app

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.stripHighlight

@Composable
fun SearchScreen(
    state: SearchUiState,
    onKeywordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResultClick: (SearchVideo) -> Unit,
    onLoadMore: () -> Unit,
    onPageClick: (PageEntry) -> Unit,
    onDismissPages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

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

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.keyword,
                onValueChange = onKeywordChange,
                label = { Text("搜索全 B 站，或粘贴 BV 号 / 链接") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            )
            Button(onClick = onSubmit) { Text("搜索") }
        }

        state.error?.let { message ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (state.totalResults > 0) {
            Text(
                "约 ${state.totalResults} 个结果 · 第 ${state.page}/${state.totalPages} 页",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // 多 P 展开：一个 BV 在结果里只占一条，点入才展开成列表
        if (state.pages.isNotEmpty()) {
            MultiPagePanel(state, onPageClick, onDismissPages)
            return@Column
        }

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            state.items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("输入关键词开始搜索", style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.items, key = { it.bvid }) { video ->
                    ResultRow(video) { onResultClick(video) }
                    HorizontalDivider()
                }
                if (state.isLoadingMore) {
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
private fun ResultRow(video: SearchVideo, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stripHighlight(video.title),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(video.author, style = MaterialTheme.typography.labelSmall)
            Text(video.duration, style = MaterialTheme.typography.labelSmall)
            Text("${video.play} 播放", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MultiPagePanel(
    state: SearchUiState,
    onPageClick: (PageEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${state.pagesOfBvid} · ${state.pages.size} 个分 P",
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton(onClick = onDismiss) { Text("返回结果") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.pages, key = { it.cid }) { entry ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPageClick(entry) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text("P${entry.page}  ${entry.title}", style = MaterialTheme.typography.bodyMedium)
                    Text("${entry.durationSec}s", style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            }
        }
    }
}
