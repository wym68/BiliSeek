package com.biliseek.app

import android.os.Bundle
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.biliseek.core.player.PlaybackState
import com.biliseek.core.player.PresentationMode
import com.biliseek.designsystem.theme.BiliSeekTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            BiliSeekTheme {
                val playback by viewModel.connection.state.collectAsState()
                val search by viewModel.search.collectAsState()
                val loginState by viewModel.loginState.collectAsState()
                val session by viewModel.session.collectAsState()
                var tab by remember { mutableIntStateOf(0) }

                // 只有真正起播才跳到播放页；点到多 P 视频时留在列表展开分 P
                LaunchedEffect(Unit) {
                    viewModel.playbackStarted.collect { tab = 1 }
                }

                val context = LocalContext.current
                val surfaceView = remember {
                    SurfaceView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(Modifier.padding(innerPadding).fillMaxSize()) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(tab == 0, { tab = 0 }, text = { Text("搜索") })
                            Tab(tab == 1, { tab = 1 }, text = { Text("播放") })
                            Tab(tab == 2, { tab = 2 }, text = { Text("账号") })
                        }
                        when (tab) {
                            0 -> SearchScreen(
                                state = search,
                                onKeywordChange = viewModel::onKeywordChange,
                                // 提交搜索留在本页看结果；只有直接播放（BV 号）才切到播放页
                                onSubmit = viewModel::submitSearch,
                                onResultClick = viewModel::onResultClick,
                                onLoadMore = viewModel::loadMore,
                                onPageClick = { viewModel.playPage(search.pagesOfBvid, it) },
                                onDismissPages = viewModel::dismissPages,
                            )
                            1 -> PlayerPanel(
                                state = playback,
                                surfaceView = surfaceView,
                                onTogglePlayPause = viewModel.connection::togglePlayPause,
                                onPresentationChange = { mode, sv ->
                                    viewModel.connection.setPresentation(mode, sv)
                                },
                            )
                            2 -> LoginPanel(
                                loginState = loginState,
                                session = session,
                                onStart = viewModel::startLogin,
                                onCancel = viewModel::cancelLogin,
                                onLogout = viewModel::logout,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerPanel(
    state: PlaybackState,
    surfaceView: SurfaceView,
    onTogglePlayPause: () -> Unit,
    onPresentationChange: (PresentationMode, SurfaceView?) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
            OutlinedButton(onClick = onTogglePlayPause) {
                Text(if (state.isPlaying) "暂停" else "播放")
            }
            Button(
                onClick = { onPresentationChange(PresentationMode.INLINE, surfaceView) },
                enabled = state.presentation == PresentationMode.NONE,
            ) { Text("切「看」") }
            Button(
                onClick = { onPresentationChange(PresentationMode.NONE, null) },
                enabled = state.presentation != PresentationMode.NONE,
            ) { Text("退回「听」") }
        }

        state.error?.let {
            Text("错误：$it", style = MaterialTheme.typography.bodySmall, color = Color.Red)
        }

        Mono("播放态     ${state.mode}")
        Mono("呈现态     ${state.presentation}")
        Mono("播放中     ${state.isPlaying}    缓冲中 ${state.isBuffering}")
        Mono("进度       ${state.positionMs / 1000}s / ${state.durationMs / 1000}s")
        Mono("视频轨选中 ${state.videoTrackSelected}")

        state.lastSwitch?.let { d ->
            Mono(if (d.seamless) "✓ 切换无缝：${d.label}" else "✗ 切换不无缝：${d.label}")
            Mono("buffering=${d.bufferingEvents}  seek=${d.seekDiscontinuities}")
            Mono("位置 ${d.positionBeforeMs} → ${d.positionAfterMs}ms")
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}
