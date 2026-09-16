package com.biliseek.app.data

import com.biliseek.core.network.dto.FollowingUp
import java.time.Instant
import java.time.ZoneId

/**
 * 「最常访问」UP 主的计数与排名策略。
 *
 * 全部是无副作用纯函数，便于单测；DAO 只负责存取（见 `UpVisitDao`）。
 */
object UpVisitPolicy {

    /**
     * 计一次访问：同日去重 —— [prev] 的 lastVisitMs 与 [nowMs] 在同一本地自然日，
     * 只刷新 lastVisitMs 不 +1，避免连点刷榜。
     */
    fun nextVisit(prev: UpVisitEntity?, mid: Long, nowMs: Long): UpVisitEntity {
        val sameDay = prev != null && isSameLocalDay(prev.lastVisitMs, nowMs)
        return UpVisitEntity(
            mid = mid,
            visitCount = (prev?.visitCount ?: 0L) + if (sameDay) 0L else 1L,
            lastVisitMs = nowMs,
        )
    }

    /**
     * 合并关注列表与访问计数，产出「最常访问」栏的展示顺序：
     * 1. 有访问记录的关注 UP：visitCount 降序，同次数按 lastVisitMs 降序（最近访问优先）；
     * 2. 未访问过的：保持关注列表原顺序（B 站默认最近关注在前）补齐；
     * 3. 可选 [limit] 截断；默认 [Int.MAX_VALUE]（不截，调用方按需 `take()`）。
     *
     * 昵称/头像始终来自关注列表；不在关注列表里的访问记录不参与展示。
     */
    fun mergeUpChips(
        followings: List<FollowingUp>,
        visits: List<UpVisitEntity>,
        limit: Int = Int.MAX_VALUE,
    ): List<FollowingUp> {
        if (visits.isEmpty()) return followings.take(limit)
        val byMid = visits.associateBy { it.mid }
        val visited = followings.filter { byMid.containsKey(it.mid) }
            .sortedWith(
                compareByDescending<FollowingUp> { byMid.getValue(it.mid).visitCount }
                    .thenByDescending { byMid.getValue(it.mid).lastVisitMs },
            )
        val rest = followings.filterNot { byMid.containsKey(it.mid) }
        return (visited + rest).take(limit)
    }

    /** 两个时间戳是否同一本地自然日（按设备时区，与播放热力图同一套口径）。 */
    private fun isSameLocalDay(aMs: Long, bMs: Long): Boolean {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(aMs).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(bMs).atZone(zone).toLocalDate()
    }
}
