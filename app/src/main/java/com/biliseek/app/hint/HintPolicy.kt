package com.biliseek.app.hint

// 首次使用演示的判定（设计稿：docs/superpowers/specs/2026-10-07-first-use-hints-design.md）。
// 纯函数；HintController 只负责持有状态与落盘。

/** 13 项演示。[prefKey] 是 SharedPreferences（biliseek_hints）里的键。 */
enum class HintKey(val prefKey: String) {
    /** 沿用旧键：看过旧版队列演示的不再放。 */
    SWIPE_QUEUE("queue_swipe_hint_shown"),
    SWIPE_LOCAL_PLAYLIST("hint_swipe_local_playlist"),
    SWIPE_FAVORITE("hint_swipe_favorite"),
    SWIPE_WATCH_LATER("hint_swipe_watch_later"),
    SWIPE_DOWNLOADS("hint_swipe_downloads"),
    SWIPE_SUMMARY("hint_swipe_summary"),
    MINIBAR_SWIPE("hint_minibar_swipe"),
    MINIBAR_DRAG("hint_minibar_drag"),
    MINIBAR_ZOOM("hint_minibar_zoom"),
    DYNAMIC_UP_FILTER("hint_dynamic_up_filter"),
    DYNAMIC_VIDEO_MENU("hint_dynamic_video_menu"),
    SEARCH_MULTI_SELECT("hint_search_multi_select"),
    UPSPACE_MULTI_SELECT("hint_upspace_multi_select"),
}

/** 启动序号 / 进入序号的「没有」。 */
const val HINT_NONE = -1

/** 存在本机的部分。 */
data class PersistedHints(
    val shown: Set<HintKey> = emptySet(),
    /** 冷启动序号：每次冷启动加一。 */
    val launchSeq: Int = 0,
    /** [HintKey.MINIBAR_SWIPE] 记为看过时的启动序号：② 要等之后的另一次启动。 */
    val minibarSwipeLaunch: Int = HINT_NONE,
)

data class HintState(
    val persisted: PersistedHints = PersistedHints(),
    /** 正在放的演示（同一时间最多一个）。 */
    val active: HintKey? = null,
    /** 全屏 / 画中画：不开始新演示。 */
    val environmentBlocked: Boolean = false,
    /** 本进程第几次进入动态页（只在内存）。 */
    val dynamicVisit: Int = 0,
    /** [HintKey.DYNAMIC_UP_FILTER] 在本进程哪一次进入里记为看过：⑪ 要等之后的另一次进入。 */
    val upFilterVisit: Int = HINT_NONE,
    /** 用户自己拖完过进度线：③ 可以放（只在内存）。 */
    val zoomArmed: Boolean = false,
)

/** 现在能不能开始放 [key]。 */
fun canStartHint(key: HintKey, state: HintState): Boolean {
    val shown = state.persisted.shown
    if (key in shown || state.active != null || state.environmentBlocked) return false
    return when (key) {
        HintKey.MINIBAR_DRAG ->
            HintKey.MINIBAR_SWIPE in shown && state.persisted.launchSeq > state.persisted.minibarSwipeLaunch
        HintKey.MINIBAR_ZOOM -> state.zoomArmed
        HintKey.DYNAMIC_VIDEO_MENU ->
            HintKey.DYNAMIC_UP_FILTER in shown && state.upFilterVisit != state.dynamicVisit
        else -> true
    }
}

/**
 * 别的演示正在放、而 [key] 本来能放：这次进入页面就放弃，等下次进入（不在前一个放完后接着连放）。
 * 本来就不能放的（如刻度尺还没解锁）不放弃，条件满足时照常开始。
 */
fun shouldForfeitHint(key: HintKey, state: HintState): Boolean =
    state.active != null && state.active != key && canStartHint(key, state.copy(active = null))

fun startHint(state: HintState, key: HintKey): HintState =
    if (canStartHint(key, state)) state.copy(active = key) else state

/** 没放完就离开页面：不记为看过。 */
fun cancelHint(state: HintState, key: HintKey): HintState =
    if (state.active == key) state.copy(active = null) else state

/** 记为看过（演示放完 / 被碰掉 / 用户自己用过）；正在放的就是它则停下。 */
fun markHintSeen(state: HintState, key: HintKey): HintState {
    val persisted = state.persisted
    val first = key !in persisted.shown
    return state.copy(
        persisted = persisted.copy(
            shown = persisted.shown + key,
            minibarSwipeLaunch = if (first && key == HintKey.MINIBAR_SWIPE) {
                persisted.launchSeq
            } else {
                persisted.minibarSwipeLaunch
            },
        ),
        active = if (state.active == key) null else state.active,
        upFilterVisit = if (first && key == HintKey.DYNAMIC_UP_FILTER) state.dynamicVisit else state.upFilterVisit,
    )
}

/** 用户自己做了 [key] 对应的操作：记为看过；自己拖完进度线还会让刻度尺演示（③）可以放。 */
fun applyUserAction(state: HintState, key: HintKey): HintState {
    val seen = markHintSeen(state, key)
    val armZoom = key == HintKey.MINIBAR_DRAG && HintKey.MINIBAR_ZOOM !in seen.persisted.shown
    return if (armZoom) seen.copy(zoomArmed = true) else seen
}

/** 设置里「重新显示操作提示」：清空看过的记录，冷启动序号保留。 */
fun resetHints(state: HintState): HintState = state.copy(
    persisted = PersistedHints(launchSeq = state.persisted.launchSeq),
    active = null,
    upFilterVisit = HINT_NONE,
    zoomArmed = false,
)
