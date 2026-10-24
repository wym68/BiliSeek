package com.biliseek.app.ui.components

import android.net.Uri
import com.biliseek.app.ui.shouldAutoLoadMore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.biliseek.app.comment.CommentImage
import com.biliseek.app.comment.CommentThreadState
import com.biliseek.app.comment.canDeleteReply
import com.biliseek.app.comment.replyHintText
import com.biliseek.app.comment.visibleSubReplies
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.ui.formatRelativeTime
import com.biliseek.app.ui.richtext.CommentComposer
import com.biliseek.app.ui.richtext.RichText
import com.biliseek.app.ui.richtext.RichTextActions
import com.biliseek.app.ui.richtext.RichTextSource
import com.biliseek.core.network.dto.ReplyItem
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.designsystem.component.BsConfirmDialog
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
 * 正文走 [RichText]：表情图、@ 高亮、蓝字关键词、链接；[onOpenUser] 非空时 @、头像、昵称都可点进空间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicCommentDialog(
    state: CommentThreadState,
    onSortChange: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit = {},
    onLike: (ReplyItem) -> Unit,
    onReply: (ReplyItem, Long?) -> Unit,
    onExpandSubReplies: (ReplyItem) -> Unit,
    onLoadMoreSubReplies: (Long) -> Unit,
    onCollapseSubReplies: (Long) -> Unit = {},
    onContentChange: (String) -> Unit,
    onClearReplyTarget: () -> Unit,
    onComposerFocused: () -> Unit = {},
    onAddImages: (List<Uri>) -> Unit = {},
    onRemoveImage: (CommentImage) -> Unit = {},
    /** 非 null = 显示截图按钮（播放页评论区、正在看画面时）。 */
    onCaptureFrame: (() -> Unit)? = null,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    /** 当前登录用户 mid；0 = 未登录（不显示删除）。 */
    myMid: Long = 0,
    onDelete: (ReplyItem, Long?) -> Unit = { _, _ -> },
    onSaveImage: (String) -> Unit = {},
    onOpenUser: ((Long) -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    val comments = state.comments
    val sort = state.sort
    val replyTargetUname = state.replyTarget?.uname
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val actions = RichTextActions(onMention = onOpenUser)
    var pendingDelete by remember { mutableStateOf<Pair<ReplyItem, Long?>?>(null) }
    var preview by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    val openImages: (List<String>, Int) -> Unit = { pics, index -> preview = pics to index }
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
                SortChip("热度", selected = sort == 1) {
                    if (sort != 1) {
                        listState.requestScrollToItem(0)
                        onSortChange(1)
                    }
                }
                SortChip("时间", selected = sort == 0) {
                    if (sort != 0) {
                        listState.requestScrollToItem(0)
                        onSortChange(0)
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
            HorizontalDivider()

            when {
                comments.isLoading && comments.items.isEmpty() && state.topReplies.isEmpty() -> BsLoadingState(Modifier.weight(1f))

                comments.items.isEmpty() && state.topReplies.isEmpty() -> BsEmptyState(
                    comments.errorMessage ?: "还没有评论，来说两句吧",
                    Modifier.weight(1f),
                    icon = Icons.Outlined.ChatBubbleOutline,
                    action = if (comments.errorMessage != null && state.listErrorRetryable) {
                        { TextButton(onClick = onRetry) { Text("重试") } }
                    } else null,
                )

                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                    items(state.topReplies, key = { "top-${it.rpid}" }) { reply ->
                        CommentItemRow(
                            reply = reply,
                            pinned = true,
                            upMid = state.upMid,
                            subReplies = state.visibleSubReplies(reply.rpid),
                            actions = actions,
                            onLike = onLike,
                            onReply = onReply,
                            onExpandSubReplies = { onExpandSubReplies(reply) },
                            onLoadMoreSubReplies = { onLoadMoreSubReplies(reply.rpid) },
                            onCollapseSubReplies = { onCollapseSubReplies(reply.rpid) },
                            myMid = myMid,
                            onDelete = { r, root -> pendingDelete = r to root },
                            onOpenImages = openImages,
                            onOpenUser = onOpenUser,
                        )
                        CommentDivider()
                    }
                    items(comments.items, key = { "c-${it.rpid}" }) { reply ->
                        CommentItemRow(
                            reply = reply,
                            pinned = false,
                            upMid = state.upMid,
                            subReplies = state.visibleSubReplies(reply.rpid),
                            actions = actions,
                            onLike = onLike,
                            onReply = onReply,
                            onExpandSubReplies = { onExpandSubReplies(reply) },
                            onLoadMoreSubReplies = { onLoadMoreSubReplies(reply.rpid) },
                            onCollapseSubReplies = { onCollapseSubReplies(reply.rpid) },
                            myMid = myMid,
                            onDelete = { r, root -> pendingDelete = r to root },
                            onOpenImages = openImages,
                            onOpenUser = onOpenUser,
                        )
                        CommentDivider()
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
                replyHint = state.replyTarget?.let(::replyHintText),
                onClearReply = onClearReplyTarget,
                requestFocus = state.focusComposer,
                onFocusRequestHandled = onComposerFocused,
                images = state.images,
                onAddImages = onAddImages,
                onRemoveImage = onRemoveImage,
                statusText = state.postingStatus,
                onCaptureFrame = onCaptureFrame,
                mentionsEnabled = true,
            )
        }
        pendingDelete?.let { (reply, root) ->
            BsConfirmDialog(
                title = "删除评论",
                text = "删除后不能恢复。",
                confirmLabel = "删除",
                destructive = true,
                onConfirm = {
                    pendingDelete = null
                    onDelete(reply, root)
                },
                onDismiss = { pendingDelete = null },
            )
        }
        // 弹层有自己的窗口，主窗口里的全局图片预览会被挡住；这里另开一个 Dialog 窗口盖在弹层上
        preview?.let { (pics, index) ->
            Dialog(
                onDismissRequest = { preview = null },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                ImagePreviewDialog(
                    pics = pics,
                    initialIndex = index,
                    onDismiss = { preview = null },
                    onSaveImage = onSaveImage,
                )
            }
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

@Composable
private fun CommentDivider() {
    HorizontalDivider(
        Modifier.padding(start = BsSpacing.lg + BsSize.avatarSm + BsSpacing.sm),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 作者本人的评论（标「UP」）。 */
private fun isUpReply(reply: ReplyItem, upMid: Long): Boolean = upMid > 0 && reply.mid == upMid

/** 昵称后面的小标签：「UP」「置顶」。 */
@Composable
private fun CommentTag(text: String) {
    val color = MaterialTheme.colorScheme.primary
    Text(
        text,
        Modifier
            .padding(start = BsSpacing.xs)
            .border(1.dp, color, BsShapes.xs)
            .padding(horizontal = BsSpacing.xxs),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

/** 一条一级评论：头像/昵称/时间/内容 + 回复/点赞 + 楼中楼预览与展开。 */
@Composable
private fun CommentItemRow(
    reply: ReplyItem,
    pinned: Boolean,
    upMid: Long,
    subReplies: RemoteListState<ReplyItem>?,
    actions: RichTextActions,
    onLike: (ReplyItem) -> Unit,
    onReply: (ReplyItem, Long?) -> Unit,
    onExpandSubReplies: () -> Unit,
    onLoadMoreSubReplies: () -> Unit,
    onCollapseSubReplies: () -> Unit,
    myMid: Long,
    onDelete: (ReplyItem, Long?) -> Unit,
    onOpenImages: (List<String>, Int) -> Unit,
    onOpenUser: ((Long) -> Unit)?,
) {
    val openUser: (() -> Unit)? = onOpenUser?.takeIf { reply.mid > 0 }?.let { open -> { open(reply.mid) } }
    val userClick = if (openUser != null) Modifier.clickable(onClick = openUser) else Modifier
    Row(
        Modifier.fillMaxWidth().padding(start = BsSpacing.lg, end = BsSpacing.sm, top = BsSpacing.md, bottom = BsSpacing.xs),
    ) {
        AsyncImage(
            model = reply.member.avatar,
            contentDescription = null,
            modifier = Modifier
                .size(BsSize.avatarSm)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(userClick),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.padding(start = BsSpacing.sm).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    reply.member.uname,
                    modifier = userClick,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isUpReply(reply, upMid)) CommentTag("UP")
                if (pinned) CommentTag("置顶")
            }
            SelectionContainer(Modifier.padding(top = BsSpacing.xs)) {
                RichText(
                    source = reply.content.toRichSource(),
                    style = MaterialTheme.typography.bodyMedium,
                    actions = actions,
                )
            }
            CommentPictures(reply, onOpenImages)
            CommentActionRow(
                reply = reply,
                onReply = { onReply(reply, null) },
                onLike = { onLike(reply) },
                onDelete = if (canDeleteReply(reply, myMid)) ({ onDelete(reply, null) }) else null,
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
                    if (subReplies != null) {
                        CollapseSubRepliesText(onCollapseSubReplies, Modifier.align(Alignment.End))
                    }
                    preview.forEach { sub ->
                        SubReplyRow(
                            sub = sub,
                            upMid = upMid,
                            actions = actions,
                            onReply = { onReply(sub, reply.rpid) },
                            onLike = { onLike(sub) },
                            onDelete = if (canDeleteReply(sub, myMid)) ({ onDelete(sub, reply.rpid) }) else null,
                            onOpenImages = onOpenImages,
                            onOpenUser = onOpenUser,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
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
                        if (subReplies != null) CollapseSubRepliesText(onCollapseSubReplies)
                    }
                }
            }
        }
    }
}

/** 楼中楼的「收起」（开头、末尾各一个，楼中楼很长时不用滑到底）。 */
@Composable
private fun CollapseSubRepliesText(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        "收起",
        modifier.clickable(onClick = onClick),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 时间 + 回复 + 点赞（一级评论和楼中楼共用）。 */
@Composable
private fun CommentActionRow(
    reply: ReplyItem,
    onReply: () -> Unit,
    onLike: () -> Unit,
    onDelete: (() -> Unit)? = null,
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
        if (onDelete != null) {
            BsIconAction(
                icon = Icons.Outlined.Delete,
                label = "删除",
                onClick = onDelete,
                showLabelWhenEmpty = false,
            )
        }
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
    upMid: Long,
    actions: RichTextActions,
    onReply: () -> Unit,
    onLike: () -> Unit,
    onDelete: (() -> Unit)?,
    onOpenImages: (List<String>, Int) -> Unit,
    onOpenUser: ((Long) -> Unit)?,
) {
    val unameColor = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer {
            RichText(
                source = sub.content.toRichSource(),
                style = MaterialTheme.typography.bodySmall,
                actions = actions,
                prefix = {
                    val name: AnnotatedString.Builder.() -> Unit = {
                        withStyle(SpanStyle(color = unameColor)) {
                            append(sub.member.uname)
                            if (isUpReply(sub, upMid)) append(" UP")
                        }
                    }
                    if (onOpenUser != null && sub.mid > 0) {
                        withLink(LinkAnnotation.Clickable(tag = "user:${sub.mid}") { onOpenUser(sub.mid) }) { name() }
                    } else {
                        name()
                    }
                    append("：")
                },
            )
        }
        CommentPictures(sub, onOpenImages)
        CommentActionRow(reply = sub, onReply = onReply, onLike = onLike, onDelete = onDelete)
    }
}

/** 评论正文 → 富文本素材。 */
internal fun com.biliseek.core.network.dto.ReplyContent.toRichSource(): RichTextSource = RichTextSource(
    text = message,
    emotes = emote,
    mentions = members.mapNotNull { m -> m.uname.takeIf { it.isNotBlank() }?.let { it to (m.mid.toLongOrNull() ?: 0L) } }.toMap(),
    keywords = jumpKeywords,
)

/** 评论图片：用动态同款九宫格，点开全屏预览。没有图什么都不画。 */
@Composable
private fun CommentPictures(reply: ReplyItem, onOpenImages: (List<String>, Int) -> Unit) {
    val pics = reply.content.pictureUrls()
    if (pics.isEmpty()) return
    DrawGrid(
        pics = pics,
        onImageClick = { index -> onOpenImages(pics, index) },
        modifier = Modifier.padding(top = BsSpacing.xs),
    )
}

/** 评论图片地址：与动态图片同样经 [normalizeCoverUrl]（`http://` 会被 Android 9+ 拦成占位框），去掉空串。 */
internal fun com.biliseek.core.network.dto.ReplyContent.pictureUrls(): List<String> =
    pictures.orEmpty().map { normalizeCoverUrl(it.imgSrc) }.filter { it.isNotBlank() }
