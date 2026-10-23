package com.biliseek.app.ui.components

import com.biliseek.app.ui.shouldAutoLoadMore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.comment.CommentThreadState
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.ui.formatRelativeTime
import com.biliseek.app.ui.richtext.CommentComposer
import com.biliseek.app.ui.richtext.RichText
import com.biliseek.app.ui.richtext.RichTextActions
import com.biliseek.app.ui.richtext.RichTextSource
import com.biliseek.core.network.dto.ReplyItem
import com.biliseek.designsystem.component.BsEmptyState
import com.biliseek.designsystem.component.BsIconAction
import com.biliseek.designsystem.component.BsLoadMoreFooter
import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.theme.BsShapes
import com.biliseek.designsystem.theme.BsSize
import com.biliseek.designsystem.theme.BsSpacing

/**
 * 评论列表 Sheet（动态评论与播放页评论共用）。列表 + 楼中楼预览 + 排序 + 输入栏（含表情面板）。
 * 一级评论和楼中楼都能点赞 / 回复；一级评论可展开全部楼中楼。
 *
 * 正文走 [RichText]：表情图、@ 高亮（[onOpenUser] 非空时可点进空间）、蓝字关键词、链接。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicCommentDialog(
    state: CommentThreadState,
    onSortChange: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onLike: (ReplyItem) -> Unit,
    onReply: (ReplyItem, Long?) -> Unit,
    onExpandSubReplies: (ReplyItem) -> Unit,
    onLoadMoreSubReplies: (Long) -> Unit,
    onContentChange: (String) -> Unit,
    onClearReplyTarget: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    onOpenUser: ((Long) -> Unit)? = null,
) {
    val comments = state.comments
    val sort = state.sort
    val replyTargetUname = state.replyTarget?.uname
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val actions = RichTextActions(onMention = onOpenUser)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { CompactDragHandle() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = BsSpacing.lg, end = BsSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "评论",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                SortChip("热度", selected = sort == 1) { onSortChange(1) }
                SortChip("时间", selected = sort == 0) { onSortChange(0) }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
            HorizontalDivider()

            when {
                comments.isLoading && comments.items.isEmpty() -> BsLoadingState(Modifier.weight(1f))

                comments.items.isEmpty() -> BsEmptyState(
                    comments.errorMessage ?: "还没有评论，来说两句吧",
                    Modifier.weight(1f),
                    icon = Icons.Outlined.ChatBubbleOutline,
                )

                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(comments.items, key = { it.rpid }) { reply ->
                        CommentItemRow(
                            reply = reply,
                            subReplies = state.expandedSubReplies[reply.rpid],
                            actions = actions,
                            onLike = onLike,
                            onReply = onReply,
                            onExpandSubReplies = { onExpandSubReplies(reply) },
                            onLoadMoreSubReplies = { onLoadMoreSubReplies(reply.rpid) },
                        )
                        HorizontalDivider(
                            Modifier.padding(start = BsSpacing.lg + BsSize.avatarSm + BsSpacing.sm),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    if (comments.hasMore || comments.isLoadingMore) {
                        item(key = "comment_footer") {
                            // 失败后不自动再来（否则会一直重打 B 站），等用户点「重试」
                            if (shouldAutoLoadMore(comments.hasMore, comments.isLoadingMore, comments.errorMessage)) {
                                LaunchedEffect(comments.items.size) { onLoadMore() }
                            }
                            // 没失败就一直转圈（自动加载紧接着就会开始）；失败了显示原因和「重试」
                            BsLoadMoreFooter(
                                isLoadingMore = comments.errorMessage == null,
                                errorMessage = comments.errorMessage,
                                onRetry = onLoadMore,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            CommentComposer(
                content = state.content,
                onContentChange = onContentChange,
                onSubmit = onSubmit,
                placeholder = if (replyTargetUname != null) "回复…" else "写评论…",
                isPosting = state.isPosting,
                replyHint = replyTargetUname?.let { "回复 @$it" },
                onClearReply = onClearReplyTarget,
            )
        }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.padding(start = BsSpacing.xs),
    )
}

/** 一条一级评论：头像/昵称/时间/内容 + 回复/点赞 + 楼中楼预览与展开。 */
@Composable
private fun CommentItemRow(
    reply: ReplyItem,
    subReplies: RemoteListState<ReplyItem>?,
    actions: RichTextActions,
    onLike: (ReplyItem) -> Unit,
    onReply: (ReplyItem, Long?) -> Unit,
    onExpandSubReplies: () -> Unit,
    onLoadMoreSubReplies: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = BsSpacing.lg, end = BsSpacing.sm, top = BsSpacing.md, bottom = BsSpacing.xs),
    ) {
        AsyncImage(
            model = reply.member.avatar,
            contentDescription = null,
            modifier = Modifier
                .size(BsSize.avatarSm)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.padding(start = BsSpacing.sm).weight(1f)) {
            Text(
                reply.member.uname,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RichText(
                source = reply.content.toRichSource(),
                modifier = Modifier.padding(top = BsSpacing.xs),
                style = MaterialTheme.typography.bodyMedium,
                actions = actions,
            )
            CommentActionRow(
                reply = reply,
                onReply = { onReply(reply, null) },
                onLike = { onLike(reply) },
            )
            if (reply.rcount > 0) {
                val preview = subReplies?.items ?: reply.replies.orEmpty()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = BsSpacing.xs)
                        .clip(BsShapes.sm)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(BsSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(BsSpacing.xs),
                ) {
                    preview.forEach { sub ->
                        SubReplyRow(
                            sub = sub,
                            actions = actions,
                            onReply = { onReply(sub, reply.rpid) },
                            onLike = { onLike(sub) },
                        )
                    }
                    when {
                        subReplies == null -> Text(
                            "查看全部 ${reply.rcount} 条回复",
                            Modifier.clickable(onClick = onExpandSubReplies),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        subReplies.isLoading || subReplies.isLoadingMore ->
                            CircularProgressIndicator(Modifier.size(BsSize.iconSm), strokeWidth = 2.dp)
                        subReplies.errorMessage != null -> Text(
                            "加载失败，点击重试",
                            Modifier.clickable(onClick = if (subReplies.items.isEmpty()) onExpandSubReplies else onLoadMoreSubReplies),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        subReplies.hasMore -> Text(
                            "加载更多回复",
                            Modifier.clickable(onClick = onLoadMoreSubReplies),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 时间 + 回复 + 点赞（一级评论和楼中楼共用）。 */
@Composable
private fun CommentActionRow(
    reply: ReplyItem,
    onReply: () -> Unit,
    onLike: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = BsSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatRelativeTime(reply.ctime * 1000),
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        BsIconAction(
            icon = Icons.Outlined.ChatBubbleOutline,
            label = "回复",
            onClick = onReply,
            showLabelWhenEmpty = false,
        )
        BsIconAction(
            icon = Icons.Outlined.ThumbUp,
            activeIcon = Icons.Filled.ThumbUp,
            label = "点赞",
            count = reply.like.toLong(),
            active = reply.action == 1,
            onClick = onLike,
            showLabelWhenEmpty = false,
        )
    }
}

/** 楼中楼一条：「昵称：正文」+ 时间、回复、点赞。 */
@Composable
private fun SubReplyRow(
    sub: ReplyItem,
    actions: RichTextActions,
    onReply: () -> Unit,
    onLike: () -> Unit,
) {
    val unameColor = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        RichText(
            source = sub.content.toRichSource(),
            style = MaterialTheme.typography.bodySmall,
            actions = actions,
            prefix = {
                withStyle(SpanStyle(color = unameColor)) {
                    append(sub.member.uname)
                }
                append("：")
            },
        )
        CommentActionRow(reply = sub, onReply = onReply, onLike = onLike)
    }
}


/** 评论正文 → 富文本素材。 */
internal fun com.biliseek.core.network.dto.ReplyContent.toRichSource(): RichTextSource = RichTextSource(
    text = message,
    emotes = emote,
    mentions = members.mapNotNull { m -> m.uname.takeIf { it.isNotBlank() }?.let { it to (m.mid.toLongOrNull() ?: 0L) } }.toMap(),
    keywords = jumpKeywords,
)
