package com.biliseek.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.ui.richtext.CommentComposer
import com.biliseek.app.ui.richtext.LocalEmotePanelHost
import com.biliseek.app.ui.richtext.RichText
import com.biliseek.app.ui.richtext.RichTextSource
import com.biliseek.designsystem.component.BsEmptyState
import com.biliseek.designsystem.component.BsErrorState
import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.component.BsTopBar
import com.biliseek.designsystem.theme.BsSpacing
import com.biliseek.core.network.dto.ReplyAtItem
import com.biliseek.core.network.canReplyInApp
import com.biliseek.core.network.listKey
import com.biliseek.core.network.replyOid
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
    val emoteHost = LocalEmotePanelHost.current
    LaunchedEffect(Unit) {
        onInit()
        // 回复与@ 接口不下发表情表：拉一次表情面板当字典（进程内只请求一次）
        emoteHost.ensureLoaded()
    }
    var pendingReply by remember { mutableStateOf<ReplyAtItem?>(null) }
    var replyDraft by remember { mutableStateOf("") }
    var replySubmitting by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        BsTopBar(title = "回复与@", onNavigate = onBack)

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
                        state.isLoading && state.items.isEmpty() -> item { BsLoadingState() }
                        state.errorMessage != null && state.items.isEmpty() -> item {
                            BsErrorState("加载失败：${state.errorMessage}", onRetry = onRetry)
                        }
                        state.items.isEmpty() -> item {
                            BsEmptyState("暂无回复与@", icon = Icons.Outlined.AlternateEmail)
                        }
                        else -> items(state.items, key = { it.listKey() }) { entry ->
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

    val target = pendingReply
    if (target != null) {
        ModalBottomSheet(onDismissRequest = { pendingReply = null }) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = BsSpacing.lg)) {
                    Text("回复 ${target.user?.name.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(BsSpacing.sm))
                    RichText(
                        RichTextSource(text = target.content),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CommentComposer(
                    content = replyDraft,
                    onContentChange = { replyDraft = it },
                    placeholder = "写下你的回复…",
                    isPosting = replySubmitting,
                    onSubmit = {
                        replySubmitting = true
                        onSubmitReply(
                            target.replyOid(),
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
                )
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
            val action = when {
                item.isAt -> "@了你"
                item.quotedContent.isNotBlank() -> "回复了你的评论"
                else -> "评论了你"
            }
            RichText(
                RichTextSource(text = item.content),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                prefix = { append("$action：") },
            )
            val context = if (item.isAt) item.title else item.quotedContent.ifBlank { item.title }
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
