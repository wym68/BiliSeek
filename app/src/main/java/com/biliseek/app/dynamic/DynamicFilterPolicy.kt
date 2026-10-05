package com.biliseek.app.dynamic

import com.biliseek.core.network.dto.FollowingUp

/** 点「最常访问」里的头像后，筛选怎么变。 */
sealed interface UpFilterChange {
    /** 选中 [up]：从没筛选切过来，或从另一个 UP 直接切过来。 */
    data class Select(val up: FollowingUp) : UpFilterChange

    /** 取消筛选，回到关注流。 */
    data object Clear : UpFilterChange
}

/**
 * 点头像（[up]；null = 回关注流）后筛选怎么变。再点已选中的 = 取消筛选（与 B 站 App 一致）；
 * 没有变化（本来就没筛选又要回关注流）返回 null。
 */
fun resolveUpFilterChange(selectedMid: Long?, up: FollowingUp?): UpFilterChange? = when {
    up == null -> if (selectedMid == null) null else UpFilterChange.Clear
    selectedMid == up.mid -> UpFilterChange.Clear
    else -> UpFilterChange.Select(up)
}

/** 筛选变化后下方提示的文字。UP 主名字太长就截断，用省略号代替放不下的部分。 */
fun upFilterNotice(change: UpFilterChange): String = when (change) {
    UpFilterChange.Clear -> "已取消筛选"
    is UpFilterChange.Select -> {
        val name = ellipsizeName(change.up.uname)
        if (name.isEmpty()) "只看这位 UP 主的动态" else "只看「$name」的动态"
    }
}

/** 提示里 UP 主名字的最大显示宽度：全角算 2、其余算 1，20 ≈ 10 个汉字，系统 Toast 里一行放得下。 */
private const val MAX_NOTICE_NAME_WIDTH = 20

/** 码点从这里起算全角（中日韩、全角符号、emoji）。 */
private const val FULL_WIDTH_FROM = 0x2E80

/**
 * 名字超出 [MAX_NOTICE_NAME_WIDTH] 就截断，末尾补「…」（算 1 格）。
 * 按码点切，不会把 emoji 从中间切坏。系统 Toast 自己不会给省略号，所以得在拼文字时截。
 */
private fun ellipsizeName(raw: String): String {
    val name = raw.trim()
    var width = 0
    var index = 0
    var cutAt = 0 // 宽度 ≤ MAX - 1 的最长前缀的结尾，留一格给省略号
    while (index < name.length) {
        val codePoint = name.codePointAt(index)
        width += if (codePoint >= FULL_WIDTH_FROM) 2 else 1
        index += Character.charCount(codePoint)
        if (width <= MAX_NOTICE_NAME_WIDTH - 1) cutAt = index
    }
    return if (width <= MAX_NOTICE_NAME_WIDTH) name else name.substring(0, cutAt) + "…"
}
