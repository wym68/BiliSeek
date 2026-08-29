package com.biliseek.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.biliseek.core.network.SearchError
import com.biliseek.designsystem.theme.BiliSeekTheme
import org.junit.Rule
import org.junit.Test

/**
 * 搜索页各状态的纯状态渲染测试。
 *
 * 覆盖《M12 计划》B-3 的 SearchScreen 部分：空状态 / 「没有找到」/
 * 错误 + 重试（含 **Blocked 无重试** 分支）。
 */
class SearchScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setSearch(state: SearchUiState, emptyContentText: String = "空状态占位") {
        compose.setContent {
            BiliSeekTheme {
                SearchScreen(
                    state = state,
                    onKeywordChange = {},
                    onSubmit = {},
                    onResultClick = {},
                    onAddToPlaylist = {},
                    onPlayNext = {},
                    onCopyLink = {},
                    onShowPages = {},
                    onLoadMore = {},
                    onPageClick = {},
                    onDismissPages = {},
                    emptyContent = { androidx.compose.material3.Text(emptyContentText) },
                )
            }
        }
    }

    private fun state(keyword: String = "", error: SearchError? = null) = SearchUiState(
        keyword = keyword,
        error = error,
    )

    // ── 空状态 ────────────────────────────────────────────

    @Test
    fun 无关键词无结果时显示首页空状态() {
        setSearch(state())

        compose.onNodeWithText("空状态占位").assertIsDisplayed()
    }

    // ── 有关键词但 0 结果：修白屏的分支 ────────────────────

    @Test
    fun 无结果时显示没有找到_且无重试按钮() {
        setSearch(state(keyword = "不存在的东西", error = SearchError.Empty("不存在的东西")))

        compose.onNodeWithText("没有找到「不存在的东西」").assertIsDisplayed()
        compose.onNodeWithText("换个关键词，或粘贴 BV 号 / 链接直达").assertIsDisplayed()
        compose.onNodeWithText("重试").assertDoesNotExist()
    }

    // ── 错误分类 ──────────────────────────────────────────

    @Test
    fun 风控拦截_提示等待且无重试按钮() {
        setSearch(state(keyword = "任意词", error = SearchError.Blocked))

        compose.onNodeWithText("请求太频繁，B 站暂时拦截了搜索").assertIsDisplayed()
        compose.onNodeWithText("请过几分钟再试，反复重试会让拦截时间变长").assertIsDisplayed()
        // 红线：Blocked 绝不给出重试按钮，防止把临时限流推成长期封禁
        compose.onNodeWithText("重试").assertDoesNotExist()
    }

    @Test
    fun 网络错误_显示文案与重试() {
        setSearch(state(keyword = "任意词", error = SearchError.Network("连接超时")))

        compose.onNodeWithText("网络不可用").assertIsDisplayed()
        compose.onNodeWithText("连接超时").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
    }

    @Test
    fun 凭据失效_显示文案与重试() {
        setSearch(state(keyword = "任意词", error = SearchError.CredentialsInvalid))

        compose.onNodeWithText("设备凭据已失效，已重新种植").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
    }

    // ── 重试回调真的会触发 ────────────────────────────────

    @Test
    fun 点击重试触发回调() {
        var retried = false
        compose.setContent {
            BiliSeekTheme {
                SearchScreen(
                    state = state(keyword = "任意词", error = SearchError.Network("断网")),
                    onKeywordChange = {},
                    onSubmit = {},
                    onResultClick = {},
                    onAddToPlaylist = {},
                    onPlayNext = {},
                    onCopyLink = {},
                    onShowPages = {},
                    onLoadMore = {},
                    onPageClick = {},
                    onDismissPages = {},
                    onRetry = { retried = true },
                )
            }
        }

        compose.onNodeWithText("重试").performClick()
        check(retried) { "重试按钮未触发 onRetry 回调" }
    }
}
