package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.biliseek.app.data.AppSettings
import com.biliseek.app.data.DefaultPresentation
import com.biliseek.core.player.AudioQuality

/**
 * 设置页。
 *
 * 分组顺序按「用得最多的排前面」：播放 → 网络 → 音质 → 账号 → 关于。
 * 账号排最后是有意的——**登录非强制**，把它摆在第一屏会暗示必须先登录。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onDefaultPresentationChange: (DefaultPresentation) -> Unit,
    onAllowVideoOnMeteredChange: (Boolean) -> Unit,
    onAudioQualityChange: (AudioQuality) -> Unit,
    onLoudnessChange: (Boolean) -> Unit,
    accountContent: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("播放")
        OptionRow(
            label = "默认只听",
            description = "每次起播都先进纯音频模式",
            selected = settings.defaultPresentation == DefaultPresentation.AUDIO_ONLY,
            onSelect = { onDefaultPresentationChange(DefaultPresentation.AUDIO_ONLY) },
        )
        OptionRow(
            label = "记住上次",
            description = "上次在看模式，下次起播直接进看模式",
            selected = settings.defaultPresentation == DefaultPresentation.REMEMBER_LAST,
            onSelect = { onDefaultPresentationChange(DefaultPresentation.REMEMBER_LAST) },
        )

        HorizontalDivider()
        SectionTitle("网络")
        SwitchRow(
            label = "移动网络下允许看视频",
            description = "关闭时仍可正常听音频——视频码率比音频高一个量级",
            checked = settings.allowVideoOnMetered,
            onCheckedChange = onAllowVideoOnMeteredChange,
        )

        HorizontalDivider()
        SectionTitle("音质")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QualityChip("自动", AudioQuality.AUTO, settings.audioQuality, onAudioQualityChange)
            QualityChip("无损优先", AudioQuality.LOSSLESS, settings.audioQuality, onAudioQualityChange)
            QualityChip("省流量", AudioQuality.SAVER, settings.audioQuality, onAudioQualityChange)
        }
        Text(
            when (settings.audioQuality) {
                AudioQuality.AUTO -> "按网络状况在普通音轨间自适应"
                AudioQuality.LOSSLESS -> "优先无损 / 杜比，需要大会员；拿不到时自动回退，播放页会说明原因"
                AudioQuality.SAVER -> "固定用最低码率音轨"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        SwitchRow(
            label = "响度均衡",
            description = "把过响的投稿压到接近的响度，避免切歌时忽大忽小",
            checked = settings.loudnessNormalization,
            onCheckedChange = onLoudnessChange,
        )

        HorizontalDivider()
        SectionTitle("账号")
        accountContent()

        HorizontalDivider()
        SectionTitle("关于")
        AboutSection()
    }
}

/** M10：版本号 + 源码入口 + 开源许可（GPLv3）。 */
@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    // versionName 来自安装的包本身，保证与发版一致
    val versionName = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "未知"
    }
    // NOTICE 打包在 assets/NOTICE.txt（与仓库根 NOTICE 同步）
    val noticeText = remember {
        runCatching {
            context.assets.open("NOTICE.txt").bufferedReader().use { it.readText() }
        }.getOrNull()
    }
    var showLicense by remember { mutableStateOf(false) }

    AboutRow(label = "版本", value = versionName)
    // GPLv3 源码入口：首次发版必须指向真实公开仓库
    AboutRow(
        label = "源码",
        value = "github.com/wym68/BiliSeek",
        onClick = { uriHandler.openUri("https://github.com/wym68/BiliSeek") },
    )
    AboutRow(
        label = "开源许可",
        value = if (showLicense) "收起" else "GPLv3 · 点击展开",
        onClick = { showLicense = !showLicense },
    )
    if (showLicense) {
        Text(
            noticeText ?: "许可信息加载失败（应打包在 assets/NOTICE.txt）",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AboutRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun OptionRow(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun QualityChip(
    label: String,
    value: AudioQuality,
    current: AudioQuality,
    onSelect: (AudioQuality) -> Unit,
) {
    FilterChip(
        selected = current == value,
        onClick = { onSelect(value) },
        label = { Text(label) },
    )
}
