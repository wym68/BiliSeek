package com.biliseek.app

import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.biliseek.core.network.AuthState
import com.biliseek.core.player.PlaybackRequest
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode

/** M0 用：公开 DASH 测试流，音视频分轨，不接 B 站。 */
const val M0_TEST_STREAM = "https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd"

/** M1 用：硬编码一个 B 站视频，验证取流链路。 */
const val M1_TEST_BVID = "BV1GJ411x7h7"

@Composable
fun M0Screen(
    state: PlaybackState,
    authState: AuthState,
    onPlay: (PlaybackRequest) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPresentationChange: (PresentationMode, SurfaceView?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val surfaceView = remember {
        SurfaceView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("M1 · 接入 B 站", style = MaterialTheme.typography.titleLarge)
        AuthCard(authState)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.presentation == PresentationMode.NONE) {
                Text(
                    if (state.isResolving) "取流中…" else "纯音频模式（无画面）",
                    color = Color.White,
                )
            } else {
                AndroidView(factory = { surfaceView }, modifier = Modifier.fillMaxSize())
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onPlay(PlaybackRequest.Bili(M1_TEST_BVID, title = "B 站测试视频")) }) {
                Text("播放 B 站视频")
            }
            OutlinedButton(
                onClick = { onPlay(PlaybackRequest.Direct(M0_TEST_STREAM, "M0 测试流")) },
            ) {
                Text("M0 测试流")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTogglePlayPause) {
                Text(if (state.isPlaying) "暂停" else "播放")
            }
            Button(
                onClick = { onPresentationChange(PresentationMode.INLINE, surfaceView) },
                enabled = state.presentation == PresentationMode.NONE,
            ) {
                Text("切「看」")
            }
            Button(
                onClick = { onPresentationChange(PresentationMode.NONE, null) },
                enabled = state.presentation != PresentationMode.NONE,
            ) {
                Text("退回「听」")
            }
        }

        state.error?.let { ErrorCard(it) }
        StateCard(state)
        state.lastSwitch?.let { VerdictCard(it) }
    }
}

@Composable
private fun AuthCard(authState: AuthState) {
    val (label, container) = when (authState) {
        is AuthState.Ready -> "✓ 风控凭据就绪" to MaterialTheme.colorScheme.primaryContainer
        is AuthState.Preparing -> "⋯ 正在种植凭据" to MaterialTheme.colorScheme.secondaryContainer
        is AuthState.Failed -> "✗ 凭据失败：${authState.reason}" to MaterialTheme.colorScheme.errorContainer
        AuthState.Idle -> "· 未开始" to MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (authState is AuthState.Ready) {
                val c = authState.credentials
                Mono("buvid3  ${c.buvid3.take(20)}…")
                Mono("ticket  ${if (c.ticket.isBlank()) "无" else c.ticket.take(20) + "…"}")
                Mono("wbi     ${c.wbiImgKey.take(12)}… / ${c.wbiSubKey.take(12)}…")
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("错误", style = MaterialTheme.typography.titleSmall)
            Mono(message)
        }
    }
}

@Composable
private fun StateCard(state: PlaybackState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("当前状态", style = MaterialTheme.typography.titleSmall)
            Mono("播放态     ${state.mode}")
            Mono("呈现态     ${state.presentation}")
            Mono("播放中     ${state.isPlaying}    缓冲中 ${state.isBuffering}")
            Mono("进度       ${state.positionMs / 1000}s / ${state.durationMs / 1000}s")
            Mono("视频轨选中 ${state.videoTrackSelected}")
        }
    }
}

/** 把《验收清单》M0 B-3 的判据直接做进 UI，不靠人工翻日志。 */
@Composable
private fun VerdictCard(d: com.biliseek.core.player.SwitchDiagnostics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (d.seamless) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (d.seamless) "✓ 切换无缝：${d.label}" else "✗ 切换不无缝：${d.label}",
                style = MaterialTheme.typography.titleSmall,
            )
            Mono("buffering 次数      ${d.bufferingEvents}   (须为 0)")
            Mono("seek 不连续次数     ${d.seekDiscontinuities}   (须为 0)")
            Mono("位置 ${d.positionBeforeMs}ms → ${d.positionAfterMs}ms")
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}
