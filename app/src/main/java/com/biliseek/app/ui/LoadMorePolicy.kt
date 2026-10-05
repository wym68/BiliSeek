package com.biliseek.app.ui

/**
 * 列表滚到底时要不要自动加载下一页：还有更多、没在加载、上一次没失败。
 *
 * 失败后不自动再来，等用户点「重试」：底部的自动加载在「加载中」时退出组合，失败后重新进入组合就会立刻再发一次，
 * 不看失败就成了对 B 站的自动重试循环（CLAUDE.md 禁止在 B 站请求外面加重试；撞上 -352 冷却还会空转）。
 */
fun shouldAutoLoadMore(hasMore: Boolean, isLoadingMore: Boolean, errorMessage: String?): Boolean =
    hasMore && !isLoadingMore && errorMessage == null
