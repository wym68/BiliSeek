package com.biliseek.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import com.biliseek.core.network.dto.SearchVideo
import com.biliseek.core.network.stripHighlight

/**
 * 视频条目三点菜单的动作集合，与搜索结果的三点菜单一致
 * （设计稿：docs/superpowers/specs/2026-10-02-video-menu-on-lists-design.md）。
 *
 * 榜单、收藏夹、稍后再看、合集共用一份，由宿主逐层透传（照 [DynamicVideoActions] 的写法）；默认全是空操作。
 */
data class VideoMenuActions(
    val addToPlaylist: (SearchVideo) -> Unit = {},
    /** 追加到播放队列末尾。 */
    val appendToQueue: (SearchVideo) -> Unit = {},
    /** 插到当前曲目之后。 */
    val playNext: (SearchVideo) -> Unit = {},
    val viewUp: (SearchVideo) -> Unit = {},
    val copyLink: (SearchVideo) -> Unit = {},
    val download: (SearchVideo) -> Unit = {},
    val summarize: (SearchVideo) -> Unit = {},
)

/** 行右侧的三点按钮：点行本身仍是播放，按钮只负责打开菜单。 */
@Composable
fun VideoMenuButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
    }
}

/**
 * 三点菜单：标题是视频标题，项目见 [videoMenuActions]；点一项先收起菜单再执行动作（与搜索页一致）。
 * [onRemove] 非空时末尾多一项「移除」（已下载页用 [removeLabel] 叫「删除」）。
 */
@Composable
fun VideoMenuSheet(
    video: SearchVideo,
    actions: VideoMenuActions,
    onRemove: (() -> Unit)? = null,
    /** 「移除」那一项的文字；已下载页会真的删文件，传「删除」。 */
    removeLabel: String = VideoMenuAction.Remove.label,
    /** 「已下载」页的条目传 false：已经下好了，不再有「下载」。 */
    downloadable: Boolean = true,
    onDismiss: () -> Unit,
) {
    ActionListSheet(
        title = stripHighlight(video.title),
        items = videoMenuActions(video.mid, removable = onRemove != null, downloadable = downloadable).map { action ->
            (if (action == VideoMenuAction.Remove) removeLabel else action.label) to {
                onDismiss()
                when (action) {
                    VideoMenuAction.AddToPlaylist -> actions.addToPlaylist(video)
                    VideoMenuAction.AppendToQueue -> actions.appendToQueue(video)
                    VideoMenuAction.PlayNext -> actions.playNext(video)
                    VideoMenuAction.ViewUp -> actions.viewUp(video)
                    VideoMenuAction.CopyLink -> actions.copyLink(video)
                    VideoMenuAction.Download -> actions.download(video)
                    VideoMenuAction.Summarize -> actions.summarize(video)
                    VideoMenuAction.Remove -> onRemove?.invoke()
                }
            }
        },
        onDismiss = onDismiss,
    )
}
