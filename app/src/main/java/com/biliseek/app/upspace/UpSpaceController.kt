package com.biliseek.app.upspace

import com.biliseek.app.UpSpaceUiState
import com.biliseek.app.UpSpaceView
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.DynamicEntry
import com.biliseek.core.network.dto.RelationStatus
import com.biliseek.core.network.dto.SearchUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UP 空间页：UP 信息卡、关注状态与关注/取关、投稿 / 动态双视图及各自分页。
 *
 * 从 AppViewModel 拆出，由 `AppViewModel.upSpace` 持有；生命周期挂在宿主的 [scope] 上。
 * 与宿主的耦合全在构造参数里：[recordUpVisit]（最常访问计数）、[onExitSelection]（退出投稿复选，
 * 复选状态归宿主）、[onFollowChanged]（关注变化后刷新动态页 UP 栏）。
 * 点投稿播放、投稿复选、动态卡片互动都不在这里；动态卡片乐观更新经 [patchDynamicEntry] 回写本页列表。
 */
class UpSpaceController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val session: StateFlow<BiliSession>,
    private val emitMessage: (String) -> Unit,
    private val recordUpVisit: (mid: Long) -> Unit,
    private val onExitSelection: () -> Unit,
    private val onFollowChanged: () -> Unit,
) {

    private val _upSpace = MutableStateFlow(UpSpaceUiState())
    val state: StateFlow<UpSpaceUiState> = _upSpace.asStateFlow()

    /** 点 UP 搜索结果 → 打开空间页（默认投稿视图）。 */
    fun openUpSpace(user: SearchUser) {
        _upSpace.value = UpSpaceUiState(up = user)
        loadUpPosts(1)
        loadFollowingState(user.mid)
        // 如果头像或粉丝数为空，从 API 获取完整信息
        if (user.upic.isBlank() || user.fans == 0L) {
            loadUserInfo(user.mid)
        }
        // 计一次访问（最常访问排序用）。
        recordUpVisit(user.mid)
    }

    /** 获取用户完整信息（头像、粉丝数）。 */
    private fun loadUserInfo(mid: Long) = scope.launch {
        repository.userCard(mid)
            .onSuccess { fullUser ->
                _upSpace.value = _upSpace.value.copy(up = fullUser)
            }
            .onFailure { err ->
                android.util.Log.e("BiliSeekUser", "loadUserInfo failed: ${err.message}")
            }
    }

    fun closeUpSpace() {
        onExitSelection()
        _upSpace.value = UpSpaceUiState()
    }

    /**
     * 进空间页时拉一次关注状态。`relation()` 未登录会 -101（本地未登录不会触发续期），
     * 失败只记日志，关注状态保持未知。
     */
    private fun loadFollowingState(mid: Long) = scope.launch {
        android.util.Log.d("BiliSeekRelation", "loadFollowingState: mid=$mid")
        repository.relation(mid)
            .onSuccess { stat ->
                android.util.Log.d("BiliSeekRelation", "relation ok: stat=$stat, relationStatus=${stat.relationStatus}, following=${stat.relationStatus == RelationStatus.FOLLOWING}")
                val following = stat.relationStatus == RelationStatus.FOLLOWING
                _upSpace.value = _upSpace.value.copy(following = following)
            }
            .onFailure { err ->
                android.util.Log.e("BiliSeekRelation", "relation FAILED: ${err.javaClass.simpleName}: ${err.message}", err)
            }
    }

    /** 关注 / 取关：乐观更新，失败回滚。 */
    fun toggleRelation(mid: Long, follow: Boolean) = scope.launch {
        if (!session.value.isLoggedIn) {
            emitMessage("请先登录后再关注")
            return@launch
        }
        val cur = _upSpace.value
        // 防止双击：true→true 已是最终态，不重复发
        if (cur.followingLoading) return@launch
        // 乐观更新
        _upSpace.value = cur.copy(followingLoading = true, following = follow)
        repository.modifyRelation(mid, follow)
            .onSuccess {
                _upSpace.value = _upSpace.value.copy(followingLoading = false)
                emitMessage(if (follow) "已关注" else "已取消关注")
                // 关注/取关成功后刷新动态页的关注列表
                onFollowChanged()
            }
            .onFailure { err ->
                // 回滚 + 报错提示
                _upSpace.value = _upSpace.value.copy(followingLoading = false, following = !follow)
                emitMessage("关注失败：${err.message ?: "未知错误"}")
            }
    }

    /** 动态列表里就地替换一条（供动态卡片互动的乐观更新 / 回滚使用）。 */
    fun patchDynamicEntry(idStr: String, transform: (DynamicEntry) -> DynamicEntry) {
        val space = _upSpace.value
        val idx = space.dynamic.items.indexOfFirst { it.idStr == idStr }
        if (idx < 0) return
        val newItems = space.dynamic.items.toMutableList().also { it[idx] = transform(it[idx]) }
        _upSpace.value = space.copy(dynamic = space.dynamic.copy(items = newItems))
    }

    /** 投稿 / 动态 视图切换：各视图独立分页状态，互不干扰。 */
    fun setUpSpaceView(view: UpSpaceView) {
        // 切视图退出投稿复选：另一视图的列表与勾选集无关
        onExitSelection()
        val cur = _upSpace.value
        if (cur.view == view) return
        _upSpace.value = cur.copy(view = view)
        if (view == UpSpaceView.DYNAMIC && cur.dynamic.items.isEmpty() && !cur.dynamic.isLoading) {
            loadUpDynamicPage(offset = "")
        }
    }

    fun loadUpPosts(page: Int) {
        val cur = _upSpace.value
        val up = cur.up ?: return
        if (cur.posts.isLoading || cur.posts.isLoadingMore) return
        val loadingMore = page > 1
        if (loadingMore && !cur.posts.hasMore) return
        scope.launch {
            _upSpace.value = cur.copy(
                posts = cur.posts.copy(
                    isLoading = !loadingMore,
                    isLoadingMore = loadingMore,
                    errorMessage = null,
                ),
            )
            repository.spaceArchives(up.mid, page)
                .onSuccess { result ->
                    val base = _upSpace.value.posts
                    _upSpace.value = _upSpace.value.copy(
                        posts = base.copy(
                            items = if (loadingMore) base.items + result.items else result.items,
                            hasMore = result.hasMore,
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = null,
                        ),
                    )
                }
                .onFailure {
                    val base = _upSpace.value.posts
                    _upSpace.value = _upSpace.value.copy(
                        posts = base.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = it.message ?: "加载失败",
                        ),
                    )
                }
        }
    }

    fun loadMoreUpPosts() {
        val cur = _upSpace.value
        if (!cur.posts.hasMore) return
        loadUpPosts(cur.posts.page + 1)
    }

    private fun loadUpDynamicPage(offset: String) {
        val cur = _upSpace.value
        val up = cur.up ?: return
        if (cur.dynamic.isLoading || cur.dynamic.isLoadingMore) return
        val loadingMore = offset.isNotBlank()
        scope.launch {
            _upSpace.value = cur.copy(
                dynamic = cur.dynamic.copy(
                    isLoading = !loadingMore,
                    isLoadingMore = loadingMore,
                    errorMessage = null,
                ),
            )
            repository.dynamicSpace(up.mid, offset)
                .onSuccess { pageData ->
                    val base = _upSpace.value.dynamic
                    _upSpace.value = _upSpace.value.copy(
                        dynamic = base.copy(
                            items = if (loadingMore) base.items + pageData.entries else pageData.entries,
                            hasMore = pageData.hasMore,
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = null,
                        ),
                        dynamicOffset = pageData.offset,
                    )
                }
                .onFailure {
                    val base = _upSpace.value.dynamic
                    _upSpace.value = _upSpace.value.copy(
                        dynamic = base.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = it.message ?: "加载失败",
                        ),
                    )
                }
        }
    }

    fun loadMoreUpDynamic() {
        val cur = _upSpace.value
        if (!cur.dynamic.hasMore) return
        loadUpDynamicPage(cur.dynamicOffset)
    }
}
