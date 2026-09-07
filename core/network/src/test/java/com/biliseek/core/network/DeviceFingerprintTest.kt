package com.biliseek.core.network

import com.biliseek.core.network.internal.DeviceFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** B1 指纹档案：同种子稳定、跨种子有分布、所有值落在合法池（工单 §4.1）。 */
class DeviceFingerprintTest {

    @Test
    fun `同种子生成稳定`() {
        val a = DeviceFingerprint.generate(Random(42))
        val b = DeviceFingerprint.generate(Random(42))
        assertEquals(a, b)
    }

    @Test
    fun `所有值落在合法池`() {
        repeat(200) { seed ->
            val fp = DeviceFingerprint.generate(Random(seed.toLong()))
            assertTrue("分辨率在池内", (fp.screenW to fp.screenH) in DeviceFingerprint.SCREENS)
            assertTrue("核数在池内", fp.hardwareConcurrency in DeviceFingerprint.CORES)
            assertTrue("内存在池内", fp.deviceMemory in DeviceFingerprint.MEMORY)
            assertEquals("availW = screenW", fp.screenW, fp.availW)
            assertEquals("availH = screenH - 任务栏", fp.screenH - DeviceFingerprint.TASKBAR_H, fp.availH)
            assertEquals(24, fp.colorDepth)
            assertEquals(-480, fp.timezoneOffsetMin)
        }
    }

    @Test
    fun `多次抽样有分布（不是全同一份）`() {
        // 复用同一 RNG 流抽样（真机是 SecureRandom）；避免 java.util.Random 顺序小种子首抽相关性
        val r = Random(1234)
        val screens = (0 until 200).map {
            val fp = DeviceFingerprint.generate(r)
            fp.screenW to fp.screenH
        }.toSet()
        assertTrue("多次生成应命中多种分辨率", screens.size > 1)
    }
}
