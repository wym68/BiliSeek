package com.biliseek.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.ui.formatRelativeTime
import com.biliseek.core.network.dto.ReplyItem

/**
 * 动态评论列表 Sheet（Ch4 §4.1.4 评论）。列表 + 楼中楼预览 + 排序 + 输入框。
 * 楼中楼展开后只读（不支持对楼中楼再点赞/回复），一级评论支持点赞/回复/展开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicCommentDialog(
    comments: RemoteListState<ReplyItem>,
    sort: Int,
    replyTargetUname: String?,
    content: String,
    isPosting: Boolean,
    expandedSubReplies: Map<Long, RemoteListState<ReplyItem>>,
    onSortChange: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onLike: (ReplyItem, Long?) -> Unit,
    onReply: (ReplyItem, Long?) -> Unit,
    onExpandSubReplies: (ReplyItem) -> Unit,
    onLoadMoreSubReplies: (Long) -> Unit,
    onContentChange: (String) -> Unit,
    onClearReplyTarget: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("💬 评论", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "热度",
                        Modifier.clickable { onSortChange(1) },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (sort == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "时间",
                        Modifier.clickable { onSortChange(0) },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (sort == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "关闭",
                        Modifier.clickable(onClick = onDismiss),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            HorizontalDivider()

            when {
                comments.isLoading && comments.items.isEmpty() -> Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    Alignment.Center,
                ) { CircularProgressIndicator() }

                comments.items.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                    Text(
                        comments.errorMessage ?: "还没有评论，来说两句吧",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(comments.items, key = { it.rpid }) { reply ->
                        CommentItemRow(
                            reply = reply,
                            subReplies = expandedSubReplies[reply.rpid],
                            onLike = { onLike(reply, null) },
                            onReply = { onReply(reply, null) },
                            onExpandSubReplies = { onExpandSubReplies(reply) },
                            onLoadMoreSubReplies = { onLoadMoreSubReplies(reply.rpid) },
                        )
                        HorizontalDivider()
                    }
                    if (comments.hasMore || comments.isLoadingMore) {
                        item(key = "comment_footer") {
                            if (comments.hasMore && !comments.isLoadingMore) {
                                LaunchedEffect(comments.items.size) { onLoadMore() }
                            }
                            Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                                if (comments.isLoadingMore) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("加载中…", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            if (replyTargetUname != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("回复 @$replyTargetUname", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "取消",
                        Modifier.clickable(onClick = onClearReplyTarget),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                label = { Text(if (replyTargetUname != null) "回复…" else "写评论…") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                minLines = 2,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onSubmit, enabled = !isPosting && content.isNotBlank()) {
                    Text(if (isPosting) "发送中…" else "发送")
                }
            }
        }
    }
}

/** 一条一级评论：头像/昵称/时间/内容 + 回复/点赞 + 楼中楼预览与展开。 */
@Composable
private fun CommentItemRow(
    reply: ReplyItem,
    subReplies: RemoteListState<ReplyItem>?,
    onLike: () -> Unit,
    onReply: () -> Unit,
    onExpandSubReplies: () -> Unit,
    onLoadMoreSubReplies: () -> Unit,
) {
    val liked = reply.action == 1
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = reply.member.avatar,
                contentDescription = null,
                modifier = Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(reply.member.uname, style = MaterialTheme.typography.labelLarge)
                Text(formatRelativeTime(reply.ctime * 1000), style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(reply.content.message, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "回复",
                Modifier.clickable(onClick = onReply),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${reply.like} 赞",
                Modifier.clickable(onClick = onLike),
                style = MaterialTheme.typography.labelSmall,
                color = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (reply.rcount > 0) {
            val preview = subReplies?.items ?: reply.replies.orEmpty()
            if (preview.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(8.dp),
                ) {
                    preview.forEach { sub ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                "${sub.member.uname}: ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(sub.content.message, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (subReplies?.hasMore == true) {
                        Text(
                            if (subReplies.isLoadingMore) "加载中…" else "加载更多回复",
                            Modifier.clickable(enabled = !subReplies.isLoadingMore, onClick = onLoadMoreSubReplies),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Text(
                "查看全部回复(${reply.rcount})",
                Modifier
                    .padding(top = 4.dp)
                    .clickable(enabled = subReplies?.isLoading != true, onClick = onExpandSubReplies),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
