package com.biliseek.core.player.internal

import com.biliseek.core.player.PlayMode

/**
 * 队列推进 —— 纯函数，可单测。
 *
 * 播完一首后该放哪一首，由播放模式唯一决定；返回 `null` 表示停止。
 */
internal fun resolveNextIndex(
    current: Int,
    size: Int,
    mode: PlayMode,
    random: (Int) -> Int = { (0 until it).random() },
): Int? {
    if (size <= 0) return null
    return when (mode) {
        PlayMode.REPEAT_ONE -> current
        PlayMode.SHUFFLE -> if (size == 1) current else generateSequence { random(size) }
            .first { it != current }
        PlayMode.REPEAT_ALL -> (current + 1) % size
        PlayMode.SEQUENTIAL -> (current + 1).takeIf { it < size }
    }
}

/** 手动「上一首」。与自动推进不同：单曲循环下手动上一首仍应换歌。 */
internal fun resolvePreviousIndex(current: Int, size: Int, mode: PlayMode): Int? {
    if (size <= 0) return null
    return when (mode) {
        PlayMode.SEQUENTIAL -> (current - 1).takeIf { it >= 0 }
        else -> (current - 1 + size) % size
    }
}

/**
 * 「下一首播放」的插入位置。
 *
 * 队列为空或还没起播（[current] < 0）时追加到末尾；否则插在当前项之后。
 */
internal fun resolveEnqueueNextIndex(current: Int, size: Int): Int =
    if (current < 0) size else (current + 1).coerceAtMost(size)

/**
 * 在 [insertAt] 处插入一项后，当前下标应变成什么。
 *
 * 插入点在当前项**之前或同位**时，当前项被顶下去了一格；否则不动。
 * 忘了修正这个下标，「下一首播放」就会表现为「插完之后正在播的歌变了」。
 */
internal fun resolveIndexAfterInsert(current: Int, insertAt: Int): Int =
    if (current >= 0 && insertAt <= current) current + 1 else current

/** 手动「下一首」。单曲循环下手动下一首也应换歌，不能原地打转。 */
internal fun resolveManualNextIndex(current: Int, size: Int, mode: PlayMode): Int? {
    if (size <= 0) return null
    return when (mode) {
        PlayMode.SEQUENTIAL -> (current + 1).takeIf { it < size }
        else -> (current + 1) % size
    }
}

/**
 * 清空队列后的尺寸与下标（Ch5 §5.1.3 / §5.8）。
 *
 * **保留当前播放、清其余**；[currentIndex] 越界或队列为空时为 no-op（返回 size, queueIndex=-1）。
 * queueIndex 归 0：清完后当前项是队首，下一次手动"下一首"按 [resolveManualNextIndex]
 * 自然回 1（基于 size=1），避免回到原下标导致再次寻址已删除项。
 */
internal fun resolveClearedQueue(currentIndex: Int, size: Int): Pair<Int, Int> {
    if (size <= 0) return 0 to -1
    if (currentIndex !in 0 until size) return 0 to -1
    return 1 to 0
}

// ── 两层队列（docs/10-播放队列工单.md）──────────────────────
//
// 队列条目分两种：用户显式加入的（「加入队列」「下一首播放」）是**固定**的，点击播放永远不删；
// 点击带进来的（单个视频、多 P 的全部分 P、整张列表）是**临时**的，下一次点击整块替换。
// 点击整块插入临时条目，所以刚点完时它们是连续的一段；之后「下一首播放」插在当前项后面，
// 当前项是临时条目时固定条目就会夹在临时块中间（分 P 之间也一样），当前项本身是固定条目时
// 临时块更是被它隔开。所以下面的函数不假设临时条目连续，只看每个条目自己的固定 / 临时标记。

/**
 * 一次点击播放对队列的改写方案。
 *
 * @property kept 保留下来的原下标（全部是固定条目），按原顺序
 * @property insertAt 新临时块在新队列里的起始位置
 * @property newIndex 新队列的当前下标
 * @property removedTemporary 被替换掉的临时条目数（决定要不要给「撤销」）
 */
internal data class TapPlan(
    val kept: List<Int>,
    val insertAt: Int,
    val newIndex: Int,
    val removedTemporary: Int,
)

/**
 * 点击播放：删掉全部临时条目，固定条目按原顺序保留，新内容作为一整块临时条目
 * 插在原当前条目的位置——当前条目之前的固定条目之后、还没播的固定条目之前。
 *
 * 于是「手动加的排在整个列表之后」：列表播完才轮到它们。
 * 还没起播（[current] 越界）时插在最前，已排着的固定条目等新内容播完再放。
 *
 * @param pinned 每个条目是否固定
 * @param newCount 新临时块的条目数（≥1）
 * @param startOffset 从新块的第几项开始播
 */
internal fun resolveQueueOnTap(
    pinned: List<Boolean>,
    current: Int,
    newCount: Int,
    startOffset: Int,
): TapPlan {
    val kept = pinned.indices.filter { pinned[it] }
    val insertAt = if (current in pinned.indices) kept.count { it <= current } else 0
    val offset = startOffset.coerceIn(0, (newCount - 1).coerceAtLeast(0))
    return TapPlan(
        kept = kept,
        insertAt = insertAt,
        newIndex = insertAt + offset,
        removedTemporary = pinned.size - kept.size,
    )
}

/** 「把合集换进列表、不重新起播」的改写计划（见 [resolveQueueOnAdopt]）。 */
internal data class AdoptPlan(
    /** 保留的固定条目（原下标、原顺序；不含当前视频那一段）。 */
    val kept: List<Int>,
    /** 新块插在 [kept] 的第几项之前。 */
    val insertAt: Int,
    /** 当前视频那一段在原队列里的范围 [runStart, runEnd)：当前条目前后连着的同一视频条目（含已展开的分 P，和分 P 之间夹着的固定条目）。 */
    val runStart: Int,
    val runEnd: Int,
    /** 改写后的当前下标。 */
    val newIndex: Int,
)

/**
 * 合集弹层里点正在播的那一集：临时条目整块换成合集，当前视频（连同已展开的分 P）原样落在合集里它的位置，
 * 不重新起播（docs/10）。
 *
 * - 当前视频那一段 = 当前条目前后连着、[keys] 相同的条目。「下一首播放」会把固定条目插在分 P 中间
 *   （`[A1, A2(当前), X(固定), A3]`），所以一串连着的固定条目只要另一头紧挨着同一视频的条目，
 *   就连同它们一起算进这一段、原位不动（仍是固定的，夹在分 P 之间）；
 *   隔着别的临时条目就不再延伸；
 * - 不在这一段里的固定条目原样保留，新块插在这一段原位置之前的固定条目之后；
 * - 新块 = 合集前 [beforeCount] 项 + 这一段 + 合集其余项，所以新下标 = insertAt + beforeCount + 当前条目在段内的偏移。
 *
 * [current] 越界返回 null。
 */
internal fun resolveQueueOnAdopt(
    pinned: List<Boolean>,
    keys: List<String>,
    current: Int,
    beforeCount: Int,
): AdoptPlan? {
    if (current !in keys.indices) return null
    val key = keys[current]
    // 从 from 出发朝 step 方向走：先越过一串键不同的固定条目，落点是同一视频的条目才算连着，返回落点；否则 null
    fun reach(from: Int, step: Int): Int? {
        var i = from
        while (i in keys.indices && pinned[i] && keys[i] != key) i += step
        return i.takeIf { it in keys.indices && keys[it] == key }
    }
    var start = current
    while (true) {
        start = reach(start - 1, -1) ?: break
    }
    var end = current + 1
    while (true) {
        end = (reach(end, 1) ?: break) + 1
    }
    val kept = pinned.indices.filter { pinned[it] && it !in start until end }
    val insertAt = kept.count { it < start }
    return AdoptPlan(
        kept = kept,
        insertAt = insertAt,
        runStart = start,
        runEnd = end,
        newIndex = insertAt + beforeCount + (current - start),
    )
}

/** 临时块的末尾（开区间）；队列里没有临时条目时为 null。续页取回的条目就插在这里。 */
internal fun resolveTemporaryBlockEnd(pinned: List<Boolean>): Int? =
    pinned.indexOfLast { !it }.takeIf { it >= 0 }?.plus(1)

/**
 * 要不要给列表来源续一页：正在播临时块里的条目，且离块尾不到 [threshold] 项。
 *
 * 只在播到块尾附近才请求，跟用户往下滚列表一样一次一页——
 * 不在点击时一口气拉完整个收藏夹 / 合集，免得连发请求撞上风控（-352）。
 */
internal fun shouldLoadMoreContext(
    pinned: List<Boolean>,
    current: Int,
    threshold: Int = CONTEXT_PREFETCH_THRESHOLD,
): Boolean {
    if (current !in pinned.indices || pinned[current]) return false
    val end = resolveTemporaryBlockEnd(pinned) ?: return false
    return end - current <= threshold
}

internal const val CONTEXT_PREFETCH_THRESHOLD = 2

/**
 * 点击替换时真正「丢掉」的临时条目数：被替换掉的条目里，新块中找不到同一视频的有几条。
 *
 * 按视频（bvid）比对而不是按整条请求：在同一张列表里换一条播放时，旧块可能已经展开过分 P、
 * 续过页，逐条比对会误判成「换掉了一整张列表」，弹出一个毫无必要的撤销提示。
 *
 * @param removed 被替换掉的临时条目的视频键
 * @param incoming 新临时块的视频键
 */
internal fun countLostOnTap(removed: List<String>, incoming: List<String>): Int {
    val kept = incoming.toSet()
    return removed.count { it !in kept }
}

/**
 * 点击真正丢掉的临时条目够多才给「撤销」（见 [countLostOnTap]）。
 *
 * 只替换掉上一次点的那一个视频不值得打扰；在同一张列表里换一条播放什么也没丢，也不提示。
 * 从一整张列表跳到别处播放才可能是误触。
 */
internal fun shouldOfferUndo(lost: Int): Boolean = lost >= 2

/**
 * 删除单条后的当前下标。
 *
 * 删的是正在播的那一项时返回 null——拒绝删除（面板里那一行本来就滑不动，这里再兜一次底）。
 * 删的在当前项之前，当前项往前挪一格；之后则不动。
 */
internal fun resolveRemoveAt(current: Int, size: Int, index: Int): Int? {
    if (index !in 0 until size || index == current) return null
    return if (current in 0 until size && index < current) current - 1 else current
}

/**
 * 把 [index] 处的一项原地展开成 [partCount] 个分 P 后，当前下标应变成什么。
 *
 * 展开的就是当前项时停在 P1（同一下标）；展开点在当前项之前时当前项被顶后 partCount-1 格。
 */
internal fun resolveIndexAfterExpand(current: Int, index: Int, partCount: Int): Int =
    if (current > index) current + partCount - 1 else current
