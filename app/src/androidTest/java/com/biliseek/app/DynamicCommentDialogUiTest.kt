package com.biliseek.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.ui.components.DynamicCommentDialog
import com.biliseek.core.network.dto.ReplyContent
import com.biliseek.core.network.dto.ReplyItem
import com.biliseek.core.network.dto.ReplyMember
import com.biliseek.designsystem.theme.BiliSeekTheme
import org.junit.Rule
import org.junit.Test

/**
 * 评论列表 Sheet 的交互测试：排序切换 / 点赞 / 回复 / 加载更多回调。
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

    @Test
    fun 评论列表_点赞回复排序与加载更多回调() {
        var likedReply: ReplyItem? = null
        var likedRoot: Long? = null
        var replyTarget: ReplyItem? = null
        var sortRequested: Int? = null
        var loadMoreCalled = false

        compose.setContent {
            BiliSeekTheme {
                DynamicCommentDialog(
                    comments = RemoteListState(items = replies, hasMore = true),
                    sort = 1,
                    replyTargetUname = null,
                    content = "",
                    isPosting = false,
                    expandedSubReplies = emptyMap(),
                    onSortChange = { sortRequested = it },
                    onLoadMore = { loadMoreCalled = true },
                    onLike = { reply, root -> likedReply = reply; likedRoot = root },
                    onReply = { reply, _ -> replyTarget = reply },
                    onExpandSubReplies = {},
                    onLoadMoreSubReplies = {},
                    onContentChange = {},
                    onClearReplyTarget = {},
                    onSubmit = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("第一条评论").assertIsDisplayed()
        compose.onNodeWithText("用户B").assertIsDisplayed()

        compose.onAllNodesWithContentDescription("点赞")[0].performClick()
        check(likedReply?.rpid == 1L) { "点赞未回调正确的评论" }
        check(likedRoot == null) { "一级评论点赞的 root 应为 null" }

        compose.onAllNodesWithContentDescription("回复")[0].performClick()
        check(replyTarget?.rpid == 1L) { "回复未回调正确的评论" }

        compose.onNodeWithText("时间").performClick()
        check(sortRequested == 0) { "排序切换未回调" }

        compose.waitForIdle()
        check(loadMoreCalled) { "列表末尾 sentinel 未触发加载更多" }
    }

    @Test
    fun 回复目标提示与取消() {
        var cleared = false
        compose.setContent {
            BiliSeekTheme {
                DynamicCommentDialog(
                    comments = RemoteListState(items = replies),
                    sort = 1,
                    replyTargetUname = "用户A",
                    content = "",
                    isPosting = false,
                    expandedSubReplies = emptyMap(),
                    onSortChange = {},
                    onLoadMore = {},
                    onLike = { _, _ -> },
                    onReply = { _, _ -> },
                    onExpandSubReplies = {},
                    onLoadMoreSubReplies = {},
                    onContentChange = {},
                    onClearReplyTarget = { cleared = true },
                    onSubmit = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("回复 @用户A").assertIsDisplayed()
        compose.onNodeWithContentDescription("取消回复").performClick()
        check(cleared) { "取消回复目标未回调" }
    }
}
