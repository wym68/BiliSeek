package com.biliseek.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.biliseek.app.data.AppSettings
import com.biliseek.app.data.DownloadContent
import com.biliseek.app.data.PlaylistEntity
import com.biliseek.app.download.DownloadRequest
import com.biliseek.app.ui.DownloadDialog
import com.biliseek.app.ui.MeteredVideoDialog
import com.biliseek.app.ui.PlaylistPickerDialog
import com.biliseek.app.ui.toDownloadTarget
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.designsystem.theme.BiliSeekTheme
import org.junit.Rule
import org.junit.Test

/**
 * 三个对话框的交互测试：流量拦截 / 加歌单选择 / 下载确认。
 *
 * 覆盖《M12 计划》B-3 的 Dialogs 部分。
 */
class DialogsUiTest {

    @get:Rule
    val compose = createComposeRule()

    // ── 流量策略拦截（M8 D-2）──────────────────────────────

    @Test
    fun 流量拦截_文案与两个出口() {
        var confirmed = false
        var dismissed = false
        compose.setContent {
            BiliSeekTheme {
                MeteredVideoDialog(
                    onConfirmOnce = { confirmed = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("当前是移动网络").assertIsDisplayed()
        compose.onNodeWithText("这次就看").performClick()
        check(confirmed) { "「这次就看」未触发一次性放行" }
        check(!dismissed)

        compose.onNodeWithText("继续只听").performClick()
        check(dismissed) { "「继续只听」未触发关闭" }
    }

    // ── 加歌单选择（M7 C-4）────────────────────────────────

    @Test
    fun 加歌单_列出全部歌单并回调选中项() {
        var picked: Long? = null
        var creating = false
        compose.setContent {
            BiliSeekTheme {
                PlaylistPickerDialog(
                    playlists = listOf(
                        PlaylistEntity(id = 7, name = "我的歌单", createdAt = 1, updatedAt = 1),
                        PlaylistEntity(id = 9, name = "通勤专用", createdAt = 2, updatedAt = 2),
                    ),
                    onPick = { picked = it },
                    onCreate = { creating = true },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("我的歌单").assertIsDisplayed()
        compose.onNodeWithText("通勤专用").assertIsDisplayed()
        compose.onNodeWithText("通勤专用").performClick()
        check(picked == 9L) { "点击歌单未回调正确的 id" }
        check(!creating)
    }

    @Test
    fun 加歌单_无歌单时直接进新建() {
        var created = false
        compose.setContent {
            BiliSeekTheme {
                PlaylistPickerDialog(
                    playlists = emptyList(),
                    onPick = {},
                    onCreate = { created = true },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("新建歌单并加入").assertIsDisplayed()
        compose.onNodeWithText("歌单名").assertIsDisplayed()
    }

    // ── 下载确认（M9 E-4 内容二选一 + 位置三选一）──────────

    @Test
    fun 下载对话框_内容与位置选项齐全() {
        compose.setContent {
            BiliSeekTheme {
                DownloadDialog(
                    target = SearchVideo(bvid = "BV1xx411c7mD", title = "下载测试曲目", author = "UP").toDownloadTarget(),
                    settings = AppSettings(),
                    onDismiss = {},
                    onConfirm = {},
                    onRememberLocation = {},
                    onRememberSafTree = {},
                )
            }
        }

        compose.onNodeWithText("下载").assertIsDisplayed()
        compose.onNodeWithText("只音频（m4a）").assertIsDisplayed()
        compose.onNodeWithText("视频（mp4）").assertIsDisplayed()
        compose.onNodeWithText("应用内").assertIsDisplayed()
        compose.onNodeWithText("公共媒体库").assertIsDisplayed()
        compose.onNodeWithText("自定义目录").assertIsDisplayed()
        compose.onNodeWithText("开始下载").assertIsDisplayed()
    }

    @Test
    fun 下载对话框_确认带出内容与位置() {
        var request: DownloadRequest? = null
        compose.setContent {
            BiliSeekTheme {
                DownloadDialog(
                    target = SearchVideo(bvid = "BV1xx411c7mD", title = "下载测试曲目", author = "UP").toDownloadTarget(),
                    settings = AppSettings(),
                    onDismiss = {},
                    onConfirm = { request = it },
                    onRememberLocation = {},
                    onRememberSafTree = {},
                )
            }
        }

        compose.onNodeWithText("开始下载").performClick()
        check(request != null) { "未触发 onConfirm" }
        check(request!!.bvid == "BV1xx411c7mD")
        check(request!!.content == DownloadContent.AUDIO)
    }
}
