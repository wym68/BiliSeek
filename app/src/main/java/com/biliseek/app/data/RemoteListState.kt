package com.biliseek.app.data

/**
 * 远程列表的标识（《架构设计篇》§6.3「一切皆歌单」）。
 *
 * 本地歌单不在此列——它走 Room 的 Flow（增删改自动刷新）。
 * 这里只收「远程分页拉取」的列表来源。**新增一种列表 = 新增一个数据源实现，
 * 不改 UI**：UI 只依赖 [RemoteListState] 这个与来源无关的状态。
 */
sealed interface TrackListId {
    /** 收藏夹（M11-B）：目录快照落库，内容分页拉取。 */
    data class Favorite(val mediaId: Long) : TrackListId

    /** UP 主投稿（M11-D）：空间投稿分页拉取。 */
    data class Uploader(val mid: Long) : TrackListId

    /** 稍后再看（M12）：远程列表，一次性拉取（无分页）。 */
    data object WatchLater : TrackListId
}

/**
 * 远程列表的分页状态（§6.3 的 `TrackListUiState` 收敛为最小形状）。
 *
 * 加载机制**不强求统一**：收藏夹内容、UP 投稿、动态流各自实现加载，
 * 汇聚到同一个状态形状即可。UI 按 [errorMessage] 显式兜底——
 * 远程拉取失败要看得见、可重试，不能静默变空。
 *
 * @param T 列表条目。收藏夹条目与 UP 投稿条目都收敛到可播放的 Track 形状。
 */
data class RemoteListState<T>(
    val id: TrackListId? = null,
    val title: String = "",
    val items: List<T> = emptyList(),
    /** 已加载的页数（第 1 页起）。切换来源 / 刷新时归 1。 */
    val page: Int = 0,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
) {
    companion object {
        fun <T> empty() = RemoteListState<T>()
    }
}
