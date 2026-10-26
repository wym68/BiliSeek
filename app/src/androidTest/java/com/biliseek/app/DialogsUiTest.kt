package com.biliseek.app

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.biliseek.app.data.AppSettings
import com.biliseek.app.data.DownloadContent
import com.biliseek.app.data.FavoriteFolderEntity
import com.biliseek.app.data.PlaylistEntity
import com.biliseek.app.download.DownloadRequest
import com.biliseek.app.ui.AddToListPickerDialog
import com.biliseek.app.ui.DownloadDialog
import com.biliseek.app.ui.MeteredVideoDialog
import com.biliseek.app.ui.components.InputDialog
import com.biliseek.app.ui.toDownloadTarget
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.designsystem.component.BsConfirmDialog
import com.biliseek.designsystem.theme.BiliSeekTheme
import org.junit.Rule
import org.junit.Test

/**
 * 对话框与弹层的交互测试：流量拦截 / 加入列表选择 / 输入弹窗 / 确认框忙碌态 / 下载确认。
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
        onCreatePlaylist: (String, String) -> Unit = { _, _ -> },
        onDismiss: () -> Unit = {},
    ) {
        AddToListPickerDialog(
            playlists = playlists,
            favoriteFolders = emptyList(),
            isLoggedIn = false,
            onPickWatchLater = {},
            onPickPlaylist = onPickPlaylist,
            onCreatePlaylist = onCreatePlaylist,
            onRenamePlaylist = { _, _, _ -> },
            onDeletePlaylist = {},
            onPickFavorite = {},
            onCreateFavorite = { _, _ -> },
            onEditFavorite = { _, _ -> },
            onDeleteFavorite = {},
            onDismiss = onDismiss,
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
                    onCreatePlaylist = { name, _ -> created = name },
                )
            }
        }

        compose.onNodeWithText("我的歌单").assertIsDisplayed()
        compose.onNodeWithText("通勤专用").assertIsDisplayed()
        compose.onNodeWithText("B站收藏夹").assertDoesNotExist()
        compose.onNodeWithText("稍后再看").assertDoesNotExist()
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

    @Test
    fun 加入列表_收藏夹行只显示名字不显示数量() {
        compose.setContent {
            BiliSeekTheme {
                AddToListPickerDialog(
                    playlists = listOf(PlaylistEntity(id = 7, name = "我的歌单", createdAt = 1, updatedAt = 1)),
                    favoriteFolders = listOf(
                        FavoriteFolderEntity(mediaId = 1, title = "默认收藏夹", coverUrl = "", mediaCount = 3, syncedAt = 1),
                        FavoriteFolderEntity(mediaId = 2, title = "音乐", coverUrl = "", mediaCount = 12, syncedAt = 1),
                    ),
                    isLoggedIn = true,
                    onPickWatchLater = {},
                    onPickPlaylist = {},
                    onCreatePlaylist = { _, _ -> },
                    onRenamePlaylist = { _, _, _ -> },
                    onDeletePlaylist = {},
                    onPickFavorite = {},
                    onCreateFavorite = { _, _ -> },
                    onEditFavorite = { _, _ -> },
                    onDeleteFavorite = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("B站收藏夹").assertIsDisplayed()
        compose.onNodeWithText("默认收藏夹").assertIsDisplayed()
        compose.onNodeWithText("音乐").assertIsDisplayed()
        compose.onNodeWithText("个内容", substring = true).assertDoesNotExist()
    }

    @Test
    fun 加入列表_登录后最上面是稍后再看_点了回调() {
        var pickedWatchLater = false
        compose.setContent {
            BiliSeekTheme {
                AddToListPickerDialog(
                    playlists = listOf(PlaylistEntity(id = 7, name = "我的歌单", createdAt = 1, updatedAt = 1)),
                    favoriteFolders = listOf(
                        FavoriteFolderEntity(mediaId = 1, title = "默认收藏夹", coverUrl = "", mediaCount = 3, syncedAt = 1),
                    ),
                    isLoggedIn = true,
                    onPickWatchLater = { pickedWatchLater = true },
                    onPickPlaylist = {},
                    onCreatePlaylist = { _, _ -> },
                    onRenamePlaylist = { _, _, _ -> },
                    onDeletePlaylist = {},
                    onPickFavorite = {},
                    onCreateFavorite = { _, _ -> },
                    onEditFavorite = { _, _ -> },
                    onDeleteFavorite = {},
                    onDismiss = {},
                )
            }
        }

        val watchLaterTop = compose.onNodeWithText("稍后再看").assertIsDisplayed().getUnclippedBoundsInRoot().top
        val localTop = compose.onNodeWithText("本地歌单").getUnclippedBoundsInRoot().top
        check(watchLaterTop < localTop) { "稍后再看应在本地歌单分区之上" }
        compose.onNodeWithText("稍后再看").performClick()
        check(pickedWatchLater) { "点稍后再看未回调" }
    }

    @Test
    fun 加入列表_点取消关闭() {
        var dismissed = false
        compose.setContent {
            BiliSeekTheme {
                LoggedOutPicker(
                    playlists = listOf(PlaylistEntity(id = 7, name = "我的歌单", createdAt = 1, updatedAt = 1)),
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("取消").performClick()
        check(dismissed) { "点「取消」没有关闭加入列表" }
    }

    // ── 输入弹窗（新建 / 重命名）────────────────────────────

    @Test
    fun 输入弹窗_名字为空时确认键不可点_填了之后带出文字() {
        var confirmed: String? = null
        compose.setContent {
            BiliSeekTheme {
                var name by remember { mutableStateOf("") }
                InputDialog(
                    title = "新建歌单",
                    confirmLabel = "创建",
                    confirmEnabled = name.isNotBlank(),
                    onConfirm = { confirmed = name },
                    onDismiss = {},
                ) { focus ->
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("歌单名") },
                        modifier = Modifier.focusRequester(focus),
                    )
                }
            }
        }

        compose.onNodeWithText("新建歌单").assertIsDisplayed()
        compose.onNodeWithText("创建").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("通勤专用")
        compose.onNodeWithText("创建").assertIsEnabled().performClick()
        check(confirmed == "通勤专用") { "确认没有带出输入的文字：$confirmed" }
    }

    // ── 确认框的忙碌态（删除收藏夹要清空视频，过程中不可操作）────────

    @Test
    fun 确认框_忙碌时显示处理中且按钮不可点() {
        var confirmed = false
        var dismissed = false
        compose.setContent {
            BiliSeekTheme {
                BsConfirmDialog(
                    title = "删除收藏夹",
                    text = "确定删除「音乐」？",
                    confirmLabel = "删除",
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true },
                    destructive = true,
                    busy = true,
                )
            }
        }

        compose.onNodeWithText("处理中…").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsNotEnabled()
        check(!confirmed && !dismissed)
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
        compose.onNodeWithText("只音频（m4a）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("视频（mp4）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("应用内").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("公共媒体库").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("自定义目录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("开始下载").performScrollTo().assertIsDisplayed()
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

        compose.onNodeWithText("开始下载").performScrollTo().performClick()
        val request = requests?.singleOrNull()
        check(request != null) { "未触发 onConfirm 或条数不对" }
        check(request.bvid == "BV1xx411c7mD")
        check(request.content == DownloadContent.AUDIO)
    }

    @Test
    fun 下载对话框_选视频后可选画质_默认带入设置() {
        var requests: List<DownloadRequest>? = null
        compose.setContent {
            BiliSeekTheme {
                DownloadDialog(
                    targets = listOf(SearchVideo(bvid = "BV1xx411c7mD", title = "下载测试曲目", author = "UP").toDownloadTarget()),
                    settings = AppSettings(),
                    defaultVideoQuality = 80,
                    onDismiss = {},
                    onConfirm = { requests = it },
                    onRememberLocation = {},
                    onRememberSafTree = {},
                )
            }
        }

        // 只音频时没有画质
        compose.onNodeWithText("720P").assertDoesNotExist()
        compose.onNodeWithText("视频（mp4）").performScrollTo().performClick()
        compose.onNodeWithText("1080P").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("720P").performScrollTo().performClick()
        compose.onNodeWithText("开始下载").performScrollTo().performClick()
        val request = requests?.singleOrNull()
        check(request != null) { "未触发 onConfirm 或条数不对" }
        check(request.content == DownloadContent.VIDEO)
        check(request.videoQn == 64) { "画质没带出来：${request.videoQn}" }
    }
}
