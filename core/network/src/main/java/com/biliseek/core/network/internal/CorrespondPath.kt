package com.biliseek.core.network.internal

import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * `correspondPath` —— Cookie 刷新链路的第一步。
 *
 * 用固定 RSA 公钥以 OAEP(SHA-256) 加密 `refresh_{timestamp}`，输出小写 hex。
 * 纯函数，可单测。
 */
internal fun correspondPath(timestampMs: Long, publicKeyPem: String): String =
    rsaOaepHex(correspondPlaintext(timestampMs), publicKeyPem)

/** 被加密的明文。单独抽出来是为了让「格式错了」这种错误能被单测钉住。 */
internal fun correspondPlaintext(timestampMs: Long): String = "refresh_$timestampMs"

/**
 * RSA-OAEP(SHA-256) 加密后转小写 hex。
 *
 * **MGF1 的摘要必须显式指定成 SHA-256。** JCA 里 `OAEPWithSHA-256AndMGF1Padding`
 * 这个名字只约束了 OAEP 的摘要，MGF1 在多数 provider 上默认仍是 SHA-1——
 * 而浏览器 WebCrypto 的 RSA-OAEP 两处都用同一个摘要。
 * 不写这个 [OAEPParameterSpec]，加密照样成功，服务端却解不开。
 */
internal fun rsaOaepHex(plaintext: String, publicKeyPem: String): String {
    val der = Base64.getDecoder().decode(
        publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), ""),
    )
    val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        key,
        OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
    )
    return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * 从 `/correspond/1/{path}` 返回的 HTML 里抠出 `refresh_csrf`。
 *
 * 目标是 `id="1-name"` 元素的文本。为此引一个 HTML 转换器不划算——
 * 这是**一个**已知结构的固定页面，正则足够，而且能被单测钉死。
 */
internal fun extractRefreshCsrf(html: String): String? =
    REFRESH_CSRF.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

/** 属性可能用单引号或双引号，元素与文本之间也可能有换行。 */
private val REFRESH_CSRF = Regex(
    """id=["']1-name["'][^>]*>([^<]*)<""",
    RegexOption.IGNORE_CASE,
)
