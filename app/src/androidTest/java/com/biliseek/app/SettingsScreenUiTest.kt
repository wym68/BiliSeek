package com.biliseek.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.biliseek.app.data.AppSettings
import com.biliseek.app.data.DefaultPresentation
import com.biliseek.app.ui.SettingsScreen
import com.biliseek.core.player.AudioQuality
import com.biliseek.designsystem.theme.BiliSeekTheme
import org.junit.Rule
import org.junit.Test

/**
 * 设置页各组渲染、单选弹层与回调测试（分组卡片版式）。
 *
 * 覆盖《M12 计划》B-3 的 SettingsScreen 部分。
 */
class SettingsScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setSettings(
        settings: AppSettings = AppSettings(),
        onDefaultChange: (DefaultPresentation) -> Unit = {},
        onMeteredChange: (Boolean) -> Unit = {},
        onQualityChange: (AudioQuality) -> Unit = {},
        onLoudnessChange: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            BiliSeekTheme {
                SettingsScreen(
                    settings = settings,
                    onDefaultPresentationChange = onDefaultChange,
                    onAllowVideoOnMeteredChange = onMeteredChange,
                    onAudioQualityChange = onQualityChange,
                    onLoudnessChange = onLoudnessChange,
                    accountContent = { Text("账号占位") },
                )
            }
        }
    }

    @Test
    fun 各组均渲染() {
        setSettings()

        // 账号卡片（未登录）
        compose.onNodeWithText("登录 B 站账号").assertIsDisplayed()
        // 播放
        compose.onNodeWithText("起播模式").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("列表播放模式").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("弹幕").performScrollTo().assertIsDisplayed()
        // 视频与流量
        compose.onNodeWithText("移动网络下默认允许看视频").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("音质").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun 出厂默认_起播模式显示默认只听() {
        setSettings()

        compose.onNodeWithText("默认只听").assertIsDisplayed()
    }

    @Test
    fun 起播模式弹层选记住上次_触发回调() {
        var selected: DefaultPresentation? = null
        setSettings(onDefaultChange = { selected = it })

        compose.onNodeWithText("起播模式").performClick()
        compose.onNodeWithText("记住上次").performClick()
        check(selected == DefaultPresentation.REMEMBER_LAST) { "选「记住上次」未触发回调" }
    }

    @Test
    fun 音质弹层选无损_触发回调() {
        var quality: AudioQuality? = null
        setSettings(onQualityChange = { quality = it })

        compose.onNodeWithText("音质").performScrollTo().performClick()
        compose.onNodeWithText("无损优先").performClick()
        check(quality == AudioQuality.LOSSLESS) { "选「无损优先」未触发回调" }
    }

    @Test
    fun 未登录点账号卡片_弹出登录面板() {
        setSettings()

        compose.onNodeWithText("登录 B 站账号").performClick()
        compose.onNodeWithText("账号占位").assertIsDisplayed()
    }
}
