package com.biliseek.core.network

/** 写操作失败分类。:app 据此决定：弹登录 / 弹重试 / 弹风控提示 / 静默。 */
sealed interface WriteError {
    data object NeedLogin : WriteError            // -101 或本地无 bili_jct
    data object CsrfInvalid : WriteError          // 刷新+重试后仍 -111 → 建议重登录
    data object Blocked : WriteError              // -352
    data object CredentialsInvalid : WriteError   // -412（已触发重种）
    data class  Network(val message: String) : WriteError
    data class  Unknown(val code: Int, val message: String) : WriteError
}

class WriteException(val error: WriteError, message: String) : Exception(message)
