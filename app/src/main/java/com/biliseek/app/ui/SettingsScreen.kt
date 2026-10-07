package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NetworkCell
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.app.danmaku.DanmakuSettings
import com.biliseek.app.danmaku.DanmakuSettingsOptions
import com.biliseek.app.data.AppSettings
import com.biliseek.app.data.DefaultPresentation
import com.biliseek.app.data.MiniBarStyle
import com.biliseek.app.data.VideoQualityDefaults
import com.biliseek.app.data.DOWNLOAD_CONCURRENCY_CHOICES
import com.biliseek.app.data.MAX_DOWNLOAD_CONCURRENCY
import com.biliseek.app.ui.components.LocalHints
import com.biliseek.app.ui.components.LocalMiniBarInset
import com.biliseek.app.ui.components.LocalShowMessage
import com.biliseek.app.ui.components.SettingsChoice
import com.biliseek.app.ui.components.SettingsChoiceSheet
import com.biliseek.app.ui.components.SettingsDivider
import com.biliseek.app.ui.components.SettingsGroup
import com.biliseek.app.ui.components.SettingsRow
import com.biliseek.app.ui.components.SettingsSheet
import com.biliseek.app.ui.components.SettingsSwitchRow
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.player.AudioQuality
import com.biliseek.core.player.PlayMode
import com.biliseek.designsystem.component.BsConfirmDialog
import com.biliseek.designsystem.component.BsTopBar
import com.biliseek.designsystem.theme.BsShapes
import com.biliseek.designsystem.theme.BsSpacing

/** 设置页当前打开的弹层。 */
private enum class SettingsSheetKind {
    LOGIN, PRESENTATION, PLAY_MODE, DANMAKU, AUDIO_QUALITY, VIDEO_QUALITY_WIFI, VIDEO_QUALITY_METERED, MINI_BAR, THEME, LICENSE,
    DOWNLOAD_CONCURRENCY,
}

/**
 * 设置页。
 *
 * 版式（统一外观见 `ui/components/SettingsComponents.kt`）：顶部账号卡片，其余按用途分组成圆角卡片——
 * 播放 → 视频与流量 → 下载 → 界面 → AI 总结 → 关于，已登录时最下面单独一张「退出登录」。
 * 多选一的设置行上只显示当前值，点开从底部弹出单选；开关型整行可点。
 *
 * 账号卡片放顶部但**登录非强制**：未登录时副标题写明「可选」，点开才弹扫码登录面板。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onDefaultPresentationChange: (DefaultPresentation) -> Unit,
    onAllowVideoOnMeteredChange: (Boolean) -> Unit,
    onAudioQualityChange: (AudioQuality) -> Unit,
    onLoudnessChange: (Boolean) -> Unit,
    /** 扫码登录面板（放在登录弹层里）。 */
    accountContent: @Composable () -> Unit,
    // Ch5
    onMiniBarStyleChange: (MiniBarStyle) -> Unit = {},
    onThemeModeChange: (com.biliseek.app.data.ThemeMode) -> Unit = {},
    onVideoQualityWifiChange: (Int) -> Unit = {},
    onVideoQualityMeteredChange: (Int) -> Unit = {},
    // AI 总结（工单 AI-3）
    onOpenAiSettings: () -> Unit = {},
    aiSettingsSummary: String = "",
    /** 账号卡片：当前会话。 */
    session: BiliSession = BiliSession.EMPTY,
    /** 上次登录失败 / 登录过期的原因，未登录时显示在账号卡片上。 */
    loginFailureReason: String? = null,
    onOpenOwnSpace: () -> Unit = {},
    onLogout: () -> Unit = {},
    /** 列表播放模式：与播放页的播放模式按钮是同一个值。 */
    playMode: PlayMode = PlayMode.SEQUENTIAL,
    onPlayModeChange: (PlayMode) -> Unit = {},
    onDanmakuSettingsChange: ((DanmakuSettings) -> DanmakuSettings) -> Unit = {},
    onDownloadConcurrencyChange: (Int) -> Unit = {},
) {
    var sheet by remember { mutableStateOf<SettingsSheetKind?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val dismiss = { sheet = null }

    // 扫码登录成功后自动收起登录弹层
    LaunchedEffect(session.isLoggedIn) {
        if (session.isLoggedIn && sheet == SettingsSheetKind.LOGIN) sheet = null
    }

    Column(Modifier.fillMaxSize()) {
        BsTopBar(title = "设置", onNavigate = null)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BsSpacing.lg)
                // 底部再让出迷你条（它盖在页面最下面）
                .padding(bottom = BsSpacing.xl + LocalMiniBarInset.current),
            verticalArrangement = Arrangement.spacedBy(BsSpacing.lg),
        ) {
            AccountCard(
                session = session,
                loginFailureReason = loginFailureReason,
                onClick = { if (session.isLoggedIn) onOpenOwnSpace() else sheet = SettingsSheetKind.LOGIN },
            )

            SettingsGroup("播放") {
                SettingsRow(
                    title = "起播模式",
                    icon = Icons.Outlined.Headphones,
                    value = presentationLabel(settings.defaultPresentation),
                    onClick = { sheet = SettingsSheetKind.PRESENTATION },
                )
                SettingsDivider()
                SettingsRow(
                    title = "列表播放模式",
                    icon = Icons.Outlined.Repeat,
                    value = playModeChoices.first { it.value == playMode }.label,
                    onClick = { sheet = SettingsSheetKind.PLAY_MODE },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = "响度均衡",
                    subtitle = "把过响的投稿压到接近的响度，切歌时不再忽大忽小",
                    icon = Icons.AutoMirrored.Outlined.VolumeUp,
                    checked = settings.loudnessNormalization,
                    onCheckedChange = onLoudnessChange,
                )
                SettingsDivider()
                SettingsRow(
                    title = "弹幕",
                    subtitle = "默认是否开启与显示样式",
                    icon = Icons.Outlined.Subtitles,
                    value = if (settings.danmaku.enabled) "默认开启" else "默认关闭",
                    onClick = { sheet = SettingsSheetKind.DANMAKU },
                )
            }

            SettingsGroup("视频与流量") {
                SettingsSwitchRow(
                    title = "移动网络下默认允许看视频",
                    subtitle = "关闭时点「看」会先弹窗询问，可以选择这次就看",
                    icon = Icons.Outlined.SignalCellularAlt,
                    checked = settings.allowVideoOnMetered,
                    onCheckedChange = onAllowVideoOnMeteredChange,
                )
                SettingsDivider()
                SettingsRow(
                    title = "音质",
                    icon = Icons.Outlined.GraphicEq,
                    value = audioQualityChoices.first { it.value == settings.audioQuality }.label,
                    onClick = { sheet = SettingsSheetKind.AUDIO_QUALITY },
                )
                SettingsDivider()
                SettingsRow(
                    title = "Wi-Fi 画质",
                    icon = Icons.Outlined.Wifi,
                    value = videoQualityLabel(settings.videoQualityWifi),
                    onClick = { sheet = SettingsSheetKind.VIDEO_QUALITY_WIFI },
                )
                SettingsDivider()
                SettingsRow(
                    title = "移动网络画质",
                    icon = Icons.Outlined.NetworkCell,
                    value = videoQualityLabel(settings.videoQualityMetered),
                    onClick = { sheet = SettingsSheetKind.VIDEO_QUALITY_METERED },
                )
            }

            SettingsGroup("下载") {
                SettingsRow(
                    title = "同时下载数",
                    icon = Icons.Outlined.Download,
                    value = "${settings.downloadConcurrency} 个",
                    onClick = { sheet = SettingsSheetKind.DOWNLOAD_CONCURRENCY },
                )
            }

            SettingsGroup("界面") {
                SettingsRow(
                    title = "深色模式",
                    icon = Icons.Outlined.DarkMode,
                    value = themeChoices.first { it.value == settings.themeMode }.label,
                    onClick = { sheet = SettingsSheetKind.THEME },
                )
                SettingsRow(
                    title = "迷你播放器样式",
                    icon = Icons.Outlined.PictureInPictureAlt,
                    value = miniBarChoices.first { it.value == settings.miniBarStyle }.label,
                    onClick = { sheet = SettingsSheetKind.MINI_BAR },
                )
                // 首次演示全部清掉重来（设计稿 2026-10-07-first-use-hints）
                val hints = LocalHints.current
                val showMessage = LocalShowMessage.current
                SettingsRow(
                    title = "重新显示操作提示",
                    subtitle = "右滑移除、拖进度条等操作的演示再放一次",
                    icon = Icons.Outlined.TouchApp,
                    onClick = {
                        hints?.reset()
                        showMessage?.invoke("已重置，下次碰到时会再演示")
                    },
                )
            }

            SettingsGroup("AI 总结") {
                SettingsRow(
                    title = "模型与风格",
                    subtitle = aiSettingsSummary.ifBlank { null },
                    icon = Icons.Outlined.AutoAwesome,
                    onClick = onOpenAiSettings,
                )
            }

            AboutGroup(onOpenLicense = { sheet = SettingsSheetKind.LICENSE })

            if (session.isLoggedIn) {
                Surface(
                    shape = BsShapes.lg,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "退出登录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { confirmLogout = true }
                            .padding(vertical = 18.dp),
                    )
                }
            }
        }
    }

    when (sheet) {
        SettingsSheetKind.LOGIN -> SettingsSheet(title = "登录 B 站账号", note = "登录是可选的：只听、搜索、下载都不需要登录", onDismiss = dismiss) {
            Column(Modifier.padding(horizontal = BsSpacing.xl)) { accountContent() }
        }
        SettingsSheetKind.PRESENTATION -> SettingsChoiceSheet(
            title = "起播模式",
            choices = presentationChoices,
            selected = settings.defaultPresentation,
            onSelect = onDefaultPresentationChange,
            onDismiss = dismiss,
        )
        SettingsSheetKind.PLAY_MODE -> SettingsChoiceSheet(
            title = "列表播放模式",
            note = "和播放页的播放模式按钮是同一个设置，两边改哪边都行",
            choices = playModeChoices,
            selected = playMode,
            onSelect = onPlayModeChange,
            onDismiss = dismiss,
        )
        SettingsSheetKind.DANMAKU -> SettingsSheet(
            title = "弹幕",
            note = "弹幕只在全屏播放时显示；全屏里的弹幕面板改的是同一份设置",
            onDismiss = dismiss,
        ) {
            SettingsSwitchRow(
                title = "默认开启弹幕",
                icon = Icons.Outlined.Subtitles,
                checked = settings.danmaku.enabled,
                onCheckedChange = { on -> onDanmakuSettingsChange { it.copy(enabled = on) } },
                modifier = Modifier.padding(horizontal = BsSpacing.sm),
            )
            Column(Modifier.padding(horizontal = BsSpacing.xl, vertical = BsSpacing.sm)) {
                DanmakuSettingsOptions(settings = settings.danmaku, onChange = onDanmakuSettingsChange)
            }
        }
        SettingsSheetKind.AUDIO_QUALITY -> SettingsChoiceSheet(
            title = "音质",
            note = "只影响音频轨；播放页「更多」里可以临时切换",
            choices = audioQualityChoices,
            selected = settings.audioQuality,
            onSelect = onAudioQualityChange,
            onDismiss = dismiss,
        )
        SettingsSheetKind.VIDEO_QUALITY_WIFI -> SettingsChoiceSheet(
            title = "Wi-Fi 画质",
            note = VIDEO_QUALITY_NOTE,
            choices = videoQualityChoices,
            selected = settings.videoQualityWifi,
            onSelect = onVideoQualityWifiChange,
            onDismiss = dismiss,
        )
        SettingsSheetKind.VIDEO_QUALITY_METERED -> SettingsChoiceSheet(
            title = "移动网络画质",
            note = VIDEO_QUALITY_NOTE,
            choices = videoQualityChoices,
            selected = settings.videoQualityMetered,
            onSelect = onVideoQualityMeteredChange,
            onDismiss = dismiss,
        )
        SettingsSheetKind.MINI_BAR -> SettingsChoiceSheet(
            title = "迷你播放器样式",
            choices = miniBarChoices,
            selected = settings.miniBarStyle,
            onSelect = onMiniBarStyleChange,
            onDismiss = dismiss,
        )
        SettingsSheetKind.THEME -> SettingsChoiceSheet(
            title = "深色模式",
            choices = themeChoices,
            selected = settings.themeMode,
            onSelect = onThemeModeChange,
            onDismiss = dismiss,
        )
        SettingsSheetKind.DOWNLOAD_CONCURRENCY -> SettingsChoiceSheet(
            title = "同时下载数",
            note = "下载走 B 站视频服务器，不经过业务接口；同时下得越多越占带宽，边下边听时可能卡顿",
            choices = downloadConcurrencyChoices,
            selected = settings.downloadConcurrency,
            onSelect = onDownloadConcurrencyChange,
            onDismiss = dismiss,
        )
        SettingsSheetKind.LICENSE -> LicenseSheet(onDismiss = dismiss)
        null -> Unit
    }

    if (confirmLogout) {
        BsConfirmDialog(
            title = "退出登录？",
            text = "只清除本机的账号登录态，本地歌单、历史和下载都保留。",
            confirmLabel = "退出登录",
            onConfirm = {
                confirmLogout = false
                onLogout()
            },
            onDismiss = { confirmLogout = false },
            destructive = true,
        )
    }
}

private const val VIDEO_QUALITY_NOTE =
    "看模式起播时的默认档位；视频没有这一档时自动往下取最接近的。播放页里临时切换的画质会沿用到本次退出 App 为止"

private val presentationChoices = listOf(
    SettingsChoice(DefaultPresentation.AUDIO_ONLY, "默认只听", "每次起播都先进纯音频模式"),
    SettingsChoice(DefaultPresentation.REMEMBER_LAST, "记住上次", "上次离开播放页时在看，下次打开播放页直接进看模式"),
)

private fun presentationLabel(value: DefaultPresentation): String =
    presentationChoices.firstOrNull { it.value == value }?.label.orEmpty()

private val playModeChoices = listOf(
    SettingsChoice(PlayMode.SEQUENTIAL, playModeLabel(PlayMode.SEQUENTIAL), "按列表顺序播，播完最后一首停止"),
    SettingsChoice(PlayMode.REPEAT_ALL, playModeLabel(PlayMode.REPEAT_ALL), "播完最后一首回到第一首"),
    SettingsChoice(PlayMode.REPEAT_ONE, playModeLabel(PlayMode.REPEAT_ONE), "一直重复当前这一首"),
    SettingsChoice(PlayMode.SHUFFLE, playModeLabel(PlayMode.SHUFFLE), "在列表里随机挑下一首"),
)

private val audioQualityChoices = listOf(
    SettingsChoice(AudioQuality.AUTO, "自动", "按网络状况在普通音轨间自适应"),
    SettingsChoice(AudioQuality.LOSSLESS, "无损优先", "优先无损 / 杜比，需要大会员；拿不到时自动回退，播放页会说明原因"),
    SettingsChoice(AudioQuality.SAVER, "省流量", "固定用最低码率音轨"),
)

private val videoQualityChoices = VideoQualityDefaults.CHOICES.map { (qn, label) -> SettingsChoice(qn, label) }

private fun videoQualityLabel(qn: Int): String =
    videoQualityChoices.firstOrNull { it.value == qn }?.label ?: qn.toString()

private val downloadConcurrencyChoices = DOWNLOAD_CONCURRENCY_CHOICES.map { n ->
    SettingsChoice(
        n,
        "$n 个",
        when (n) {
            1 -> "一条下完再下下一条（默认）"
            MAX_DOWNLOAD_CONCURRENCY -> "最多同时下 $n 条，最占带宽"
            else -> "同时下 $n 条"
        },
    )
}

private val themeChoices = listOf(
    SettingsChoice(com.biliseek.app.data.ThemeMode.SYSTEM, "跟随系统", "系统切换深浅色时跟着变（默认）"),
    SettingsChoice(com.biliseek.app.data.ThemeMode.LIGHT, "浅色", "一直用浅色"),
    SettingsChoice(com.biliseek.app.data.ThemeMode.DARK, "深色", "一直用深色"),
)

private val miniBarChoices = listOf(
    SettingsChoice(MiniBarStyle.FLOAT, "浮在底部", "圆角 + 阴影，悬浮在底栏上方"),
    SettingsChoice(MiniBarStyle.BOTTOM, "贴底", "贴着底栏，只有顶部圆角、无阴影"),
)

/** 顶部账号卡片：已登录显示头像、昵称、UID，点了进自己的空间；未登录点了弹扫码登录。 */
@Composable
private fun AccountCard(
    session: BiliSession,
    loginFailureReason: String?,
    onClick: () -> Unit,
) {
    Surface(
        shape = BsShapes.lg,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 76.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (session.isLoggedIn) {
                AsyncImage(
                    model = normalizeCoverUrl(session.face),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            } else {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (session.isLoggedIn) session.uname.ifBlank { "已登录" } else "登录 B 站账号",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        session.isLoggedIn -> "UID ${session.dedeUserId}"
                        !loginFailureReason.isNullOrBlank() -> loginFailureReason
                        else -> "可选 · 用于收藏、关注、动态和互动"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!session.isLoggedIn && !loginFailureReason.isNullOrBlank()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** M10：版本号 + 源码入口 + 开源许可（GPLv3）。 */
@Composable
private fun AboutGroup(onOpenLicense: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    // versionName 来自安装的包本身，保证与发版一致
    val versionName = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "未知"
    }

    SettingsGroup("关于") {
        SettingsRow(title = "版本", icon = Icons.Outlined.Info, value = versionName)
        SettingsDivider()
        // GPLv3 源码入口：必须指向真实公开仓库
        SettingsRow(
            title = "源码",
            subtitle = "github.com/wym68/BiliSeek",
            icon = Icons.Outlined.Code,
            onClick = { uriHandler.openUri("https://github.com/wym68/BiliSeek") },
            trailing = {
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
        )
        SettingsDivider()
        SettingsRow(title = "开源许可", subtitle = "GPLv3", icon = Icons.Outlined.Gavel, onClick = onOpenLicense)
    }
}

/** 开源许可全文。NOTICE 打包在 assets/NOTICE.txt（与仓库根 NOTICE 同步）。 */
@Composable
private fun LicenseSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val noticeText = remember {
        runCatching {
            context.assets.open("NOTICE.txt").bufferedReader().use { it.readText() }
        }.getOrNull()
    }
    SettingsSheet(title = "开源许可", onDismiss = onDismiss) {
        Text(
            noticeText ?: "许可信息加载失败（应打包在 assets/NOTICE.txt）",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = BsSpacing.xl),
        )
    }
}
