package com.biliseek.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.biliseek.app.download.DownloadRequest
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.normalizeCoverUrl
import com.biliseek.core.network.stripHighlight

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
 * 下载确认面板：内容（只音频 / 视频）+ 保存位置（私有 / 公共 / 自定义目录）。
 *
 * 两个维度正交（M9 §9.2）。位置记住上次选择，下次默认带入。
 * SAF 选目录用 [androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree]，
 * 选中即 `takePersistableUriPermission`——下次重进 App 不必重选。
 */
@Composable
fun DownloadDialog(
    target: DownloadTarget,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onConfirm: (DownloadRequest) -> Unit,
    onRememberLocation: (String) -> Unit,
    onRememberSafTree: (String) -> Unit,
) {
    var content by remember { mutableStateOf(DownloadContent.AUDIO) }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    target.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Text("内容", style = MaterialTheme.typography.labelMedium)
                ChoiceRow(
                    options = listOf("只音频（m4a）" to DownloadContent.AUDIO, "视频（mp4）" to DownloadContent.VIDEO),
                    selected = content,
                    onSelect = { content = it },
                )
                Text(
                    if (content == DownloadContent.AUDIO) {
                        "DASH 音频轨即完整文件，无需转码"
                    } else {
                        "音频 + 视频轨合成 MP4，只封装不重编码"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )

                Text("保存位置", style = MaterialTheme.typography.labelMedium)
                ChoiceRow(
                    options = listOf(
                        "应用内" to DownloadLocation.PRIVATE,
                        "公共媒体库" to DownloadLocation.PUBLIC,
                        "自定义目录" to DownloadLocation.SAF,
                    ),
                    selected = location,
                    onSelect = { location = it },
                )
                Text(
                    when (location) {
                        DownloadLocation.PRIVATE -> "应用私有目录，卸载即清。供离线播放"
                        DownloadLocation.PUBLIC -> "音频进系统音乐、视频进系统相册，卸载后仍在"
                        else -> "用系统文件选择器选一个文件夹"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
                if (location == DownloadLocation.SAF) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (safTreeUri.isNotBlank()) "已选择：$safTreeUri" else "尚未选择文件夹",
                            Modifier.weight(1f).padding(end = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { pickFolder.launch(null) }) { Text("选择") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // SAF 下必须真的选过目录
                    if (location == DownloadLocation.SAF && safTreeUri.isBlank()) return@TextButton
                    onRememberLocation(location)
                    onConfirm(
                        DownloadRequest(
                            bvid = target.bvid,
                            cid = target.cid.takeIf { it > 0 },
                            title = target.title,
                            author = target.author,
                            coverUrl = target.coverUrl,
                            content = content,
                            location = location,
                            safTreeUri = if (location == DownloadLocation.SAF) safTreeUri else "",
                        ),
                    )
                    onDismiss()
                },
            ) { Text("开始下载") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ChoiceRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column {
        options.forEach { (label, value) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(value) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == value, onClick = { onSelect(value) })
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
