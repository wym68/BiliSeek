package com.biliseek.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 移动网络下切看被拦住时的询问框。
 *
 * **「这次就看」不写回设置**——用户说的是这一次。把一次例外升级成永久开关，
 * 下次在地铁上又开始偷偷拉视频，那才是真正的流量事故。
 */
@Composable
fun MeteredVideoDialog(onConfirmOnce: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("当前是移动网络") },
        text = {
            Text("设置里关闭了「移动网络下允许看视频」。视频流量大约是音频的一百倍以上。")
        },
        confirmButton = { TextButton(onClick = onConfirmOnce) { Text("这次就看") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("继续只听") } },
    )
}
