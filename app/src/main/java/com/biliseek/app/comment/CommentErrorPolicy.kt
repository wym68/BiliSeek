package com.biliseek.app.comment

import com.biliseek.core.network.SearchError
import com.biliseek.core.network.SearchException
import com.biliseek.core.network.WriteError
import com.biliseek.core.network.WriteException
import com.biliseek.core.network.retryable
import java.io.IOException

/**
 * 评论相关失败 → 给用户看的一句话：「$action失败：原因」。不露原始错误码；
 * 服务端给的中文提示（如「评论内容包含敏感信息」）原样保留。
 */
fun commentErrorText(action: String, error: Throwable): String = "${action}失败：${commentErrorReason(error)}"

private fun commentErrorReason(error: Throwable): String = when (error) {
    is SearchException -> when (val e = error.error) {
        SearchError.Blocked -> BLOCKED
        SearchError.CredentialsInvalid -> CREDENTIALS
        SearchError.NotActivated -> CREDENTIALS
        is SearchError.Network -> NETWORK
        is SearchError.Unknown -> e.message.ifBlank { UNKNOWN }
        else -> UNKNOWN
    }
    is WriteException -> when (val e = error.error) {
        WriteError.NeedLogin -> "请先登录"
        WriteError.CsrfInvalid -> "登录已失效，请重新登录"
        WriteError.Blocked -> BLOCKED
        WriteError.CredentialsInvalid -> CREDENTIALS
        is WriteError.Network -> NETWORK
        is WriteError.Unknown -> e.message.ifBlank { UNKNOWN }
    }
    is IOException -> NETWORK
    else -> UNKNOWN
}

/** 失败后能不能给「重试」：风控拦截（-352 / HTTP 412）不给，免得用户连点把临时限制变长期封禁。 */
fun isRetryableCommentError(error: Throwable): Boolean =
    (error as? SearchException)?.error?.retryable ?: true

private const val BLOCKED = "请求太频繁，请过几分钟再试"
private const val CREDENTIALS = "设备凭据已重置，请稍后再试"
private const val NETWORK = "网络不可用"
private const val UNKNOWN = "未知错误"
