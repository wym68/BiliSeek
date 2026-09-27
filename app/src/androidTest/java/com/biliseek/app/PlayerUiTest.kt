package com.biliseek.app

import android.view.SurfaceView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.biliseek.app.ui.MiniPlayer
import com.biliseek.app.ui.PlayerScreen
import com.biliseek.app.ui.rememberAccentDecision
import com.biliseek.core.player.PlaybackMode
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode
import com.biliseek.designsystem.theme.BiliSeekTheme
import com.biliseek.designsystem.theme.CoverPalette
import org.junit.Rule
import org.junit.Test

/**
 * 迷你播放条与播放页的纯状态渲染测试（免网络、免 DB）。
 *
 * 直接渲染 [MiniPlayer] / [PlayerScreen]，喂合成状态断言文案与按钮行为。
 * 播放页顶部栏、控制条在听/看两种模式下都常显，只有封面区内容不同。
 */
class PlayerUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setMiniPlayer(state: PlaybackState) {
        compose.setContent {
            BiliSeekTheme {
                MiniPlayer(
                    state = state,
                    onExpand = {},
                    onTogglePlayPause = {},
                    onOpenQueue = {},
                    onSeek = {},
                    miniBarStyle = com.biliseek.app.data.MiniBarStyle.FLOAT,
                )
            }
        }
    }

    private fun setPlayerScreen(state: PlaybackState) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            BiliSeekTheme {
                PlayerScreen(
                    state = state,
                    surfaceView = SurfaceView(context),
                    onCollapse = {},
                    onTogglePlayPause = {},
                    onPresentationChange = { _, _ -> },
                    onEnterPip = {},
                    onSkipNext = {},
                    onSkipPrevious = {},
                    onCyclePlayMode = {},
                    onSeek = {},
                    onSetSpeed = {},
                    onSetSleepTimer = {},
                    onRetryPlayback = {},
                    onEnterFullscreen = {},
                    onOpenQueue = {},
                    onOpenComments = {},
                    onShare = {},
                    onOpenAddToList = {},
                    onOpenUpSpace = {},
                    accent = rememberAccentDecision(CoverPalette.Empty),
                )
            }
        }
    }

    private val playingState = PlaybackState(
        mode = PlaybackMode.AUDIO,
        title = "测试标题",
        isPlaying = true,
        durationMs = 90_000,
        positionMs = 30_000,
    )

    // ── 迷你播放器 ────────────────────────────────────────

    @Test
    fun 迷你播放器_显示标题_听标记与暂停键() {
        setMiniPlayer(playingState)

        compose.onNodeWithText("测试标题").assertIsDisplayed()
        compose.onNodeWithText("00:30 / 01:30 · 听").assertIsDisplayed()
        compose.onNodeWithText("⏸").assertIsDisplayed()
    }

    @Test
    fun 迷你播放器_看模式下显示看标记() {
        setMiniPlayer(playingState.copy(presentation = PresentationMode.INLINE))

        compose.onNodeWithText("00:30 / 01:30 · 看").assertIsDisplayed()
    }

    @Test
    fun 迷你播放器_空闲时不渲染() {
        setMiniPlayer(PlaybackState()) // IDLE

        compose.onNodeWithText("测试标题").assertDoesNotExist()
    }

    // ── 播放页：听模式 ────────────────────────────────────

    @Test
    fun 播放页_听模式显示顶部栏与切看按钮() {
        setPlayerScreen(playingState)

        compose.onNodeWithText("正在播放").assertIsDisplayed()
        compose.onNodeWithText("看").assertIsDisplayed()
        compose.onNodeWithText("退回听").assertDoesNotExist()
    }

    @Test
    fun 播放页_播放中主按钮contentDescription为暂停() {
        setPlayerScreen(playingState)

        compose.onNodeWithContentDescription("暂停").assertIsDisplayed()
    }

    @Test
    fun 播放页_暂停时主按钮contentDescription为播放() {
        setPlayerScreen(playingState.copy(isPlaying = false))

        compose.onNodeWithContentDescription("播放").assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停").assertDoesNotExist()
    }

    // ── 播放页：看模式 ────────────────────────────────────

    @Test
    fun 播放页_看模式顶部栏常显且有全屏入口() {
        setPlayerScreen(playingState.copy(presentation = PresentationMode.INLINE))

        compose.onNodeWithText("正在播放").assertIsDisplayed()
        compose.onNodeWithText("退回听").assertIsDisplayed()
        compose.onNodeWithContentDescription("全屏").assertIsDisplayed()
    }

    @Test
    fun 播放页_点菜单后能进入倍速() {
        setPlayerScreen(playingState.copy(presentation = PresentationMode.INLINE))

        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNodeWithText("倍速").performClick()
        compose.onNodeWithText("倍速播放").assertIsDisplayed()
    }

    // ── 取流失败 ──────────────────────────────────────────

    @Test
    fun 取流失败_显示错误文本与重试() {
        setPlayerScreen(playingState.copy(error = "网络不可达"))

        compose.onNodeWithText("网络不可达").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
    }
}
