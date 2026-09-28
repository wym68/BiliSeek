package com.biliseek.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

enum class LibraryTab(val label: String, val icon: ImageVector) {
    PLAYLISTS("播放列表", Icons.AutoMirrored.Outlined.QueueMusic),
    FAVORITES("收藏夹", Icons.Outlined.StarBorder),
    SERIES("合集", Icons.Outlined.VideoLibrary),
}