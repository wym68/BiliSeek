package com.biliseek.core.network

/**
 * 搜索失败的类型。
 *
 * 早期把所有失败都塞进一个 message 字符串，UI 只能一律显示「出错了 + 重试」。
 * 但这些失败**该给用户的操作并不相同**——尤其是风控拦截，给了重试按钮就是在诱导伤害账号。
 *
 * 文案留给 UI 层，这里只描述「是什么」。
 */
sealed interface SearchError {

    /** `-352` 风控拦截。见 [retryable]。 */
    data object Blocked : SearchError

    /** `-412` 请求被拦截，多为指纹缺失；凭据已重置，可以重试。 */
    data object CredentialsInvalid : SearchError

    /** `code=0` 但 `data` 为空，多为 buvid 未激活，服务端只回了 v_voucher。 */
    data object NotActivated : SearchError

    /** 网络不可达 / 超时 / 凭据拿不到。 */
    data class Network(val message: String) : SearchError

    /** `code=0` 且结果为 0 条。不是错误，但同样不能白屏。 */
    data class Empty(val keyword: String) : SearchError

    /** 其他业务错误码。 */
    data class Unknown(val message: String) : SearchError
}

/**
 * 是否该给用户一个「重试」按钮。
 *
 * **[SearchError.Blocked] 恒为 false。** `BACKOFF_NO_RETRY` 的语义就是绝不重试
 * （《架构设计篇》§3.2）：UI 一旦放出按钮，用户就会猛戳，
 * 把一次临时限流推成长期封禁。这条不能靠「用户自觉」兜底。
 */
val SearchError.retryable: Boolean
    get() = when (this) {
        SearchError.Blocked -> false
        // 重试也还是没有结果，按钮只会让人以为是加载失败
        is SearchError.Empty -> false
        else -> true
    }

/** 搜索失败的载体。[error] 是分类，`message` 供日志与兜底展示。 */
class SearchException(val error: SearchError, message: String) : Exception(message)
