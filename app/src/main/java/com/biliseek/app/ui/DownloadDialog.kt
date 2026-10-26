package com.biliseek.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.data.AppSettings
import com.biliseek.app.data.DownloadContent
import com.biliseek.app.data.DownloadLocation
import com.biliseek.app.data.VideoQualityDefaults
import com.biliseek.app.download.DownloadRequest
import com.biliseek.app.ui.components.SettingsRadioRow
import com.biliseek.app.ui.components.SettingsSheet
import com.biliseek.app.ui.components.SettingsSheetSection
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.network.stripHighlight
import com.biliseek.core.player.VIDEO_QUALITY_AUTO
import com.biliseek.designsystem.theme.BsSpacing

/**
 * 下载弹窗的输入。从 SearchVideo 泛化出来：多 P 面板的分 P 与 UP 空间投稿
 * 也走同一弹窗，cid > 0 时下载精确到分 P（0 = 交给下载器解析第一个分 P）。
 */
data class DownloadTarget(
    val bvid: String,
    val cid: Long = 0,
    val title: String,
    val coverUrl: String,
    val author: String,
)

/** SearchVideo → 下载目标（搜索结果单条下载）。 */
fun SearchVideo.toDownloadTarget(): DownloadTarget = DownloadTarget(
    bvid = bvid,
    title = stripHighlight(title),
    coverUrl = normalizeCoverUrl(pic),
    author = author,
)

/**
 * 下载确认面板（底部弹层，与设置页同一套外观）：内容（只音频 / 视频）+ 画质（选了视频时）+
 * 保存位置（私有 / 公共 / 自定义目录）。
 * 一次可以确认多条（多 P 选完分 P、批量选择后）：内容、画质与位置选一次，对全部生效。
 * 画质默认带入 [defaultVideoQuality]（设置里当前网络的画质）；自动 = 最高一档，不设上限。
 *
 * 两个维度正交（M9 §9.2）。位置记住上次选择，下次默认带入。
 * SAF 选目录用 [androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree]，
 * 选中即 `takePersistableUriPermission`——下次重进 App 不必重选。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadDialog(
    targets: List<DownloadTarget>,
    settings: AppSettings,
    defaultVideoQuality: Int = VIDEO_QUALITY_AUTO,
    onDismiss: () -> Unit,
    onConfirm: (List<DownloadRequest>) -> Unit,
    onRememberLocation: (String) -> Unit,
    onRememberSafTree: (String) -> Unit,
) {
    var content by remember { mutableStateOf(DownloadContent.AUDIO) }
    var videoQn by remember { mutableStateOf(defaultVideoQuality) }
    var location by remember { mutableStateOf(settings.lastDownloadLocation) }
    var safTreeUri by remember { mutableStateOf(settings.lastDownloadSafTreeUri) }

    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        safTreeUri = uri.toString()
        onRememberSafTree(uri.toString())
    }

    // SAF 下必须真的选过目录
    val canConfirm = location != DownloadLocation.SAF || safTreeUri.isNotBlank()

    SettingsSheet(
        title = "下载",
        note = when {
            targets.size > 1 -> "${targets.first().title} 等 ${targets.size} 项"
            else -> targets.firstOrNull()?.title.orEmpty()
        },
        onDismiss = onDismiss,
    ) {
        SettingsSheetSection("内容")
        SettingsRadioRow(
            label = "只音频（m4a）",
            description = "DASH 音频轨即完整文件，无需转码",
            selected = content == DownloadContent.AUDIO,
            onClick = { content = DownloadContent.AUDIO },
        )
        SettingsRadioRow(
            label = "视频（mp4）",
            description = "音频 + 视频轨合成 MP4，只封装不重编码",
            selected = content == DownloadContent.VIDEO,
            onClick = { content = DownloadContent.VIDEO },
        )
        if (content == DownloadContent.VIDEO) {
            SettingsSheetSection("画质")
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = BsSpacing.xl),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VideoQualityDefaults.CHOICES.forEach { (qn, label) ->
                    FilterChip(
                        selected = videoQn == qn,
                        onClick = { videoQn = qn },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "自动取这个视频的最高画质；所选画质没有时用低一档的",
                Modifier.padding(horizontal = BsSpacing.xl),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSheetSection("保存位置")
        SettingsRadioRow(
            label = "应用内",
            description = "应用私有目录，卸载即清。供离线播放",
            selected = location == DownloadLocation.PRIVATE,
            onClick = { location = DownloadLocation.PRIVATE },
        )
        SettingsRadioRow(
            label = "公共媒体库",
            description = "音频进系统音乐、视频进系统相册，卸载后仍在",
            selected = location == DownloadLocation.PUBLIC,
            onClick = { location = DownloadLocation.PUBLIC },
        )
        SettingsRadioRow(
            label = "自定义目录",
            description = "用系统文件选择器选一个文件夹",
            selected = location == DownloadLocation.SAF,
            onClick = { location = DownloadLocation.SAF },
        )
        if (location == DownloadLocation.SAF) {
            Row(
                Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (safTreeUri.isNotBlank()) "已选择：${folderLabel(safTreeUri)}" else "尚未选择文件夹",
                    Modifier.weight(1f).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (safTreeUri.isNotBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { pickFolder.launch(null) }) { Text("选择") }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = {
                    onRememberLocation(location)
                    onConfirm(
                        targets.map { target ->
                            DownloadRequest(
                                bvid = target.bvid,
                                cid = target.cid.takeIf { it > 0 },
                                title = target.title,
                                author = target.author,
                                coverUrl = target.coverUrl,
                                content = content,
                                location = location,
                                safTreeUri = if (location == DownloadLocation.SAF) safTreeUri else "",
                                videoQn = videoQn,
                            )
                        },
                    )
                    onDismiss()
                },
                enabled = canConfirm,
                modifier = Modifier.weight(1f),
            ) { Text("开始下载") }
        }
    }
}

/** SAF 目录 uri → 可读的文件夹名（`primary:Music/BiliSeek` → `Music/BiliSeek`）。 */
private fun folderLabel(treeUri: String): String =
    Uri.parse(treeUri).lastPathSegment?.substringAfter(':')?.ifBlank { null } ?: treeUri
