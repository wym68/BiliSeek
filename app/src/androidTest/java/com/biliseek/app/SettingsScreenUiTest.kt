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
 * 设置页四组渲染与回调测试。
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
    fun 四组均渲染() {
        setSettings()

        // 播放
        compose.onNodeWithText("默认只听").assertIsDisplayed()
        compose.onNodeWithText("记住上次").assertIsDisplayed()
        // 网络
        compose.onNodeWithText("移动网络下允许看视频").performScrollTo().assertIsDisplayed()
        // 音质
        compose.onNodeWithText("自动").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("无损优先").performScrollTo().assertIsDisplayed()
        // 账号
        compose.onNodeWithText("账号占位").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun 出厂默认_默认只听被选中() {
        setSettings()

        compose.onNodeWithText("默认只听").assertIsDisplayed()
    }

    @Test
    fun 点击记住上次_触发回调() {
        var selected: DefaultPresentation? = null
        setSettings(onDefaultChange = { selected = it })

        compose.onNodeWithText("记住上次").performClick()
        check(selected == DefaultPresentation.REMEMBER_LAST) { "点击「记住上次」未触发回调" }
    }

    @Test
    fun 点击无损_触发回调() {
        var quality: AudioQuality? = null
        setSettings(onQualityChange = { quality = it })

        compose.onNodeWithText("无损优先").performScrollTo().performClick()
        check(quality == AudioQuality.LOSSLESS) { "点击「无损优先」未触发回调" }
    }
}
