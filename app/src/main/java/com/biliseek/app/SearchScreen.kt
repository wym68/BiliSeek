package com.biliseek.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.ui.CoverThumbnail
import com.biliseek.core.network.SearchError
import com.biliseek.core.network.retryable
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.network.stripHighlight
import com.biliseek.core.player.formatDuration

@Composable
fun SearchScreen(
    state: SearchUiState,
    onKeywordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResultClick: (SearchVideo) -> Unit,
    onAddToPlaylist: (SearchVideo) -> Unit,
    onPlayNext: (SearchVideo) -> Unit,
    onCopyLink: (SearchVideo) -> Unit,
    onShowPages: (SearchVideo) -> Unit,
    onLoadMore: () -> Unit,
    onPageClick: (PageEntry) -> Unit,
    onDismissPages: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    /** 无搜索时展示的首页空状态 */
    emptyContent: @Composable () -> Unit = {},
) {
    val listState = rememberLazyListState()

    // 长按不再是「直接加歌单」这一个动作，而是打开一组动作
    var actionsFor by remember { mutableStateOf<SearchVideo?>(null) }
    actionsFor?.let { video ->
        ResultActionSheet(
            video = video,
            onDismiss = { actionsFor = null },
            onAddToPlaylist = { actionsFor = null; onAddToPlaylist(video) },
            onPlayNext = { actionsFor = null; onPlayNext(video) },
            onCopyLink = { actionsFor = null; onCopyLink(video) },
            onShowPages = { actionsFor = null; onShowPages(video) },
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

        // 已有结果时，失败（多为加载下一页）收窄成顶部条，不遮挡已经拿到的内容
        if (state.items.isNotEmpty()) {
            state.error?.let { error ->
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
            // 修白屏：有关键词却 0 结果时，早期条件把这一支漏成了空白页
            state.items.isEmpty() && state.error != null -> Box(
                Modifier.fillMaxSize(),
                Alignment.Center,
            ) {
                ErrorPanel(state.error, onRetry)
            }
            state.items.isEmpty() -> Box(Modifier.fillMaxSize()) { emptyContent() }
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.items, key = { it.bvid }) { video ->
                    ResultRow(
                        video = video,
                        onClick = { onResultClick(video) },
                        onLongClick = { actionsFor = video },
                    )
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

/** 长按搜索结果弹出的操作菜单。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ResultActionSheet(
    video: SearchVideo,
    onDismiss: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    onCopyLink: () -> Unit,
    onShowPages: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                stripHighlight(video.title),
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider()
            SheetItem("加入歌单", onAddToPlaylist)
            SheetItem("下一首播放", onPlayNext)
            SheetItem("复制链接", onCopyLink)
            SheetItem("查看分 P", onShowPages)
        }
    }
}

@Composable
private fun SheetItem(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
}

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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(video: SearchVideo, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // 长按弹操作菜单
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverThumbnail(normalizeCoverUrl(video.pic))
        Column(
            Modifier.weight(1f),
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
                    Text(
                        formatDuration(entry.durationSec * 1000),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
