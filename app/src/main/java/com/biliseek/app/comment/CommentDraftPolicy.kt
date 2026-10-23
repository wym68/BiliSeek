package com.biliseek.app.comment

/** 一个评论区没发出去的草稿：输入内容 + 当时在回复谁（null = 发一级评论）。 */
data class CommentDraft(val content: String, val replyTarget: CommentReplyTarget?)

/** 关评论区时要不要留草稿：内容为空就不留（只点了「回复」没打字不算草稿）。 */
fun draftOnDismiss(state: CommentThreadState): CommentDraft? =
    state.content.takeIf { it.isNotBlank() }?.let { CommentDraft(it, state.replyTarget) }
