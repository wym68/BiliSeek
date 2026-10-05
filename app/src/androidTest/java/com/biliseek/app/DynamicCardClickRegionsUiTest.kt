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
import com.biliseek.core.network.dto.DynamicBlock
import com.biliseek.core.network.dto.DynamicPic
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

    // ── 展开态：单击不收起、长图文按段落显示（§13.46）──────────────

    private fun opusEntry(idStr: String) = DynamicEntry(
        idStr = idStr,
        authorName = "图文UP",
        authorFace = "",
        authorMid = 400L,
        pubTs = 0L,
        descText = "摘要",
        rawType = "DYNAMIC_TYPE_OPUS",
        video = null,
        coverUrl = "",
    )

    private fun showExpanded(entry: DynamicEntry, state: DynamicExpandState, onToggle: (DynamicEntry) -> Unit) {
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
                    dynamicExpand = mapOf(entry.idStr to state),
                    onToggleExpand = onToggle,
                )
            }
        }
    }

    @Test
    fun 图文动态_展开后单击正文不收起() {
        val entry = opusEntry("d3")
        var toggled = false
        showExpanded(entry, DynamicExpandState(expanded = true, entry = entry.copy(descText = "完整正文内容"))) { toggled = true }

        compose.onNodeWithText("完整正文内容").performClick()
        check(!toggled) { "单击展开后的正文不应收起，只有「收起」收起" }
    }

    @Test
    fun 长图文_展开后按段落显示文字_点收起回调() {
        val entry = opusEntry("d4")
        val detail = entry.copy(
            descText = "第一段\n第二段",
            body = listOf(
                DynamicBlock.Text("第一段"),
                // 本地不存在的地址：测试不碰 B 站图床
                DynamicBlock.Images(listOf(DynamicPic("file:///nonexistent/a.jpg", 1200, 800))),
                DynamicBlock.Text("第二段"),
            ),
        )
        var toggledIdStr: String? = null
        showExpanded(entry, DynamicExpandState(expanded = true, entry = detail)) { toggledIdStr = it.idStr }

        compose.onNodeWithText("第一段").assertIsDisplayed()
        compose.onNodeWithText("第二段").assertIsDisplayed()
        compose.onNodeWithText("摘要").assertDoesNotExist()
        compose.onNodeWithText("收起").performClick()
        check(toggledIdStr == "d4") { "点收起应回调对应条目的 onToggleExpand" }
    }
}
