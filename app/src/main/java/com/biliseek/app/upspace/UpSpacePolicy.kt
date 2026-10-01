package com.biliseek.app.upspace

import com.biliseek.core.network.BiliSession

/** UP 空间页的纯函数。无副作用，配单测。 */
object UpSpacePolicy {

    /**
     * 空间页的 UP 是不是当前登录账号本人。
     *
     * 是本人时：不显示关注按钮、不查关注状态、不计入「最常访问」，关注操作直接忽略——
     * B 站不允许关注自己，按钮点了只会报「关注失败」，查关注状态也是白发一次请求。
     * 账号 mid 取本地会话的 `DedeUserID`，不发请求。未登录或 mid 无效一律不算本人。
     */
    fun isSelf(upMid: Long, session: BiliSession): Boolean =
        upMid > 0 && session.isLoggedIn && session.dedeUserId.toLongOrNull() == upMid
}
