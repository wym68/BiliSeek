package com.biliseek.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.biliseek.core.asr.AsrModelSpec
import com.biliseek.core.asr.ModelSource
import com.biliseek.core.asr.ModelState
import com.biliseek.designsystem.component.BsConfirmDialog

/**
 * 设置 → AI 总结 → 离线识别模型（工单 AI-8）：下载（选来源）/ 导入文件 / 删除 / 状态。
 * 计量网络下下载先确认。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AsrModelCard(
    spec: AsrModelSpec,
    state: ModelState,
    isMetered: () -> Boolean,
    onDownload: (ModelSource) -> Unit,
    onImport: (List<Uri>) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var source by remember { mutableStateOf(spec.sources.first()) }
    var confirmMetered by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onImport(uris)
    }
    val sizeMb = spec.totalBytes / 1_000_000

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("asr_model_card")) {
        Text("离线识别模型", style = MaterialTheme.typography.titleSmall)
        Text(
            "没有字幕的视频在本机识别语音，音频不会上传。${spec.displayName}，约 $sizeMb MB。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state) {
            ModelState.NotInstalled -> Text("未安装", style = MaterialTheme.typography.bodyMedium)
            is ModelState.Downloading -> {
                val fraction = if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f
                Text(
                    "下载中 ${(fraction * 100).toInt()}%（${state.doneBytes / 1_000_000} / ${state.totalBytes / 1_000_000} MB）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            ModelState.Verifying -> {
                Text("校验中…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            ModelState.Ready -> Text("已就绪", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            is ModelState.Broken -> Text(state.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        val busy = state is ModelState.Downloading || state == ModelState.Verifying
        if (!busy && state != ModelState.Ready) {
            Text("下载来源", style = MaterialTheme.typography.bodySmall)
            spec.sources.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = source == s, onClick = { source = s }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = source == s, onClick = { source = s })
                    Text(s.displayName, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                busy -> OutlinedButton(onClick = onCancel) { Text("取消") }
                state == ModelState.Ready -> OutlinedButton(onClick = { confirmDelete = true }) { Text("删除模型") }
                else -> {
                    OutlinedButton(
                        onClick = { if (isMetered()) confirmMetered = true else onDownload(source) },
                        modifier = Modifier.testTag("asr_download"),
                    ) { Text("下载") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("导入文件") }
                }
            }
        }
        if (state != ModelState.Ready && !busy) {
            Text(
                "导入：从 sherpa-onnx 发布页下载模型压缩包，解压后同时选中 " +
                    spec.files.joinToString("、") { it.name } + "。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { uriHandler.openUri(spec.licenseUrl) }) { Text("模型许可证：${spec.license}") }
    }

    if (confirmMetered) {
        BsConfirmDialog(
            title = "使用移动网络下载？",
            text = "模型约 $sizeMb MB，当前是移动网络。",
            confirmLabel = "仍然下载",
            onConfirm = {
                confirmMetered = false
                onDownload(source)
            },
            onDismiss = { confirmMetered = false },
        )
    }
    if (confirmDelete) {
        BsConfirmDialog(
            title = "删除离线识别模型？",
            text = "删除后，没有字幕的视频需要重新下载模型才能总结。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
