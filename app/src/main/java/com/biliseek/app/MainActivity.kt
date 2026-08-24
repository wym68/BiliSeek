package com.biliseek.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.biliseek.core.network.BiliNetwork
import com.biliseek.core.player.PlayerConnection
import com.biliseek.designsystem.theme.BiliSeekTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var connection: PlayerConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val network = BiliNetwork.get(applicationContext)
        // 风控 gate 在后台种植凭据，不阻塞 UI —— 界面必须立刻可交互
        network.authGate.start()

        connection = PlayerConnection(applicationContext)
        lifecycleScope.launch { connection.connect() }

        setContent {
            BiliSeekTheme {
                val state by connection.state.collectAsState()
                val authState by network.authGate.state.collectAsState()
                val scope = rememberCoroutineScope()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    M0Screen(
                        state = state,
                        authState = authState,
                        onPlay = { request -> scope.launch { connection.play(request) } },
                        onTogglePlayPause = connection::togglePlayPause,
                        onPresentationChange = { mode, surface ->
                            connection.setPresentation(mode, surface)
                        },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        connection.release()
        super.onDestroy()
    }
}
