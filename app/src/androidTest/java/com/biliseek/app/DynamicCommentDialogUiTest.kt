package com.biliseek.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.biliseek.app.comment.CommentReplyTarget
import com.biliseek.app.comment.CommentThreadState
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.ui.components.DynamicCommentDialog
import com.biliseek.core.network.dto.ReplyContent
import com.biliseek.core.network.dto.ReplyItem
import com.biliseek.core.network.dto.ReplyMember
import com.biliseek.designsystem.theme.BiliSeekTheme
import org.junit.Rule
import org.junit.Test

/**
 * 评论列表 Sheet 的交互测试：排序切换 / 点赞 / 回复 / 加载更多 / 楼中楼操作回调。
 */
class DynamicCommentDialogUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val replies = listOf(
        ReplyItem(
            rpid = 1, mid = 10, ctime = 0, like = 5,
            content = ReplyContent(message = "第一条评论"),
            member = ReplyMember(uname = "用户A"),
        ),
        ReplyItem(
            rpid = 2, mid = 20, ctime = 0, like = 2,
            content = ReplyContent(message = "第二条评论"),
            member = ReplyMember(uname = "用户B"),
        ),
    )

    private fun state(
        items: List<ReplyItem> = replies,
        hasMore: Boolean = false,
        replyTarget: CommentReplyTarget? = null,
    ) = CommentThreadState(
        oid = 1, type = 1,
        comments = RemoteListState(items = items, hasMore = hasMore),
        replyTarget = replyTarget,
    )

    private fun setDialog(
        state: CommentThreadState,
        onLike: (ReplyItem) -> Unit = {},
        onReply: (ReplyItem, Long?) -> Unit = { _, _ -> },
        onSortChange: (Int) -> Unit = {},
        onLoadMore: () -> Unit = {},
        onClearReplyTarget: () -> Unit = {},
    ) {
        compose.setContent {
            BiliSeekTheme {
                DynamicCommentDialog(
                    state = state,
                    onSortChange = onSortChange,
                    onLoadMore = onLoadMore,
                    onLike = onLike,
                    onReply = onReply,
                    onExpandSubReplies = {},
                    onLoadMoreSubReplies = {},
                    onContentChange = {},
                    onClearReplyTarget = onClearReplyTarget,
                    onSubmit = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun 评论列表_点赞回复排序与加载更多回调() {
        var likedReply: ReplyItem? = null
        var replyTarget: ReplyItem? = null
        var replyRoot: Long? = -1
        var sortRequested: Int? = null
        var loadMoreCalled = false

        setDialog(
            state(hasMore = true),
            onLike = { likedReply = it },
            onReply = { reply, root -> replyTarget = reply; replyRoot = root },
            onSortChange = { sortRequested = it },
            onLoadMore = { loadMoreCalled = true },
        )

        compose.onNodeWithText("第一条评论").assertIsDisplayed()
        compose.onNodeWithText("用户B").assertIsDisplayed()

        compose.onAllNodesWithContentDescription("点赞")[0].performClick()
        check(likedReply?.rpid == 1L) { "点赞未回调正确的评论" }

        compose.onAllNodesWithContentDescription("回复")[0].performClick()
        check(replyTarget?.rpid == 1L) { "回复未回调正确的评论" }
        check(replyRoot == null) { "回复一级评论的 root 应为 null" }

        compose.onNodeWithText("时间").performClick()
        check(sortRequested == 0) { "排序切换未回调" }

        compose.waitForIdle()
        check(loadMoreCalled) { "列表末尾 sentinel 未触发加载更多" }
    }

    @Test
    fun 楼中楼可以点赞和回复() {
        val sub = ReplyItem(
            rpid = 11, mid = 30, like = 0,
            content = ReplyContent(message = "楼中楼一条"),
            member = ReplyMember(uname = "用户C"),
        )
        val root = replies[0].copy(rcount = 1, replies = listOf(sub))
        var likedReply: ReplyItem? = null
        var replyTarget: ReplyItem? = null
        var replyRoot: Long? = null

        setDialog(
            state(items = listOf(root)),
            onLike = { likedReply = it },
            onReply = { reply, r -> replyTarget = reply; replyRoot = r },
        )

        // 顺序：一级评论的按钮在前，楼中楼的在后
        compose.onAllNodesWithContentDescription("点赞")[1].performClick()
        check(likedReply?.rpid == 11L) { "楼中楼点赞未回调" }

        compose.onAllNodesWithContentDescription("回复")[1].performClick()
        check(replyTarget?.rpid == 11L) { "楼中楼回复未回调" }
        check(replyRoot == 1L) { "回复楼中楼应带上所属一级评论 rpid" }
    }

    @Test
    fun 回复目标提示与取消() {
        var cleared = false
        setDialog(
            state(replyTarget = CommentReplyTarget(root = 1, parent = 1, uname = "用户A")),
            onClearReplyTarget = { cleared = true },
        )

        compose.onNodeWithText("回复 @用户A").assertIsDisplayed()
        compose.onNodeWithContentDescription("取消回复").performClick()
        check(cleared) { "取消回复目标未回调" }
    }
}
