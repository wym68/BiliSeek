package com.biliseek.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.biliseek.app.data.AppSettings
import com.biliseek.app.data.DownloadContent
import com.biliseek.app.data.PlaylistEntity
import com.biliseek.app.download.DownloadRequest
import com.biliseek.app.ui.AddToListPickerDialog
import com.biliseek.app.ui.DownloadDialog
import com.biliseek.app.ui.MeteredVideoDialog
import com.biliseek.app.ui.toDownloadTarget
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.designsystem.theme.BiliSeekTheme
import org.junit.Rule
import org.junit.Test

/**
 * 三个对话框的交互测试：流量拦截 / 加入列表选择 / 下载确认。
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

    // ── 加入列表选择（M7 C-4；加入列表改造后为 AddToListPickerDialog）──────

    /** 未登录形态：只有本地歌单区，收藏夹相关回调都不该被触发。 */
    @Composable
    private fun LoggedOutPicker(
        playlists: List<PlaylistEntity>,
        onPickPlaylist: (Long) -> Unit = {},
        onCreatePlaylist: (String) -> Unit = {},
    ) {
        AddToListPickerDialog(
            playlists = playlists,
            favoriteFolders = emptyList(),
            isLoggedIn = false,
            onPickPlaylist = onPickPlaylist,
            onCreatePlaylist = onCreatePlaylist,
            onRenamePlaylist = { _, _ -> },
            onDeletePlaylist = {},
            onPickFavorite = {},
            onCreateFavorite = { _, _ -> },
            onEditFavorite = { _, _ -> },
            onDeleteFavorite = {},
            onDismiss = {},
        )
    }

    @Test
    fun 加入列表_列出本地歌单并回调选中项() {
        var picked: Long? = null
        var created: String? = null
        compose.setContent {
            BiliSeekTheme {
                LoggedOutPicker(
                    playlists = listOf(
                        PlaylistEntity(id = 7, name = "我的歌单", createdAt = 1, updatedAt = 1),
                        PlaylistEntity(id = 9, name = "通勤专用", createdAt = 2, updatedAt = 2),
                    ),
                    onPickPlaylist = { picked = it },
                    onCreatePlaylist = { created = it },
                )
            }
        }

        compose.onNodeWithText("我的歌单").assertIsDisplayed()
        compose.onNodeWithText("通勤专用").assertIsDisplayed()
        compose.onNodeWithText("B站收藏夹").assertDoesNotExist()
        compose.onNodeWithText("通勤专用").performClick()
        check(picked == 9L) { "点击歌单未回调正确的 id" }
        check(created == null)
    }

    @Test
    fun 加入列表_未登录且无歌单时直接进新建() {
        compose.setContent {
            BiliSeekTheme {
                LoggedOutPicker(playlists = emptyList())
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
                    targets = listOf(SearchVideo(bvid = "BV1xx411c7mD", title = "下载测试曲目", author = "UP").toDownloadTarget()),
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
        var requests: List<DownloadRequest>? = null
        compose.setContent {
            BiliSeekTheme {
                DownloadDialog(
                    targets = listOf(SearchVideo(bvid = "BV1xx411c7mD", title = "下载测试曲目", author = "UP").toDownloadTarget()),
                    settings = AppSettings(),
                    onDismiss = {},
                    onConfirm = { requests = it },
                    onRememberLocation = {},
                    onRememberSafTree = {},
                )
            }
        }

        compose.onNodeWithText("开始下载").performClick()
        val request = requests?.singleOrNull()
        check(request != null) { "未触发 onConfirm 或条数不对" }
        check(request.bvid == "BV1xx411c7mD")
        check(request.content == DownloadContent.AUDIO)
    }
}
