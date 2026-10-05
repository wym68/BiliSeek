package com.biliseek.app.remote

import com.biliseek.core.network.WriteError
import com.biliseek.core.network.WriteException
import com.biliseek.core.network.bvToAv

// 远程列表（稍后再看 / 收藏夹内容）里移除条目要用的纯函数
// （设计稿：docs/superpowers/specs/2026-10-04-list-manage-and-swipe-remove-design.md），
// 以及「加入列表 → 稍后再看」的提示（docs/superpowers/specs/2026-10-04-add-to-watch-later-design.md）。

/**
 * 收藏夹往下翻时该取第几页（从 1 起）。
 *
 * 删过条目后服务器上的序号整体前移，按「页码 + 1」翻页会漏掉前移进上一页的那几条；
 * 改按「已加载、且服务器上还在的条数」（列表里的 + 正等着提交移除的）算，重叠的部分由 [appendUnseen] 去掉。
 */
fun favoriteFetchPage(loadedOnServer: Int, pageSize: Int): Int = loadedOnServer / pageSize + 1

/** 把新取到的一页接在后面：已在列表里的、正等着提交移除的（[excludedKeys]）都不再加。 */
fun <T> appendUnseen(
    existing: List<T>,
    incoming: List<T>,
    excludedKeys: Set<String>,
    key: (T) -> String,
): List<T> {
    val seen = existing.mapTo(HashSet(), key) + excludedKeys
    return existing + incoming.filter { key(it) !in seen }
}

/**
 * 撤销、或提交失败时把条目放回原位。原位超出当前长度就放到末尾；
 * 已经在列表里（比如撤销前刚刷新过）就不重复放。
 */
fun <T> restoreAt(items: List<T>, item: T, index: Int, key: (T) -> String): List<T> {
    if (items.any { key(it) == key(item) }) return items
    return items.toMutableList().apply { add(index.coerceIn(0, size), item) }
}

/**
 * 移除用的 aid：有就用，没有（≤ 0）就用 bvid 本地换算，都不行返回 null（调用方按失败处理）。
 * 不能把 null 交给 `delToView`——那是「清空所有已看过的」。
 */
fun resolveAid(aid: Long, bvid: String): Long? =
    aid.takeIf { it > 0 } ?: runCatching { bvToAv(bvid) }.getOrNull()

/** 「加入列表 → 稍后再看」的结果提示。[firstError] 是第一条失败的原因。 */
fun watchLaterAddMessage(ok: Int, failed: Int, firstError: Throwable?): String = when {
    failed == 0 -> "已加入 $ok 项到稍后再看"
    ok == 0 -> "加入稍后再看失败：${watchLaterFailureReason(firstError)}"
    else -> "已加入 $ok 项，$failed 项失败：${watchLaterFailureReason(firstError)}"
}

/** 失败原因说人话：列表满了（90001）、视频没了（90003）；其他照常用异常自带的说明。 */
fun watchLaterFailureReason(error: Throwable?): String =
    when (((error as? WriteException)?.error as? WriteError.Unknown)?.code) {
        90001 -> "稍后再看已满"
        90003 -> "视频已失效"
        else -> error?.message?.takeIf { it.isNotBlank() } ?: "未知错误"
    }
