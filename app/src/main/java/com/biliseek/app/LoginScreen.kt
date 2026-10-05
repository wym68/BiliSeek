package com.biliseek.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.LoginState

/**
 * 哔哩哔哩官方 App 的 Android 包名。
 *
 * Play 版与官网版在不同渠道可能换包名（如 `com.bilibili.app.in`），
 * 但国内主渠道是 `tv.danmaku.bili`，且 Manifest 已声明 `<queries>` 给该包。
 */
private const val BILIBILI_PACKAGE = "tv.danmaku.bili"

@Composable
fun LoginPanel(
    loginState: LoginState,
    session: BiliSession,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 打不开 B 站 App 时的提示：走 App 内提示（在迷你条上方）；没提供时（预览、测试）退回系统 Toast
    val showMessage = com.biliseek.app.ui.components.LocalShowMessage.current
        ?: { msg: String -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
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
                    // 一键唤起官方 App：把同一个 QR URL 直接转交给 B 站 App 的 WebView，
                    // 用户在 B 站 App 里点确认即完成登录。我们继续轮询，2s 内拿到结果。
                    Button(onClick = { openInBiliApp(context, loginState.url, showMessage) }) {
                        Text("用 B 站 App 一键登录")
                    }
                    OutlinedButton(onClick = onCancel) { Text("取消") }
                }

                is LoginState.Scanned -> {
                    StatusLine(Icons.Filled.CheckCircle, "已扫描，请在手机上确认")
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

                is LoginState.Success -> StatusLine(Icons.Filled.CheckCircle, "登录成功")
            }
        }
    }
}

/** 图标 + 文案的状态行（替代「✓ xxx」字符拼法）。 */
@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(com.biliseek.designsystem.theme.BsSpacing.xs),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = androidx.compose.ui.Modifier.size(com.biliseek.designsystem.theme.BsSize.iconSm),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
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

/**
 * 把扫码登录 URL 交给哔哩哔哩官方 App。
 *
 * B 站生成的 QR URL（如 `https://account.bilibili.com/h5/account-h5/auth/scan-web?qrcode_key=...`）
 * 由官方 App 的 `IntentHandlerActivity` 处理，会打开 App 内 WebView 弹出登录确认页——
 * 用户已在 B 站 App 登录的情况下直接点确认即完成授权；不在则要先登录 B 站 App。
 *
 * Manifest 已声明 `<queries>` 允许查询该包名；setPackage 同时避免浏览器抢走选择框。
 */
private fun openInBiliApp(context: Context, url: String, showMessage: (String) -> Unit) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        setPackage(BILIBILI_PACKAGE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        showMessage("未找到哔哩哔哩 App，请先安装")
    } catch (_: SecurityException) {
        showMessage("无法打开哔哩哔哩 App，请手动扫码")
    }
}
