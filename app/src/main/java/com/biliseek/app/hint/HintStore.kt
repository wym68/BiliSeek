package com.biliseek.app.hint

import android.content.Context

/** 首次演示记录的存取（[HintController] 用；测试里换成内存实现）。 */
interface HintRecords {
    fun load(): PersistedHints
    fun save(hints: PersistedHints)
}

/** 存在 SharedPreferences「biliseek_hints」：同步读，界面首帧就要知道放不放演示。 */
class SharedPrefsHintRecords(context: Context) : HintRecords {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): PersistedHints = PersistedHints(
        shown = HintKey.entries.filterTo(mutableSetOf()) { prefs.getBoolean(it.prefKey, false) },
        launchSeq = prefs.getInt(KEY_LAUNCH_SEQ, 0),
        minibarSwipeLaunch = prefs.getInt(KEY_MINIBAR_SWIPE_LAUNCH, HINT_NONE),
    )

    override fun save(hints: PersistedHints) {
        val editor = prefs.edit()
        HintKey.entries.forEach { editor.putBoolean(it.prefKey, it in hints.shown) }
        editor.putInt(KEY_LAUNCH_SEQ, hints.launchSeq)
        editor.putInt(KEY_MINIBAR_SWIPE_LAUNCH, hints.minibarSwipeLaunch)
        editor.apply()
    }

    private companion object {
        const val PREFS_NAME = "biliseek_hints"
        const val KEY_LAUNCH_SEQ = "hint_launch_seq"
        const val KEY_MINIBAR_SWIPE_LAUNCH = "hint_minibar_swipe_launch"
    }
}
