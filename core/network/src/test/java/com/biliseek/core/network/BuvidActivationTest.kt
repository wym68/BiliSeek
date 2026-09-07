package com.biliseek.core.network

import com.biliseek.core.network.internal.DeviceFingerprint
import com.biliseek.core.network.internal.buildActivationPayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B1 激活载荷：结构合法、字段随 fp 变、与 UA 自洽（工单 §4.1）。 */
class BuvidActivationTest {

    private val fp = DeviceFingerprint(
        screenW = 1920, screenH = 1080, availW = 1920, availH = 1032,
        hardwareConcurrency = 8, deviceMemory = 16,
    )

    private fun inner(buvid3: String, fp: DeviceFingerprint): JSONObject {
        val outer = JSONObject(buildActivationPayload(buvid3, fp))
        return JSONObject(outer.getString("payload"))
    }

    @Test
    fun `结构合法且携带 buvid3`() {
        val p = inner("BUVID3VAL", fp)
        assertEquals("BUVID3VAL", p.getString("df35"))
        assertTrue(p.has("3c43"))
    }

    @Test
    fun `分辨率 核数 内存 由 fp 渲染`() {
        val p = inner("x", fp)
        assertEquals("1920x1080", p.getString("6e7c"))
        val core = p.getJSONObject("3c43")
        assertEquals(8, core.getInt("1c57"))
        assertEquals(16, core.getInt("0bd0"))
        assertEquals(24, core.getInt("5766"))
        assertEquals(-480, core.getInt("fc9d"))
        // 748e = [screenH, screenW]，d61f = [availH, availW]
        assertEquals(1080, core.getJSONArray("748e").getInt(0))
        assertEquals(1920, core.getJSONArray("748e").getInt(1))
        assertEquals(1032, core.getJSONArray("d61f").getInt(0))
    }

    @Test
    fun `UA 自洽为 Windows Chrome`() {
        val core = inner("x", fp).getJSONObject("3c43")
        val ua = core.getString("b8ce")
        assertTrue(ua.contains("Windows"))
        assertTrue(ua.contains("Chrome"))
    }

    @Test
    fun `不同 fp 产出不同分辨率字段`() {
        val other = fp.copy(screenW = 2560, screenH = 1440)
        assertEquals("2560x1440", inner("x", other).getString("6e7c"))
    }
}
