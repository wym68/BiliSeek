package com.biliseek.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.LoginState

@Composable
fun LoginPanel(
    loginState: LoginState,
    session: BiliSession,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (session.isLoggedIn) {
                Text("已登录 · UID ${session.dedeUserId}", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = onLogout) { Text("登出") }
                return@Column
            }

            Text("Web 扫码登录", style = MaterialTheme.typography.titleSmall)
            Text(
                "登录非强制——不登录也能搜索和播放，仅收藏夹同步等功能需要账号",
                style = MaterialTheme.typography.bodySmall,
            )

            when (loginState) {
                is LoginState.Idle -> Button(onClick = onStart) { Text("生成二维码") }

                is LoginState.QrReady -> {
                    QrImage(loginState.url)
                    Text("请用 B 站 App 扫码", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onCancel) { Text("取消") }
                }

                is LoginState.Scanned -> {
                    Text("✓ 已扫描，请在手机上确认", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onCancel) { Text("取消") }
                }

                is LoginState.Expired -> {
                    Text("二维码已失效", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onStart) { Text("重新生成") }
                }

                is LoginState.Failed -> {
                    Text("登录失败：${loginState.reason}", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onStart) { Text("重试") }
                }

                is LoginState.Success -> Text("✓ 登录成功", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun QrImage(url: String) {
    val bitmap = remember(url) { encodeQrCode(url) }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = "登录二维码", modifier = Modifier.size(200.dp))
    } else {
        Text("二维码渲染失败", style = MaterialTheme.typography.bodySmall)
    }
}
