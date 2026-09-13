package com.biliseek.app

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.biliseek.app.data.DynamicExpandState
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.ui.DynamicScreen
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.DynamicEntry
import com.biliseek.core.network.DynamicVideo
import com.biliseek.core.network.LoginState
import com.biliseek.designsystem.theme.BiliSeekTheme
import org.junit.Rule
import org.junit.Test

/**
 * 动态卡片点击区域重设计的行为验证：视频行播放 / 文案不触发播放 / 头像跳转 /
 * 长文案展开收起。全部用本地构造的假数据，不发真实网络请求。
 */
class DynamicCardClickRegionsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val loggedInSession = BiliSession(
        sessData = "s", biliJct = "j", dedeUserId = "1", refreshToken = "r",
    )

    private fun videoEntry() = DynamicEntry(
        idStr = "v1",
        authorName = "测试UP",
        authorFace = "",
        authorMid = 100L,
        pubTs = 0L,
        descText = "今天更新了一个新视频",
        rawType = "DYNAMIC_TYPE_AV",
        video = DynamicVideo(
            bvid = "BV1xx",
            title = "测试视频标题",
            coverUrl = "",
            durationText = "03:27",
        ),
        coverUrl = "",
    )

    @Test
    fun 视频动态_点视频行播放_点文案不播放_点头像跳转UP主页() {
        var played = false
        var authorClicked = false
        val entry = videoEntry()

        compose.setContent {
            BiliSeekTheme {
                DynamicScreen(
                    state = DynamicUiState(feed = RemoteListState(items = listOf(entry))),
                    session = loggedInSession,
                    loginState = LoginState.Idle,
                    onInit = {},
                    onRefresh = {},
                    onTypeFilterChange = {},
                    onSelectUp = {},
                    onLoadMore = {},
                    onRetryInit = {},
                    onEntryClick = { played = true },
                    onStartLogin = {},
                    onCancelLogin = {},
                    onAuthorClick = { authorClicked = true },
                )
            }
        }

        // 文案区域没有点击语义（不再有整卡点击兜底）
        compose.onNodeWithText("今天更新了一个新视频").assertHasNoClickAction()

        // 点视频行（标题所在的可点热区）触发播放，不触发头像跳转
        compose.onNodeWithText("测试视频标题").performClick()
        check(played) { "点视频行应触发播放回调" }
        check(!authorClicked) { "点视频行不应触发头像跳转回调" }

        // 点头像/昵称触发跳转 UP 主页
        compose.onNodeWithText("测试UP").performClick()
        check(authorClicked) { "点昵称应触发跳转 UP 主页回调" }
    }

    @Test
    fun 图文动态_长文案显示展开按钮_点击回调正确条目() {
        val longText = "很长的正文内容。".repeat(60)
        val entry = DynamicEntry(
            idStr = "d1",
            authorName = "图文UP",
            authorFace = "",
            authorMid = 200L,
            pubTs = 0L,
            descText = longText,
            rawType = "DYNAMIC_TYPE_OPUS",
            video = null,
            coverUrl = "",
        )
        var toggledIdStr: String? = null

        compose.setContent {
            BiliSeekTheme {
                DynamicScreen(
                    state = DynamicUiState(feed = RemoteListState(items = listOf(entry))),
                    session = loggedInSession,
                    loginState = LoginState.Idle,
                    onInit = {},
                    onRefresh = {},
                    onTypeFilterChange = {},
                    onSelectUp = {},
                    onLoadMore = {},
                    onRetryInit = {},
                    onEntryClick = {},
                    onStartLogin = {},
                    onCancelLogin = {},
                    onToggleExpand = { e -> toggledIdStr = e.idStr },
                )
            }
        }

        compose.onNodeWithText("…展开", substring = true).performClick()
        check(toggledIdStr == "d1") { "点展开应回调对应条目的 onToggleExpand" }
    }

    @Test
    fun 图文动态_展开态显示完整正文与收起按钮() {
        val fullText = "完整正文内容"
        val entry = DynamicEntry(
            idStr = "d2",
            authorName = "图文UP2",
            authorFace = "",
            authorMid = 300L,
            pubTs = 0L,
            descText = "摘要",
            rawType = "DYNAMIC_TYPE_OPUS",
            video = null,
            coverUrl = "",
        )
        var toggledIdStr: String? = null

        compose.setContent {
            BiliSeekTheme {
                DynamicScreen(
                    state = DynamicUiState(feed = RemoteListState(items = listOf(entry))),
                    session = loggedInSession,
                    loginState = LoginState.Idle,
                    onInit = {},
                    onRefresh = {},
                    onTypeFilterChange = {},
                    onSelectUp = {},
                    onLoadMore = {},
                    onRetryInit = {},
                    onEntryClick = {},
                    onStartLogin = {},
                    onCancelLogin = {},
                    dynamicExpand = mapOf(
                        "d2" to DynamicExpandState(expanded = true, entry = entry.copy(descText = fullText)),
                    ),
                    onToggleExpand = { e -> toggledIdStr = e.idStr },
                )
            }
        }

        compose.onNodeWithText(fullText, substring = true).assertIsDisplayed()
        compose.onNodeWithText("收起", substring = true).performClick()
        check(toggledIdStr == "d2") { "点收起应回调对应条目的 onToggleExpand" }
    }
}
