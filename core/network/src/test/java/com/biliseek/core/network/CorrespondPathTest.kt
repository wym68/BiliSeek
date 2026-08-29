package com.biliseek.core.network

import com.biliseek.core.network.internal.correspondPlaintext
import com.biliseek.core.network.internal.extractRefreshCsrf
import com.biliseek.core.network.internal.rsaOaepHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class CorrespondPathTest {

    companion object {
        private lateinit var keyPair: KeyPair

        /**
         * 用自生成的密钥对验证算法本身。
         *
         * RSA-OAEP 带随机填充，同一明文每次密文都不同，没有固定向量可比；
         * 能证明「算得对」的唯一办法是解回来看。这也让本组用例不依赖任何线上常量。
         */
        @JvmStatic
        @BeforeClass
        fun generateKey() {
            keyPair = KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
        }

        private fun pem(): String = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray())
                .encodeToString(keyPair.public.encoded) +
            "\n-----END PUBLIC KEY-----"

        private fun decrypt(hex: String): String {
            val bytes = ByteArray(hex.length / 2) {
                hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyPair.private,
                OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    PSource.PSpecified.DEFAULT,
                ),
            )
            return String(cipher.doFinal(bytes), Charsets.UTF_8)
        }
    }

    @Test
    fun `明文格式为 refresh 加时间戳`() {
        assertEquals("refresh_1700000000000", correspondPlaintext(1_700_000_000_000))
    }

    @Test
    fun `加密结果可用私钥解回原文`() {
        val plaintext = correspondPlaintext(1_700_000_000_000)
        assertEquals(plaintext, decrypt(rsaOaepHex(plaintext, pem())))
    }

    @Test
    fun `输出是小写 hex 且长度等于模长`() {
        val hex = rsaOaepHex("refresh_1", pem())
        // 2048 位密钥 → 256 字节 → 512 个 hex 字符
        assertEquals(512, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    /** OAEP 是随机填充：同一明文两次密文必须不同，否则说明填充没生效。 */
    @Test
    fun `同一明文两次加密结果不同`() {
        val p = pem()
        assertNotEquals(rsaOaepHex("refresh_1", p), rsaOaepHex("refresh_1", p))
    }

    @Test
    fun `PEM 头尾与换行都能容忍`() {
        val oneLine = pem().replace("\n", "")
        assertEquals("refresh_7", decrypt(rsaOaepHex("refresh_7", oneLine)))
    }

    // ── 真实 B 站公钥（2026-08-28 提取自线上 wasm）──────────

    /**
     * 模数（hex，无前导 0）。
     *
     * 这是从 B 站线上 wasm `wasm_rsa_encrypt_bg.wasm` 逐字节比对得到的独立真值——
     * 不依赖本仓库常量，公钥被人改错（或回退成空串）时这条用例会先报警。
     */
    private val REAL_MODULUS = java.math.BigInteger(
        "cb81dd8e02470656da04dd38544446e2a3412051cfe9adc6a330a5ef902285096849" +
            "60970b91c3360ca29c49e1690ff8fa068cb9dfc6179d1e9585cb9424e847db1ef59" +
            "f33e37dd4dca8ccfb7631ee9b4a92640d00c8204300152a0ab7cd802889d3445aec" +
            "69918fe6022b534912e7b095be3424dad1ba81145e969b533181f1",
        16,
    )

    @Test
    fun `真实公钥可解析且模数与线上 wasm 一致`() {
        val der = java.util.Base64.getDecoder().decode(
            CookieRefresher.CORRESPOND_PUBLIC_KEY
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace(Regex("\\s"), ""),
        )
        val pub = java.security.KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.X509EncodedKeySpec(der)) as java.security.interfaces.RSAPublicKey
        assertEquals("模数与线上 wasm 不一致——公钥可能被改错或回退", REAL_MODULUS, pub.modulus)
        assertEquals(java.math.BigInteger("65537"), pub.publicExponent)
    }

    @Test
    fun `真实公钥加密输出为小写 hex 且长度等于模长`() {
        val hex = rsaOaepHex("refresh_1", CookieRefresher.CORRESPOND_PUBLIC_KEY)
        // 1024 位密钥 → 128 字节密文 → 256 个 hex 字符
        assertEquals(256, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ── refresh_csrf 的 HTML 解析 ──────────────────────────

    @Test
    fun `从真实结构的 HTML 片段里取出 refresh_csrf`() {
        val html = """
            <!DOCTYPE html><html><head><title>Bilibili</title></head><body>
            <div id="1-name">2ed4d1a3bcb0b1c9a0b7f1b0e1d2c3f4</div>
            </body></html>
        """.trimIndent()
        assertEquals("2ed4d1a3bcb0b1c9a0b7f1b0e1d2c3f4", extractRefreshCsrf(html))
    }

    @Test
    fun `单引号属性与多余属性都能解析`() {
        assertEquals(
            "abc123",
            extractRefreshCsrf("<div id='1-name' class=\"x\">abc123</div>"),
        )
    }

    @Test
    fun `元素与文本之间有空白时照样解析`() {
        assertEquals("abc123", extractRefreshCsrf("<div id=\"1-name\">\n  abc123\n</div>"))
    }

    /** 页面结构变了要能识别出来，而不是拿一个空串继续往下走。 */
    @Test
    fun `结构不符时返回 null`() {
        assertNull(extractRefreshCsrf(""))
        assertNull(extractRefreshCsrf("<html><body>404</body></html>"))
        assertNull(extractRefreshCsrf("<div id=\"2-name\">abc</div>"))
        assertNull(extractRefreshCsrf("<div id=\"1-name\"></div>"))
        assertNull(extractRefreshCsrf("<div id=\"1-name\">   </div>"))
    }
}
