package com.biliseek.app

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.biliseek.app.data.PlaylistTrack
import com.biliseek.app.data.TrackEntity
import com.biliseek.core.player.OfflineMedia
import com.biliseek.core.player.PlaybackMode
import com.biliseek.core.player.PresentationMode
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 主链路 Compose UI 测试（《功能优化篇》§10.4 指定）：
 * **起播 → 切看 → 换内容回到听**。
 *
 * 全离线设计：取流层 `offlineLookup` 回调在风控凭据（`awaitReady`）**之前**
 * 拦截（M9 §9.4 的拦截点），测试把它指向拷贝到私有目录的测试资产——
 * 播放完全不碰真实网络，模拟器搜索被服务端降权（-352/-412）影响不到这条链路。
 *
 * 断言只判状态与文案，不断言像素（模拟器软解/硬解差异不管渲染正确性）。
 */
@RunWith(AndroidJUnit4::class)
class MainPlaybackChainTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var vm: AppViewModel

    @Before
    fun wireOfflinePlayback() {
        composeRule.activityRule.scenario.onActivity { activity ->
            // 与 Activity 共享同一个 ViewModelStore：`by viewModels()` 用的就是它
            vm = ViewModelProvider(activity)[AppViewModel::class.java]
            // 覆盖 VM init 注入的真实数据库查询：所有 bvid 都命中本地测试资产。
            // 资产在测试 APK 里，读用 instrumentation context；写入目标应用私有目录，
            // 播放（目标应用进程）才能读到 file://
            val file = copyAssetToFiles(activity, "sample_video.mp4")
            vm.connection.offlineLookup = { _, _ ->
                OfflineMedia(
                    uri = "file://${file.absolutePath}",
                    mimeType = "video/mp4",
                    durationMs = 3000,
                    hasVideoTrack = true,
                )
            }
        }
    }

    private fun playTwoTrackQueue() {
        composeRule.activityRule.scenario.onActivity { activity ->
            vm.playPlaylist(
                items = listOf(
                    PlaylistTrack(
                        TrackEntity(
                            bvid = "BV1testA001",
                            cid = 1,
                            title = "测试曲目 A",
                            author = "测试UP",
                            coverUrl = "",
                            durationSec = 3,
                        ),
                        sortKey = 1.0,
                    ),
                    PlaylistTrack(
                        TrackEntity(
                            bvid = "BV1testB002",
                            cid = 2,
                            title = "测试曲目 B",
                            author = "测试UP",
                            coverUrl = "",
                            durationSec = 3,
                        ),
                        sortKey = 2.0,
                    ),
                ),
                startIndex = 0,
            )
        }
    }

    /** 等待播放状态进入某个条件。 */
    private fun waitForState(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        composeRule.waitUntil(timeoutMs) { condition() }
    }

    @Test
    fun 主链路_起播_切看_换内容回到听() {
        playTwoTrackQueue()

        // ── 起播：播放器自动展开、显示标题、听模式 ──
        waitForState {
            vm.connection.state.value.title == "测试曲目 A" &&
                vm.connection.state.value.mode != PlaybackMode.IDLE
        }
        composeRule.waitForIdle()
        // 标题同时出现在标题行与队列当前行：任一处可见即通过
        composeRule.onAllNodesWithText("测试曲目 A").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("切「看」").assertIsDisplayed()
        composeRule.onNodeWithText("暂停").assertIsDisplayed()

        // ── 切看：呈现态进 INLINE，按钮翻转 ──
        composeRule.onNodeWithText("切「看」").performClick()
        waitForState { vm.connection.state.value.presentation == PresentationMode.INLINE }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("退回「听」").assertIsDisplayed()
        composeRule.onNodeWithText("全屏").assertIsDisplayed()

        // ── 换内容（下一首）→ 回到听，按钮回「切看」──
        composeRule.onNodeWithText("下一首").performClick()
        waitForState {
            vm.connection.state.value.title == "测试曲目 B" &&
                vm.connection.state.value.presentation == PresentationMode.NONE
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("测试曲目 B").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("切「看」").assertIsDisplayed()
        composeRule.onAllNodesWithText("退回「听」").assertCountEquals(0)
    }

    @Test
    fun 主链路_暂停与继续() {
        playTwoTrackQueue()
        waitForState {
            vm.connection.state.value.title == "测试曲目 A" && vm.connection.state.value.isPlaying
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("暂停").performClick()
        waitForState { !vm.connection.state.value.isPlaying }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("播放").assertIsDisplayed()

        composeRule.onNodeWithText("播放").performClick()
        waitForState { vm.connection.state.value.isPlaying }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("暂停").assertIsDisplayed()
    }

    private fun copyAssetToFiles(context: Context, asset: String): File {
        val target = File(context.filesDir, asset)
        if (target.exists()) return target
        // 测试资产打进的是**测试 APK**：必须用 instrumentation context 读
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }
}
