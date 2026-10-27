package com.biliseek.app

import android.view.SurfaceView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.biliseek.app.collection.CollectionEpisode
import com.biliseek.app.collection.CollectionSection
import com.biliseek.app.collection.VideoCollection
import com.biliseek.app.interaction.VideoInteractionState
import com.biliseek.app.ui.MiniPlayer
import com.biliseek.app.ui.PlayerScreen
import com.biliseek.app.ui.rememberAccentDecision
import com.biliseek.core.player.PlayMode
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

    private fun setMiniPlayer(state: PlaybackState, onSeek: (Long) -> Unit = {}) {
        compose.setContent {
            BiliSeekTheme {
                MiniPlayer(
                    state = state,
                    onExpand = {},
                    onTogglePlayPause = {},
                    onOpenQueue = {},
                    onSeek = onSeek,
                    miniBarStyle = com.biliseek.app.data.MiniBarStyle.FLOAT,
                )
            }
        }
    }

    private fun setPlayerScreen(
        state: PlaybackState,
        interaction: VideoInteractionState? = null,
        onPresentationChange: (PresentationMode, SurfaceView?) -> Unit = { _, _ -> },
        onCyclePlayMode: () -> Unit = {},
        onSetSpeed: (Float) -> Unit = {},
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            BiliSeekTheme {
                PlayerScreen(
                    state = state,
                    surfaceView = SurfaceView(context),
                    onCollapse = {},
                    onTogglePlayPause = {},
                    onPresentationChange = onPresentationChange,
                    onEnterPip = {},
                    onSkipNext = {},
                    onSkipPrevious = {},
                    onCyclePlayMode = onCyclePlayMode,
                    onSeek = {},
                    onSetSpeed = onSetSpeed,
                    onSetSleepTimer = {},
                    onRetryPlayback = {},
                    onEnterFullscreen = {},
                    onOpenQueue = {},
                    onOpenComments = {},
                    onShare = {},
                    onOpenAddToList = {},
                    onOpenUpSpace = {},
                    accent = rememberAccentDecision(CoverPalette.Empty),
                    interaction = interaction,
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
        compose.onNodeWithContentDescription("暂停").assertIsDisplayed()
    }

    @Test
    fun 迷你播放器_看模式下显示看标记() {
        setMiniPlayer(playingState.copy(presentation = PresentationMode.INLINE))

        compose.onNodeWithText("00:30 / 01:30 · 看").assertIsDisplayed()
    }

    @Test
    fun 迷你播放器_左右拖进度按5秒一格跳_轻点不跳() {
        var seeked: Long? = null
        setMiniPlayer(playingState, onSeek = { seeked = it })

        compose.onNodeWithContentDescription("播放进度").performClick()
        check(seeked == null) { "轻点进度条不该跳：$seeked" }

        // 从 00:30 往右拖 80dp：每 8dp 一格 5 秒（扣掉起拖的触摸阈值，格数略少于 10）
        compose.onNodeWithContentDescription("播放进度").performTouchInput {
            swipeRight(startX = centerX - 40.dp.toPx(), endX = centerX + 40.dp.toPx(), durationMillis = 400)
        }
        val target = seeked
        check(target != null && target > 30_000L && (target - 30_000L) % 5_000L == 0L) {
            "应从 00:30 起按 5 秒一格往后跳：$target"
        }
    }

    @Test
    fun 迷你播放器_按住后放大成刻度尺_拖一点松手按5秒格跳到按住处附近() {
        var seeked: Long? = null
        // 15 分钟的视频：放大后整条刻度尺只代表 3 分钟
        setMiniPlayer(playingState.copy(durationMs = 900_000, positionMs = 30_000), onSeek = { seeked = it })

        compose.onNodeWithContentDescription("播放进度").performTouchInput {
            down(center)
            // 按住超过长按时间：零位移的 move 带着事件时间往前走
            moveBy(Offset.Zero, delayMillis = viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(16.dp.toPx(), 0f))
            up()
        }
        // 在正中按住 ≈ 07:30；往右挪 16dp 只多十来秒（不放大会多一分多钟，5 秒一格拖只会到 00:40）
        val target = seeked
        check(target != null && target % 5_000L == 0L && target in 450_000L..480_000L) {
            "应从按住处（约 07:30）起按 5 秒格往后挪一点：$target"
        }
    }

    @Test
    fun 迷你播放器_空闲时不渲染() {
        setMiniPlayer(PlaybackState()) // IDLE

        compose.onNodeWithText("测试标题").assertDoesNotExist()
    }

    // ── 播放页：听模式 ────────────────────────────────────

    @Test
    fun 播放页_听模式显示顶部栏与听看开关_停在听() {
        setPlayerScreen(playingState)

        compose.onNodeWithText("正在播放").assertIsDisplayed()
        compose.onNodeWithText("听").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("看").assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithText("退回听").assertDoesNotExist()
    }

    @Test
    fun 播放页_点听看开关未选中的一段才切换() {
        val changes = mutableListOf<PresentationMode>()
        setPlayerScreen(playingState, onPresentationChange = { mode, _ -> changes += mode })

        compose.onNodeWithText("听").performClick()
        check(changes.isEmpty()) { "点已选中的「听」不该触发切换：$changes" }

        compose.onNodeWithText("看").performClick()
        check(changes == listOf(PresentationMode.INLINE)) { "点「看」应切到看：$changes" }
    }

    @Test
    fun 播放页_播放模式按钮带当前模式名并回调() {
        var cycled = 0
        setPlayerScreen(playingState.copy(playMode = PlayMode.REPEAT_ONE), onCyclePlayMode = { cycled++ })

        compose.onNodeWithContentDescription("播放模式：单曲循环").assertIsDisplayed().performClick()
        check(cycled == 1) { "点播放模式按钮应回调一次：$cycled" }
    }

    // ── 播放页：倍速与定时胶囊 ───────────────────────────

    @Test
    fun 播放页_正常速度时胶囊写倍速_点开是倍速档位() {
        setPlayerScreen(playingState)

        compose.onNodeWithText("倍速").assertIsDisplayed().performClick()
        compose.onNodeWithText("倍速播放").assertIsDisplayed()
    }

    @Test
    fun 播放页_调过倍速后胶囊写当前档位() {
        setPlayerScreen(playingState.copy(speed = 1.5f))

        compose.onNodeWithText("1.5×").assertIsDisplayed()
        compose.onNodeWithText("倍速").assertDoesNotExist()
    }

    @Test
    fun 播放页_没设定时写定时_点开是定时选项() {
        setPlayerScreen(playingState)

        compose.onNodeWithText("定时").assertIsDisplayed().performClick()
        compose.onNodeWithText("定时关闭").assertIsDisplayed()
        compose.onNodeWithText("播完当前").assertIsDisplayed()
    }

    @Test
    fun 播放页_设了定时后胶囊写剩余时间() {
        setPlayerScreen(playingState.copy(sleepTimerRemainingMs = 1_720_000))

        compose.onNodeWithText("28:40").assertIsDisplayed()
        compose.onNodeWithText("定时").assertDoesNotExist()
    }

    @Test
    fun 播放页_选了播完当前胶囊写播完当前() {
        setPlayerScreen(playingState.copy(sleepTimerEndOfTrack = true))

        compose.onNodeWithText("播完当前").assertIsDisplayed()
        compose.onNodeWithText("定时").assertDoesNotExist()
    }

    // ── 播放页：看模式长按画面临时加速 ─────────────────────

    @Test
    fun 播放页_看模式播放中长按画面2倍速_松手恢复原速() {
        val speeds = mutableListOf<Float>()
        setPlayerScreen(
            playingState.copy(mode = PlaybackMode.VIDEO, presentation = PresentationMode.INLINE, speed = 1.0f),
            onSetSpeed = { speeds += it },
        )

        compose.onNodeWithTag("player_video").performTouchInput { longClick() }
        check(speeds == listOf(2.0f, 1.0f)) { "长按应先 2 倍、松手恢复 1 倍：$speeds" }
    }

    @Test
    fun 播放页_看模式暂停时长按画面不加速() {
        val speeds = mutableListOf<Float>()
        setPlayerScreen(
            playingState.copy(
                mode = PlaybackMode.VIDEO,
                presentation = PresentationMode.INLINE,
                isPlaying = false,
                speed = 1.0f,
            ),
            onSetSpeed = { speeds += it },
        )

        compose.onNodeWithTag("player_video").performTouchInput { longClick() }
        check(speeds.isEmpty()) { "暂停时长按不该改速度：$speeds" }
    }

    // ── 播放页：简介行与弹层 ─────────────────────────────

    private val bvid = "BV1xx411c7mD"

    @Test
    fun 播放页_有简介时显示简介和展开_点展开弹层显示正文() {
        setPlayerScreen(
            playingState.copy(bvid = bvid),
            interaction = VideoInteractionState(bvid = bvid, desc = "这是简介正文", viewCount = 1234),
        )

        compose.onNodeWithText("简介").assertIsDisplayed()
        compose.onNodeWithText("展开").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("这是简介正文").assertIsDisplayed()
    }

    @Test
    fun 播放页_没有简介内容时不显示简介和展开_统计行仍在() {
        setPlayerScreen(
            playingState.copy(bvid = bvid),
            interaction = VideoInteractionState(bvid = bvid, desc = "-", viewCount = 1234),
        )

        compose.onNodeWithText("简介").assertDoesNotExist()
        compose.onNodeWithText("展开").assertDoesNotExist()
        compose.onNodeWithText("播放 1234", substring = true).assertIsDisplayed()
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
    fun 播放页_看模式顶部栏常显且有全屏入口_开关停在看() {
        setPlayerScreen(playingState.copy(presentation = PresentationMode.INLINE))

        compose.onNodeWithText("正在播放").assertIsDisplayed()
        compose.onNodeWithText("看").assertIsSelected()
        compose.onNodeWithText("听").assertIsNotSelected()
        compose.onNodeWithContentDescription("全屏").assertIsDisplayed()
    }

    @Test
    fun 播放页_看模式点听切回音频() {
        val changes = mutableListOf<PresentationMode>()
        setPlayerScreen(
            playingState.copy(presentation = PresentationMode.INLINE),
            onPresentationChange = { mode, _ -> changes += mode },
        )

        compose.onNodeWithText("听").performClick()
        check(changes == listOf(PresentationMode.NONE)) { "点「听」应切回音频：$changes" }
    }

    @Test
    fun 播放页_三点菜单是查看UP主_加入列表_下载与音质_复制链接() {
        setPlayerScreen(playingState)

        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNodeWithText("查看UP主").assertIsDisplayed()
        compose.onNodeWithText("加入列表").assertIsDisplayed()
        compose.onNodeWithText("下载").assertIsDisplayed()
        compose.onNodeWithText("音质").assertIsDisplayed()
        compose.onNodeWithText("复制链接").assertIsDisplayed()
        // 倍速、定时只从页面上的胶囊进，菜单里不再重复
        compose.onNodeWithContentDescription("倍速").assertDoesNotExist()
        compose.onNodeWithText("定时关闭").assertDoesNotExist()
    }

    // ── 取流失败 ──────────────────────────────────────────

    @Test
    fun 取流失败_显示错误文本与重试() {
        setPlayerScreen(playingState.copy(error = "网络不可达"))

        compose.onNodeWithText("网络不可达").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
    }

    // ── 合集 ────────────────────────────────────────────

    private fun collection(currentIndex: Int?) = VideoCollection(
        seasonId = 100,
        title = "测试合集",
        sections = listOf(
            CollectionSection(
                "正片",
                listOf("BV1" to "第一集", "BV2" to "第二集", "BV3" to "第三集").map { (bvid, title) ->
                    CollectionEpisode(bvid, title, "", 60, "UP", 9)
                },
            ),
        ),
        currentIndex = currentIndex,
    )

    @Test
    fun 播放页_属于合集时显示入口行_点开弹层列出各集() {
        setPlayerScreen(
            playingState.copy(bvid = "BV2"),
            interaction = VideoInteractionState(bvid = "BV2", collection = collection(currentIndex = 1)),
        )

        compose.onNodeWithText("测试合集").assertIsDisplayed()
        compose.onNodeWithText("合集 · 第 2/3 集").assertIsDisplayed().performClick()

        compose.onNodeWithText("共 3 集").assertIsDisplayed()
        compose.onNodeWithText("第三集").assertIsDisplayed()
    }

    @Test
    fun 播放页_不属于合集时没有入口行() {
        setPlayerScreen(playingState.copy(bvid = "BV2"), interaction = VideoInteractionState(bvid = "BV2"))

        compose.onNodeWithText("合集 · ", substring = true).assertDoesNotExist()
    }
}
