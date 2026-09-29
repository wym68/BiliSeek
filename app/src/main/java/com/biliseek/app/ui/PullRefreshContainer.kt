package com.biliseek.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 账号页通用的下拉刷新：顶部转圈 + 刷新结束后的一句提示（「已刷新」/「刷新失败：…」）。
 *
 * [isRefreshing] 就是顶部转圈显不显示。列表已有内容时它应随加载转起来——早期写成
 * `isLoading && items.isEmpty()`，恰好反了：有内容时一拉就弹回去，看不出刷没刷；
 * 列表还空着时由页面自己的加载占位负责，这里不必再转一个。
 * 提示只在用户亲手下拉之后出现，判定见 [PullRefreshTracker]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefreshContainer(
    isRefreshing: Boolean,
    errorMessage: String?,
    successText: String,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var tracker by remember { mutableStateOf(PullRefreshTracker()) }
    var toast by remember { mutableStateOf<RefreshToast?>(null) }

    LaunchedEffect(isRefreshing) {
        val (next, result) = tracker.onRefreshingChanged(isRefreshing, errorMessage, successText)
        tracker = next
        if (result != null) toast = result
    }

    Box(modifier) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                tracker = tracker.onPull(isRefreshing)
                onRefresh()
            },
            state = rememberPullToRefreshState(),
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
        RefreshStateSnackbar(
            message = toast?.message,
            isError = toast?.isError == true,
            onDismiss = { toast = null },
        )
    }
}
