package com.biliseek.app.ui.components

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
 * 楼中楼展开后只读（不支持对楼中楼再点赞/回复），一级评论支持点赞/回复/展开。
 *
 * 正文走 [RichText]：表情图、@ 高亮（[onOpenUser] 非空时可点进空间）、蓝字关键词、链接。
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
    onOpenUser: ((Long) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val actions = RichTextActions(onMention = onOpenUser)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
                            subReplies = expandedSubReplies[reply.rpid],
                            actions = actions,
                            onLike = { onLike(reply, null) },
                            onReply = { onReply(reply, null) },
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
                            if (comments.hasMore && !comments.isLoadingMore) {
                                LaunchedEffect(comments.items.size) { onLoadMore() }
                            }
                            BsLoadMoreFooter(
                                isLoadingMore = true,
                                errorMessage = null,
                                onRetry = onLoadMore,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            CommentComposer(
                content = content,
                onContentChange = onContentChange,
                onSubmit = onSubmit,
                placeholder = if (replyTargetUname != null) "回复…" else "写评论…",
                isPosting = isPosting,
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
    onLike: () -> Unit,
    onReply: () -> Unit,
    onExpandSubReplies: () -> Unit,
    onLoadMoreSubReplies: () -> Unit,
) {
    val liked = reply.action == 1
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
                    active = liked,
                    onClick = onLike,
                    showLabelWhenEmpty = false,
                )
            }
            if (reply.rcount > 0) {
                val preview = subReplies?.items ?: reply.replies.orEmpty()
                val unameColor = MaterialTheme.colorScheme.primary
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

/** 评论正文 → 富文本素材。 */
internal fun com.biliseek.core.network.dto.ReplyContent.toRichSource(): RichTextSource = RichTextSource(
    text = message,
    emotes = emote,
    mentions = members.mapNotNull { m -> m.uname.takeIf { it.isNotBlank() }?.let { it to (m.mid.toLongOrNull() ?: 0L) } }.toMap(),
    keywords = jumpKeywords,
)
