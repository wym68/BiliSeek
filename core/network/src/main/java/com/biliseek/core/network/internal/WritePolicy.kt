package com.biliseek.core.network.internal

/** 写操作（点赞/投币/收藏/评论/弹幕）的错误码处置。纯函数、无副作用、可单测。 */
internal sealed interface WriteOutcome {
    data object Success : WriteOutcome            // code == 0
    data object AlreadyDone : WriteOutcome        // 幂等：重复点赞/已收藏，业务等价成功
    data object CsrfInvalid : WriteOutcome        // -111
    data object NeedLogin : WriteOutcome          // -101
    data object Blocked : WriteOutcome            // -352，绝不重试
    data object ReseedCredentials : WriteOutcome  // -412
    data class  Failed(val code: Int) : WriteOutcome
}

/**
 * @param idempotentCodes 该操作视为成功的「重复动作」码。
 *        故意做成入参而非全局常量：65006 对点赞是成功，
 *        同一个码对另一接口未必——绝不能一刀切。
 */
internal fun resolveWriteOutcome(code: Int, idempotentCodes: Set<Int> = emptySet()): WriteOutcome = when {
    code == 0               -> WriteOutcome.Success
    code in idempotentCodes -> WriteOutcome.AlreadyDone
    code == -111            -> WriteOutcome.CsrfInvalid
    code == -101            -> WriteOutcome.NeedLogin
    code == -352            -> WriteOutcome.Blocked
    code == -412            -> WriteOutcome.ReseedCredentials
    else                    -> WriteOutcome.Failed(code)
}

/** 各操作的幂等成功码。TODO 标记项需真人验收实测补全（不得联网自测——AGENTS.md）。 */
internal object IdempotentCodes {
    val LIKE   = setOf(65006)     // 重复点赞（源自 bbplayer api.ts:1094，已确认）
    val COIN   = emptySet<Int>()  // TODO 验收补：如「已投币」码
    val FAV    = emptySet<Int>()  // deal 接口 add/del 同体，一般无需
    val FOLLOW = emptySet<Int>()  // TODO 验收补：如「已关注」码
    val REPORT = emptySet<Int>()  // TODO 验收补：如「重复举报」码（B6）
}
