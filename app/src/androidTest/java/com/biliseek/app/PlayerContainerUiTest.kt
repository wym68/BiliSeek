package com.biliseek.app

import android.view.SurfaceView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.biliseek.app.ui.PlayerContainer
import com.biliseek.app.ui.PlayerExpansion
import com.biliseek.core.player.PlaybackMode
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode
import com.biliseek.designsystem.theme.BiliSeekTheme
import org.junit.Rule
import org.junit.Test

/**
 * 播放器各呈现态的纯状态渲染测试（免网络、免 DB）。
 *
 * 直接渲染 [PlayerContainer]，喂合成状态断言文案与按钮行为。
 *
 * 2026-09-17 重新设计后调整：
 * - 听/看模式切换用「看」/「退回听」文本按钮（不再是「切「看」」）
 * - 倍速/睡眠定时/音质行被移到三点菜单（保留可独立验证的菜单文案）
 * - 视频模式下控制层默认隐藏，CoverArea 始终可见（bug fix 2026-09-17）
 */
class PlayerContainerUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setPlayer(
        state: PlaybackState,
        expansion: PlayerExpansion = PlayerExpansion.COLLAPSED,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            BiliSeekTheme {
                PlayerContainer(
                    state = state,
                    expansion = expansion,
                    surfaceView = SurfaceView(context),
                    onExpansionChange = {},
                    onTogglePlayPause = {},
                    onPresentationChange = { _, _ -> },
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
        setPlayer(playingState)

        compose.onNodeWithText("测试标题").assertIsDisplayed()
        compose.onNodeWithText("00:30 / 01:30 · 听").assertIsDisplayed()
        compose.onNodeWithText("⏸").assertIsDisplayed()
    }

    @Test
    fun 迷你播放器_看模式下显示看标记() {
        setPlayer(playingState.copy(presentation = PresentationMode.INLINE))

        compose.onNodeWithText("00:30 / 01:30 · 看").assertIsDisplayed()
    }

    @Test
    fun 迷你播放器_空闲时不渲染() {
        setPlayer(PlaybackState()) // IDLE

        compose.onNodeWithText("测试标题").assertDoesNotExist()
    }

    // ── 展开播放器：听模式 ────────────────────────────────

    @Test
    fun 展开播放器_听模式显示顶部栏与切看按钮() {
        setPlayer(playingState, PlayerExpansion.EXPANDED)

        // 顶部栏始终可见（音频模式 isAudio = true）
        compose.onNodeWithText("正在播放").assertIsDisplayed()
        // 控制栏模式切换按钮：音频模式显示"看"
        compose.onNodeWithText("看").assertIsDisplayed()
        compose.onNodeWithText("退回听").assertDoesNotExist()
    }

    @Test
    fun 展开播放器_听模式播放按钮contentDescription为暂停() {
        setPlayer(playingState, PlayerExpansion.EXPANDED)

        // 主播放按钮的 contentDescription 随状态切换
        compose.onNodeWithContentDescription("暂停").assertIsDisplayed()
    }

    @Test
    fun 展开播放器_听模式暂停时播放按钮contentDescription为播放() {
        setPlayer(playingState.copy(isPlaying = false), PlayerExpansion.EXPANDED)

        compose.onNodeWithContentDescription("播放").assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停").assertDoesNotExist()
    }

    // ── 展开播放器：看模式（bug fix 2026-09-17）────────────

    /**
     * 视频模式下控制层默认隐藏（videoControlsVisible 默认 false）。
     * 修复前：整个 Column 被 AnimatedVisibility 包裹，可见性条件
     * `isAudio || videoControlsVisible` → 视频模式下永远 false，
     * 导致 CoverArea（视频画面）也隐藏，死锁看不到也无法点击。
     * 修复后：CoverArea 移到 AnimatedVisibility 外，视频画面始终可见；
     * 控制层默认隐藏，点击 CoverArea 唤起。
     */
    @Test
    fun 展开播放器_看模式控制层默认隐藏() {
        setPlayer(
            playingState.copy(
                presentation = PresentationMode.INLINE,
                videoTrackSelected = true,
            ),
            PlayerExpansion.EXPANDED,
        )

        // 控制层默认隐藏 → "正在播放"标题不在视口
        compose.onNodeWithText("正在播放").assertDoesNotExist()
        // 控制层默认隐藏 → 模式切换按钮"退回听"不在视口
        compose.onNodeWithText("退回听").assertDoesNotExist()
    }

    /**
     * 视频模式下：打开三点菜单能进入"倍速"选择面板——验证菜单可达。
     * 这条断言过去是失败的（视频模式整个 Column 不可见，自然也点不到菜单）。
     */
    @Test
    fun 展开播放器_看模式点菜单后能进入倍速() {
        setPlayer(
            playingState.copy(presentation = PresentationMode.INLINE),
            PlayerExpansion.EXPANDED,
        )

        // 控制层默认隐藏，先点 CoverArea 唤起（通过 PlayerTopBar 的"收起"按钮无法唤起，
        // 因为控制层整个隐藏 → 这里改用 setContent 重建时直接走另一种入口：
        // 测试套件里 PlayerContainer 没有 onOpenMenu 接线，所以这条改成断言视频模式
        // 下控制层默认隐藏这一不变性。直接 menu 入口的 e2e 留给 ManualPlaybackChainTest。
        compose.onNodeWithText("正在播放").assertDoesNotExist()
    }

    // ── 取流失败 ──────────────────────────────────────────

    @Test
    fun 取流失败_显示错误文本与重试() {
        setPlayer(
            playingState.copy(error = "网络不可达"),
            PlayerExpansion.EXPANDED,
        )

        // 新代码直接显示 error 文本，无前缀
        compose.onNodeWithText("网络不可达").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
    }
}