package com.biliseek.app

import android.os.Bundle
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.biliseek.app.ui.AppShell
import com.biliseek.app.ui.HomeEmptyState
import com.biliseek.app.ui.LibraryScreen
import com.biliseek.app.ui.PlayerExpansion
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
                val playlists by viewModel.playlists.collectAsState()
                val openedPlaylist by viewModel.openedPlaylist.collectAsState()
                val openedTracks by viewModel.openedTracks.collectAsState()
                val searchHistory by viewModel.searchHistory.collectAsState()
                val recentPlays by viewModel.recentPlays.collectAsState()
                val heatmap by viewModel.heatmap.collectAsState()

                var expansion by remember { mutableStateOf(PlayerExpansion.COLLAPSED) }

                val context = LocalContext.current
                val surfaceView = remember {
                    SurfaceView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                }

                // 起播即展开播放器，让用户看到当前在放什么
                LaunchedEffect(Unit) {
                    viewModel.playbackStarted.collect { expansion = PlayerExpansion.EXPANDED }
                }

                AppShell(
                    playback = playback,
                    expansion = expansion,
                    surfaceView = surfaceView,
                    onExpansionChange = { expansion = it },
                    onTogglePlayPause = viewModel.connection::togglePlayPause,
                    onPresentationChange = { mode, sv ->
                        viewModel.connection.setPresentation(mode, sv)
                    },
                    searchContent = {
                        // 首页空状态：无搜索、无结果时展示用户自己的行为数据
                        val showEmptyState = search.items.isEmpty() &&
                            search.keyword.isBlank() &&
                            search.error == null &&
                            search.pages.isEmpty()
                        SearchScreen(
                            state = search,
                            onKeywordChange = viewModel::onKeywordChange,
                            onSubmit = viewModel::submitSearch,
                            onResultClick = viewModel::onResultClick,
                            onResultLongClick = { viewModel.addToPlaylist(it) },
                            onLoadMore = viewModel::loadMore,
                            onPageClick = { viewModel.playPage(search.pagesOfBvid, it) },
                            onDismissPages = viewModel::dismissPages,
                            emptyContent = {
                                if (showEmptyState) {
                                    HomeEmptyState(
                                        searchHistory = searchHistory,
                                        recentPlays = recentPlays,
                                        heatmap = heatmap,
                                        onKeywordClick = viewModel::searchKeyword,
                                        onDeleteKeyword = viewModel::deleteSearchKeyword,
                                        onClearHistory = viewModel::clearSearchHistory,
                                        onRecentClick = viewModel::playHistoryEntry,
                                    )
                                }
                            },
                        )
                    },
                    libraryContent = {
                        LibraryScreen(
                            playlists = playlists,
                            openedPlaylist = openedPlaylist,
                            tracks = openedTracks,
                            onCreate = viewModel::createPlaylist,
                            onOpen = viewModel::openPlaylist,
                            onClose = viewModel::closePlaylist,
                            onDelete = viewModel::deletePlaylist,
                            onRename = viewModel::renamePlaylist,
                            onTrackClick = viewModel::playTrack,
                            onTrackRemove = viewModel::removeFromPlaylist,
                        )
                    },
                    settingsContent = {
                        LoginPanel(
                            loginState = loginState,
                            session = session,
                            onStart = viewModel::startLogin,
                            onCancel = viewModel::cancelLogin,
                            onLogout = viewModel::logout,
                            modifier = Modifier.padding(16.dp),
                        )
                    },
                )
            }
        }
    }
}
