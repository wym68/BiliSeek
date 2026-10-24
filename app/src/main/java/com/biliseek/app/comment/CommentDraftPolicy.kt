package com.biliseek.app.comment

/** 一个评论区没发出去的草稿：输入内容 + 已选的图 + 当时在回复谁（null = 发一级评论）。 */
data class CommentDraft(
    val content: String,
    val replyTarget: CommentReplyTarget?,
    val images: List<CommentImage> = emptyList(),
)

/** 关评论区时要不要留草稿：没字也没图就不留（只点了「回复」没打字不算草稿）。 */
fun draftOnDismiss(state: CommentThreadState): CommentDraft? =
    if (state.content.isNotBlank() || state.images.isNotEmpty()) {
        CommentDraft(state.content, state.replyTarget, state.images)
    } else {
        null
    }
