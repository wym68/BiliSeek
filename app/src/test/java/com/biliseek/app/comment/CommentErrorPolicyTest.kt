package com.biliseek.app.comment

import com.biliseek.core.network.SearchError
import com.biliseek.core.network.SearchException
import com.biliseek.core.network.WriteError
import com.biliseek.core.network.WriteException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class CommentErrorPolicyTest {

    @Test
    fun `风控不露错误码，提示等一会`() {
        assertEquals(
            "评论加载失败：请求太频繁，请过几分钟再试",
            commentErrorText("评论加载", SearchException(SearchError.Blocked, "触发风控（-352），已进入退避")),
        )
        assertEquals(
            "点赞失败：请求太频繁，请过几分钟再试",
            commentErrorText("点赞", WriteException(WriteError.Blocked, "触发风控(-352)，已进入退避")),
        )
    }

    @Test
    fun `服务端中文提示保留，空的给兜底`() {
        assertEquals(
            "评论失败：评论内容包含敏感信息",
            commentErrorText("评论", WriteException(WriteError.Unknown(12015, "评论内容包含敏感信息"), "操作失败：code=12015 评论内容包含敏感信息")),
        )
        assertEquals("评论失败：未知错误", commentErrorText("评论", WriteException(WriteError.Unknown(1, ""), "x")))
        assertEquals(
            "评论加载失败：评论区已关闭",
            commentErrorText("评论加载", SearchException(SearchError.Unknown("评论区已关闭"), "评论加载失败：code=12002 评论区已关闭")),
        )
    }

    @Test
    fun `登录、网络、凭据`() {
        assertEquals("删除失败：请先登录", commentErrorText("删除", WriteException(WriteError.NeedLogin, "未登录(-101)")))
        assertEquals("评论失败：登录已失效，请重新登录", commentErrorText("评论", WriteException(WriteError.CsrfInvalid, "x")))
        assertEquals("评论加载失败：网络不可用", commentErrorText("评论加载", SearchException(SearchError.Network("timeout"), "x")))
        assertEquals("评论加载失败：网络不可用", commentErrorText("评论加载", IOException("reset")))
        assertEquals(
            "评论加载失败：设备凭据已重置，请稍后再试",
            commentErrorText("评论加载", SearchException(SearchError.CredentialsInvalid, "x")),
        )
        assertEquals("评论加载失败：未知错误", commentErrorText("评论加载", IllegalStateException("boom")))
    }

    @Test
    fun `风控不给重试，其他可以`() {
        assertEquals(false, isRetryableCommentError(SearchException(SearchError.Blocked, "x")))
        assertEquals(true, isRetryableCommentError(SearchException(SearchError.Network(""), "x")))
        assertEquals(true, isRetryableCommentError(IOException("x")))
    }
}
