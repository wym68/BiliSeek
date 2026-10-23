package com.biliseek.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    val current = state ?: return
    DynamicCommentDialog(
        state = current,
        onSortChange = controller::setSort,
        onLoadMore = controller::loadMore,
        onLike = controller::toggleLike,
        onReply = controller::startReplyTo,
        onExpandSubReplies = controller::expandSubReplies,
        onLoadMoreSubReplies = controller::loadMoreSubReplies,
        onContentChange = controller::updateContent,
        onClearReplyTarget = controller::clearReplyTarget,
        onSubmit = controller::submit,
        onDismiss = controller::dismiss,
        myMid = myMid,
        onDelete = controller::delete,
        onSaveImage = onSaveImage,
        onOpenUser = onOpenUser,
    )
}
