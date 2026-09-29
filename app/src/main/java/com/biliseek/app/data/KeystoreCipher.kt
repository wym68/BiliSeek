package com.biliseek.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 字符串加解密。抽成接口：JVM 单测用假实现，真机走 [KeystoreCipher]。 */
interface SecretCipher {
    fun encrypt(plain: String): String

    /** 解不开（换机恢复、密钥被清、数据损坏）返回 null——调用方按「未配置」处理，**不崩**。 */
    fun decrypt(encoded: String): String?
}

/**
 * Android Keystore 里的 AES-256-GCM 密钥（工单 AI-3）。
 *
 * 密文格式 = Base64(IV(12 字节) + 密文 + GCM tag)。密钥不要求用户认证：后台前台服务里也要能解出 Key。
 * 不用已废弃的 security-crypto。
 */
class KeystoreCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            existingKey() ?: return null,
            GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES),
        )
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? =
        (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun key(): SecretKey = existingKey() ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }
        .generateKey()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "biliseek_ai_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
