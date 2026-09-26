package com.biliseek.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.data.RemoteListState
import com.biliseek.core.network.dto.ReplyAtItem
import com.biliseek.core.network.canReplyInApp
import com.biliseek.core.network.normalizeCoverUrl

/** "回复与@" 子页面：从消息中心图标进入。长按视频评论区的条目可站内直接回复。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyAtScreen(
    state: RemoteListState<ReplyAtItem>,
    onInit: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onPlayItem: (ReplyAtItem) -> Unit,
    onSubmitReply: (aid: Long, root: Long, parent: Long, content: String, onResult: (Boolean, String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onInit() }
    var pendingReply by remember { mutableStateOf<ReplyAtItem?>(null) }
    var replyDraft by remember { mutableStateOf("") }
    var replySubmitting by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "回复与@",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }

        val refreshState = rememberPullToRefreshState()
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = onRetry,
                state = refreshState,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    when {
                        state.isLoading && state.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        state.errorMessage != null && state.items.isEmpty() -> item {
                            Column(Modifier.fillMaxWidth().padding(24.dp), Arrangement.spacedBy(8.dp)) {
                                Text("加载失败：${state.errorMessage}", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onRetry) { Text("重试") }
                            }
                        }
                        state.items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                                Text("暂无内容")
                            }
                        }
                        else -> items(state.items, key = { it.id }) { entry ->
                            ReplyAtRow(
                                item = entry,
                                onClick = { onPlayItem(entry) },
                                onLongClick = if (entry.canReplyInApp()) {
                                    {
                                        pendingReply = entry
                                        replyDraft = ""
                                    }
                                } else {
                                    null
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (pendingReply != null) {
        ModalBottomSheet(onDismissRequest = { pendingReply = null }) {
            Column(Modifier.padding(16.dp)) {
                Text("回复 ${pendingReply?.user?.name.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("「${pendingReply?.content.orEmpty()}」", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = replyDraft,
                    onValueChange = { replyDraft = it },
                    placeholder = { Text("写下你的回复…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { pendingReply = null }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val target = pendingReply ?: return@TextButton
                            replySubmitting = true
                            onSubmitReply(
                                target.subjectId,
                                target.replyRootId,
                                target.replyParentId,
                                replyDraft,
                            ) { ok, _ ->
                                replySubmitting = false
                                if (ok) {
                                    pendingReply = null
                                    replyDraft = ""
                                }
                            }
                        },
                        enabled = !replySubmitting && replyDraft.isNotBlank(),
                    ) { Text(if (replySubmitting) "发送中…" else "发送") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReplyAtRow(
    item: ReplyAtItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = normalizeCoverUrl(item.user?.face.orEmpty()),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.user?.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val action = if (item.quotedContent.isNotBlank()) "回复了你的评论" else "评论了你"
            Text(
                "$action：${item.content}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val context = item.quotedContent.ifBlank { item.title }
            if (context.isNotBlank()) {
                Text(
                    context,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (item.replyTime > 0L) formatRelativeTime(item.replyTime * 1000) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
