package com.biliseek.core.network

import com.biliseek.core.network.dto.SearchVideo

/**
 * 合并分页结果，按 bvid 去重。
 *
 * **B 站的分页会返回重复条目**——实测搜同一关键词翻 8 页，累计出现 13 次重复，
 * 甚至同一页内部就有重复（第 1 页 20 条里只有 17 条是新的）。
 * 排序在两次请求之间会漂移，边界上的条目因此被重复吐出。
 *
 * 不去重的后果不是「列表里多几条」而是**直接崩溃**：
 * `LazyColumn` 的 `key` 要求全局唯一，撞到重复 key 会抛
 * `IllegalArgumentException: Key "BVxxx" was already used`。
 *
 * @param existing 已加载的条目，保持原有顺序
 * @param incoming 新一页的条目
 */
fun mergeSearchResults(
    existing: List<SearchVideo>,
    incoming: List<SearchVideo>,
): List<SearchVideo> {
    if (existing.isEmpty()) return incoming.distinctBy { it.bvid }
    val seen = existing.mapTo(HashSet()) { it.bvid }
    val added = incoming.filter { it.bvid.isNotBlank() && seen.add(it.bvid) }
    return if (added.isEmpty()) existing else existing + added
}
