package com.biliseek.app.ui.components

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.biliseek.app.comment.CommentThreadController

/** 把一个 [CommentThreadController] 接到评论弹层上；控制器状态为 null（已关闭）时什么都不画。 */
@Composable
fun CommentThreadSheet(
    controller: CommentThreadController,
    myMid: Long,
    onSaveImage: (String) -> Unit,
    onOpenUser: (Long) -> Unit,
) {
    val state by controller.state.collectAsState()
    val current = state
    // 放在 return 之前：弹层藏起（跳去 UP 空间）再回来时滚动位置还在；换了评论区才重建
    val listState = key(current?.oid, current?.type) { rememberLazyListState() }
    if (current == null || current.hiddenUntilEntry != null) return
    DynamicCommentDialog(
        state = current,
        listState = listState,
        onSortChange = controller::setSort,
        onLoadMore = controller::loadMore,
        onRetry = controller::retry,
        onLike = controller::toggleLike,
        onReply = controller::startReplyTo,
        onExpandSubReplies = controller::expandSubReplies,
        onLoadMoreSubReplies = controller::loadMoreSubReplies,
        onCollapseSubReplies = controller::collapseSubReplies,
        onContentChange = controller::updateContent,
        onClearReplyTarget = controller::clearReplyTarget,
        onComposerFocused = controller::onComposerFocused,
        onAddImages = controller::addImages,
        onRemoveImage = controller::removeImage,
        onSubmit = controller::submit,
        onDismiss = controller::dismiss,
        myMid = myMid,
        onDelete = controller::delete,
        onSaveImage = onSaveImage,
        onOpenUser = onOpenUser,
    )
}
