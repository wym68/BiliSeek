package com.biliseek.core.network.internal

import java.util.Random

/**
 * 指纹 cookie 算法（B2）。
 *
 * **诚实红线**：`_uuid` / `buvidFp` 的**精确算法**须与线上 gen 指纹 JS 逐字节对齐——填错不报错、
 * 只静默降低可信度（同 correspondPath 取证标准）。本文件里这两者按文档实现「形状」，但**未做字节级取证**，
 * 一律视为「未验证」：[BiliCookieJar] **只注入已确证安全的 [bNut] / [bLsid]**，`_uuid` / `buvidFp` 备好但
 * 不上线（宁缺毋错——错的比缺的更像伪造）。取到当日线上 JS 的同输入输出后再启用。
 */
internal object FingerprintCookies {

    private const val HEX = "0123456789ABCDEF"

    /** buvid 种植时刻的 unix 秒。随 buvid 一起落库。**安全、可注入**。 */
    fun bNut(seedMs: Long): String = (seedMs / 1000).toString()

    /**
     * b_lsid = randomHex(8, 大写) + "_" + ms 的十六进制大写。会话级。**格式已知、可注入**。
     */
    fun bLsid(seedMs: Long, random: Random): String {
        val rand = buildString { repeat(8) { append(HEX[random.nextInt(16)]) } }
        return "${rand}_${seedMs.toString(16).uppercase()}"
    }

    /**
     * 前端风格 uuid：8-4-4-4-12 段 + (ms%1e5 补 5 位) + "infoc"。
     *
     * ⚠️ **未验证**：段字符池须对齐线上 gen 指纹 JS（bilibili-API-collect §misc/sign/buvid3）；
     * 此处用标准 hex 池仅保证**形状**正确。取证前不注入。
     */
    fun uuid(seedMs: Long, random: Random): String {
        fun seg(n: Int) = buildString { repeat(n) { append(HEX[random.nextInt(16)]) } }
        val tail = (seedMs % 100_000).toString().padStart(5, '0')
        return "${seg(8)}-${seg(4)}-${seg(4)}-${seg(4)}-${seg(12)}${tail}infoc"
    }

    /**
     * buvid_fp = murmur3_x64_128(payloadKey) 的 32 位 hex。
     *
     * ⚠️ **未验证**：murmur3 本身有已知向量（见测试），但 payloadKey 的**组装**须与
     * ExClimbWuzhi payload 同源、字节级对齐。取证前不注入。
     */
    fun buvidFp(payloadKey: String): String =
        murmur3X64_128Hex(payloadKey.toByteArray(Charsets.UTF_8))
}

/** MurmurHash3 x64 128-bit → 32 位小写 hex。标准算法，可用已知向量校验。 */
internal fun murmur3X64_128Hex(data: ByteArray, seed: Int = 0): String {
    val c1 = -0x783c846eeebdac2bL // 0x87c37b91114253d5
    val c2 = 0x4cf5ad432745937fL
    var h1 = seed.toLong() and 0xffffffffL
    var h2 = seed.toLong() and 0xffffffffL
    val nblocks = data.size / 16

    for (i in 0 until nblocks) {
        val base = i * 16
        var k1 = readLongLE(data, base)
        var k2 = readLongLE(data, base + 8)
        k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1
        h1 = java.lang.Long.rotateLeft(h1, 27); h1 += h2; h1 = h1 * 5 + 0x52dce729L
        k2 *= c2; k2 = java.lang.Long.rotateLeft(k2, 33); k2 *= c1; h2 = h2 xor k2
        h2 = java.lang.Long.rotateLeft(h2, 31); h2 += h1; h2 = h2 * 5 + 0x38495ab5L
    }

    var k1 = 0L
    var k2 = 0L
    val tail = nblocks * 16
    val rem = data.size and 15
    if (rem > 8) {
        for (i in (rem - 1) downTo 8) k2 = k2 xor ((data[tail + i].toLong() and 0xff) shl (8 * (i - 8)))
        k2 *= c2; k2 = java.lang.Long.rotateLeft(k2, 33); k2 *= c1; h2 = h2 xor k2
    }
    if (rem > 0) {
        val hi = minOf(rem, 8)
        for (i in (hi - 1) downTo 0) k1 = k1 xor ((data[tail + i].toLong() and 0xff) shl (8 * i))
        k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1
    }

    h1 = h1 xor data.size.toLong(); h2 = h2 xor data.size.toLong()
    h1 += h2; h2 += h1
    h1 = fmix64(h1); h2 = fmix64(h2)
    h1 += h2; h2 += h1
    return "%016x".format(h1) + "%016x".format(h2)
}

private fun readLongLE(data: ByteArray, offset: Int): Long {
    var r = 0L
    for (i in 0 until 8) r = r or ((data[offset + i].toLong() and 0xff) shl (8 * i))
    return r
}

private fun fmix64(k0: Long): Long {
    var k = k0
    k = k xor (k ushr 33)
    k *= -0xae502812aa7333L // 0xff51afd7ed558ccd
    k = k xor (k ushr 33)
    k *= -0x3b314601e57a13adL // 0xc4ceb9fe1a85ec53
    k = k xor (k ushr 33)
    return k
}
