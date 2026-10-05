package com.biliseek.app

import android.view.SurfaceView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.biliseek.app.ui.FullscreenPlayerOverlay
import com.biliseek.core.player.PlaybackMode
import com.biliseek.core.player.PlaybackRequest
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode
import com.biliseek.core.player.VideoQualityOption
import com.biliseek.designsystem.theme.BiliSeekTheme
import org.junit.Rule
import org.junit.Test

/**
 * 全屏控制层的纯状态渲染测试（免网络）：按钮可达、面板能打开（含播放列表）、锁定后控件消失。
 */
class FullscreenPlayerUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val watching = PlaybackState(
        mode = PlaybackMode.VIDEO,
        presentation = PresentationMode.FULLSCREEN,
        title = "测试视频",
        isPlaying = false, // 暂停：控制层不自动隐藏，断言稳定
        durationMs = 600_000,
        positionMs = 60_000,
        videoQualities = listOf(
            VideoQualityOption(80, "1080P 高清", available = true),
            VideoQualityOption(64, "720P 准高清", available = true),
        ),
    )

    private fun setOverlay(
        state: PlaybackState = watching,
        danmakuEnabled: Boolean = false,
        onSkipTo: (Int) -> Unit = {},
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            BiliSeekTheme {
                FullscreenPlayerOverlay(
                    surfaceView = SurfaceView(context),
                    state = state,
                    danmakuEnabled = danmakuEnabled,
                    onDanmakuToggle = {},
                    onExit = {},
                    onTogglePlayPause = {},
                    onSeek = {},
                    onSkipPrevious = {},
                    onSkipNext = {},
                    onEnterPip = {},
                    onSkipTo = onSkipTo,
                )
            }
        }
    }

    @Test
    fun 底栏有倍速画质音质入口() {
        setOverlay()
        compose.onNodeWithText("倍速").assertIsDisplayed()
        compose.onNodeWithText("自动").assertIsDisplayed()
        compose.onNodeWithText("音质").assertIsDisplayed()
    }

    @Test
    fun 播放列表面板列出队列_点另一首跳过去() {
        var skippedTo: Int? = null
        setOverlay(
            watching.copy(
                queue = listOf(
                    PlaybackRequest.Bili(bvid = "BV1a", title = "第一首"),
                    PlaybackRequest.Bili(bvid = "BV1b", title = "第二首"),
                ),
                queueIndex = 0,
            ),
            onSkipTo = { skippedTo = it },
        )
        compose.onNodeWithContentDescription("播放列表").performClick()
        compose.onNodeWithContentDescription("正在播放").assertIsDisplayed()
        compose.onNodeWithText("第二首").assertIsDisplayed().performClick()
        check(skippedTo == 1) { "点第二首应跳到下标 1：$skippedTo" }
    }

    @Test
    fun 点倍速打开倍速面板() {
        setOverlay()
        compose.onNodeWithText("倍速").performClick()
        compose.onNodeWithText("倍速播放").assertIsDisplayed()
        compose.onNodeWithText("2×").assertIsDisplayed()
    }

    @Test
    fun 非1倍时按钮显示当前倍速() {
        setOverlay(watching.copy(speed = 1.5f))
        compose.onNodeWithText("1.5×").assertIsDisplayed()
    }

    @Test
    fun 画质只有一档时不显示入口() {
        setOverlay(watching.copy(videoQualities = emptyList()))
        compose.onNodeWithText("自动").assertDoesNotExist()
    }

    @Test
    fun 更多面板可达() {
        setOverlay()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("定时关闭").assertIsDisplayed()
        compose.onNodeWithText("下载").assertIsDisplayed()
        // 叫法与别处一致
        compose.onNodeWithText("加入列表").assertIsDisplayed()
        compose.onNodeWithText("复制链接").assertIsDisplayed()
        compose.onNodeWithText("查看UP主").assertIsDisplayed()
    }

    @Test
    fun 锁定后控件消失只留解锁按钮() {
        setOverlay()
        compose.onNodeWithContentDescription("锁定").performClick()
        compose.onNodeWithText("倍速").assertDoesNotExist()
        compose.onNodeWithContentDescription("解锁").assertIsDisplayed()
    }

    @Test
    fun 弹幕开启时出现弹幕设置入口() {
        setOverlay(danmakuEnabled = true)
        compose.onNodeWithContentDescription("弹幕设置").performClick()
        compose.onNodeWithText("屏蔽等级").assertIsDisplayed()
    }

    @Test
    fun 取流失败显示错误与重试() {
        setOverlay(watching.copy(error = "网络不可达"))
        compose.onNodeWithText("网络不可达").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
    }
}
