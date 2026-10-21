package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
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
import com.biliseek.app.ui.components.CompactDragHandle
import com.biliseek.app.ui.components.miniBarContentPadding
import com.biliseek.app.ui.richtext.CommentComposer
import com.biliseek.app.ui.richtext.LocalEmotePanelHost
import com.biliseek.app.ui.richtext.RichText
import com.biliseek.app.ui.richtext.RichTextSource
import com.biliseek.designsystem.component.BsEmptyState
import com.biliseek.designsystem.component.BsErrorState
import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.component.BsTopBar
import com.biliseek.designsystem.theme.BsShapes
import com.biliseek.designsystem.theme.BsSpacing
import com.biliseek.core.network.dto.ReplyAtItem
import com.biliseek.core.network.canReplyInApp
import com.biliseek.core.network.listKey
import com.biliseek.core.network.replyOid
import com.biliseek.core.network.videoBvid
import com.biliseek.core.network.normalizeCoverUrl

/**
 * "回复与@" 子页面：从消息中心图标进入。点条目打开消息详情弹层（全文、被回复的评论、所在视频、回复框），
 * 点条目本身不播放；要播放点详情里的「播放视频」。
 */
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
    var detail by remember { mutableStateOf<ReplyAtItem?>(null) }
    var replyDraft by remember { mutableStateOf("") }
    var replySubmitting by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        BsTopBar(title = "回复与@", onNavigate = onBack)

        PullRefreshContainer(
            isRefreshing = state.isLoading && state.items.isNotEmpty(),
            errorMessage = state.errorMessage,
            successText = "回复与@已刷新",
            onRefresh = onRetry,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = miniBarContentPadding(bottom = 16.dp)) {
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
                            onClick = {
                                detail = entry
                                replyDraft = ""
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    val target = detail
    if (target != null) {
        ModalBottomSheet(
            onDismissRequest = { detail = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { CompactDragHandle() },
        ) {
            Column(Modifier.fillMaxWidth()) {
                ReplyAtDetail(
                    item = target,
                    onPlay = target.videoBvid()?.let {
                        {
                            detail = null
                            onPlayItem(target)
                        }
                    },
                )
                if (target.canReplyInApp()) {
                    CommentComposer(
                        content = replyDraft,
                        onContentChange = { replyDraft = it },
                        placeholder = "回复 ${target.user?.name.orEmpty()}…",
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
                                    detail = null
                                    replyDraft = ""
                                }
                            }
                        },
                    )
                } else {
                    Text(
                        "这类内容暂不支持站内回复",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = BsSpacing.lg, vertical = BsSpacing.md),
                    )
                }
            }
        }
    }
}

/** 消息详情：谁、做了什么、全文、被回复的我的评论、所在视频。[onPlay] 为 null 时不显示「播放视频」。 */
@Composable
private fun ReplyAtDetail(item: ReplyAtItem, onPlay: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(start = BsSpacing.lg, end = BsSpacing.lg, top = BsSpacing.sm, bottom = BsSpacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = normalizeCoverUrl(item.user?.face.orEmpty()),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(36.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(BsSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text(item.user?.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(item.actionText(), if (item.replyTime > 0L) formatRelativeTime(item.replyTime * 1000) else "")
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Spacer(Modifier.height(BsSpacing.sm))
        RichText(
            RichTextSource(text = item.content),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!item.isAt && item.quotedContent.isNotBlank()) {
            Spacer(Modifier.height(BsSpacing.sm))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = BsShapes.sm,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RichText(
                    RichTextSource(text = item.quotedContent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    prefix = { append("我的评论：") },
                    modifier = Modifier.padding(BsSpacing.sm),
                )
            }
        }
        if (item.title.isNotBlank() || onPlay != null) {
            Spacer(Modifier.height(BsSpacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onPlay != null) {
                    TextButton(onClick = onPlay) { Text("播放视频") }
                }
            }
        }
    }
}

private fun ReplyAtItem.actionText(): String = when {
    isAt -> "@了你"
    quotedContent.isNotBlank() -> "回复了你的评论"
    else -> "评论了你"
}

@Composable
private fun ReplyAtRow(
    item: ReplyAtItem,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
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
            val action = item.actionText()
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
