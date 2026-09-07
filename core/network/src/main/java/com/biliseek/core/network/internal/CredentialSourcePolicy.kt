package com.biliseek.core.network.internal

/** 设备身份来源。真登录带回的 buvid 系可信度远高于自造。 */
internal enum class CredentialSource { REAL_LOGIN, SELF_SEEDED }

/** 登录 `Set-Cookie` 里带回的 buvid 系（可能部分缺失，视登录方式而定）。 */
internal data class LoginBuvids(
    val buvid3: String = "",
    val buvid4: String = "",
    val buvidFp: String = "",
    val bNut: String = "",
)

/**
 * 有登录带回的完整 buvid 系（buvid3 + buvid_fp 都在）→ 采信真登录；否则自种。
 * 纯函数、可单测。
 */
internal fun resolveCredentialSource(loginBuvid3: String?, loginBuvidFp: String?): CredentialSource =
    if (!loginBuvid3.isNullOrBlank() && !loginBuvidFp.isNullOrBlank()) {
        CredentialSource.REAL_LOGIN
    } else {
        CredentialSource.SELF_SEEDED
    }

/** 从 `Set-Cookie` 头里抽 buvid 系。纯函数，与账号凭据解析同源同法。 */
internal fun parseLoginBuvids(cookies: List<String>): LoginBuvids {
    fun pick(name: String): String = cookies
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter("$name=")
        ?.substringBefore(';')
        .orEmpty()
    return LoginBuvids(
        buvid3 = pick("buvid3"),
        buvid4 = pick("buvid4"),
        buvidFp = pick("buvid_fp"),
        bNut = pick("b_nut"),
    )
}
