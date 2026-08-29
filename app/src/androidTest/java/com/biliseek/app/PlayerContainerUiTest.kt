package com.biliseek.app

import android.view.SurfaceView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.biliseek.app.ui.PlayerContainer
import com.biliseek.app.ui.PlayerExpansion
import com.biliseek.core.player.AudioQuality
import com.biliseek.core.player.PlayMode
import com.biliseek.core.player.PlaybackMode
import com.biliseek.core.player.PlaybackRequest
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode
import com.biliseek.core.player.QualityFallback
import com.biliseek.designsystem.theme.BiliSeekTheme
import org.junit.Rule
import org.junit.Test

/**
 * 播放器各呈现态的纯状态渲染测试（免网络、免 DB）。
 *
 * 直接渲染 [PlayerContainer]，喂合成状态断言文案与按钮行为。
 * 覆盖《M12 计划》B-3 的 PlayerContainer 部分。
 */
class PlayerContainerUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setPlayer(
        state: PlaybackState,
        expansion: PlayerExpansion = PlayerExpansion.COLLAPSED,
    ) {
        // 纯状态测试不需要真实 Activity：SurfaceView 只是占位，取测试包 targetContext
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

    // ── 展开播放器：听 / 看切换 ────────────────────────────

    @Test
    fun 展开播放器_听模式显示切看() {
        setPlayer(playingState, PlayerExpansion.EXPANDED)

        compose.onNodeWithText("正在播放").assertIsDisplayed()
        compose.onNodeWithText("切「看」").assertIsDisplayed()
        compose.onNodeWithText("退回「听」").assertDoesNotExist()
        compose.onNodeWithText("全屏").assertDoesNotExist()
    }

    @Test
    fun 展开播放器_看模式显示退回听与全屏() {
        setPlayer(
            playingState.copy(
                presentation = PresentationMode.INLINE,
                videoTrackSelected = true,
            ),
            PlayerExpansion.EXPANDED,
        )

        compose.onNodeWithText("退回「听」").assertIsDisplayed()
        compose.onNodeWithText("全屏").assertIsDisplayed()
        compose.onNodeWithText("画中画").assertIsDisplayed()
        compose.onNodeWithText("切「看」").assertDoesNotExist()
    }

    @Test
    fun 展开播放器_无封面时显示纯音频模式() {
        setPlayer(playingState, PlayerExpansion.EXPANDED)

        compose.onNodeWithText("纯音频模式").assertIsDisplayed()
    }

    @Test
    fun 展开播放器_播放暂停文案随状态() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = androidx.compose.runtime.mutableStateOf(playingState)
        compose.setContent {
            BiliSeekTheme {
                PlayerContainer(
                    state = state.value,
                    expansion = PlayerExpansion.EXPANDED,
                    surfaceView = SurfaceView(context),
                    onExpansionChange = {},
                    onTogglePlayPause = {},
                    onPresentationChange = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("暂停").assertIsDisplayed()

        state.value = playingState.copy(isPlaying = false)
        compose.onNodeWithText("播放").assertIsDisplayed()
        compose.onNodeWithText("暂停").assertDoesNotExist()
    }

    // ── 音质行 ────────────────────────────────────────────

    @Test
    fun 音质行_本地文件如实显示() {
        setPlayer(playingState.copy(isOffline = true), PlayerExpansion.EXPANDED)

        compose.onNodeWithText("音质：本地文件").assertIsDisplayed()
    }

    @Test
    fun 音质行_无损回退带原因() {
        setPlayer(
            playingState.copy(
                audioQuality = AudioQuality.LOSSLESS,
                qualityFallback = QualityFallback.UNAVAILABLE,
            ),
            PlayerExpansion.EXPANDED,
        )

        compose.onNodeWithText("音质：无损（本视频无无损音轨，或账号无权限）").assertIsDisplayed()
    }

    @Test
    fun 音质行_倍速互斥回退带原因() {
        setPlayer(
            playingState.copy(
                audioQuality = AudioQuality.LOSSLESS,
                qualityFallback = QualityFallback.SPEED_INCOMPATIBLE,
                speed = 2.0f,
            ),
            PlayerExpansion.EXPANDED,
        )

        compose.onNodeWithText("音质：无损（倍速超过 1.5×，已回退）").assertIsDisplayed()
    }

    // ── 倍速与睡眠定时 ────────────────────────────────────

    @Test
    fun 倍速行_五个档位() {
        setPlayer(playingState.copy(speed = 1.25f), PlayerExpansion.EXPANDED)

        compose.onNodeWithText("倍速").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0.75×").assertIsDisplayed()
        compose.onNodeWithText("1.0×").assertIsDisplayed()
        compose.onNodeWithText("1.25×").assertIsDisplayed()
        compose.onNodeWithText("1.5×").assertIsDisplayed()
        compose.onNodeWithText("2.0×").assertIsDisplayed()
    }

    @Test
    fun 睡眠定时器_倒计时显示剩余与取消() {
        setPlayer(
            playingState.copy(sleepTimerRemainingMs = 900_000),
            PlayerExpansion.EXPANDED,
        )

        compose.onNodeWithText("睡眠定时 · 剩余 15:00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("15 分钟").assertIsDisplayed()
        compose.onNodeWithText("30 分钟").assertIsDisplayed()
        compose.onNodeWithText("60 分钟").assertIsDisplayed()
        compose.onNodeWithText("播完当前").assertIsDisplayed()
    }

    @Test
    fun 睡眠定时器_播完当前模式() {
        setPlayer(
            playingState.copy(sleepTimerEndOfTrack = true),
            PlayerExpansion.EXPANDED,
        )

        compose.onNodeWithText("睡眠定时 · 播完当前").performScrollTo().assertIsDisplayed()
    }

    // ── 队列面板 ──────────────────────────────────────────

    @Test
    fun 队列面板_展开后列出全部条目() {
        val state = playingState.copy(
            queue = listOf(
                PlaybackRequest.Bili("BV1A", 1, "队列曲目一", ""),
                PlaybackRequest.Bili("BV1B", 2, "队列曲目二", ""),
                PlaybackRequest.Bili("BV1C", 3, "队列曲目三", ""),
            ),
            queueIndex = 1,
        )
        setPlayer(state, PlayerExpansion.EXPANDED)

        compose.onNodeWithText("队列 2 / 3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("展开").performClick()
        compose.onNodeWithText("队列曲目一").assertIsDisplayed()
        compose.onNodeWithText("队列曲目二").assertIsDisplayed()
        // 第三项可能在视口外：滚动后再断言
        compose.onNodeWithText("队列曲目三").performScrollTo().assertIsDisplayed()
        // 「正在播放」出现两处：面板标题 + 当前行标记
        compose.onAllNodesWithText("正在播放").assertCountEquals(2)
    }

    @Test
    fun 队列面板_单曲不显示() {
        setPlayer(
            playingState.copy(queue = listOf(PlaybackRequest.Bili("BV1A", 1, "单曲", ""))),
            PlayerExpansion.EXPANDED,
        )

        compose.onNodeWithText("队列").assertDoesNotExist()
    }

    // ── 取流失败 ──────────────────────────────────────────

    @Test
    fun 取流失败_显示错误与重试() {
        setPlayer(
            playingState.copy(error = "网络不可达"),
            PlayerExpansion.EXPANDED,
        )

        compose.onNodeWithText("取流失败：网络不可达").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
    }
}
