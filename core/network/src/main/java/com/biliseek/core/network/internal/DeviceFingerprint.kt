package com.biliseek.core.network.internal

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Random

/**
 * per-install 指纹档案。**生成一次、落库复用**：随机性体现在「安装之间」不同（打散聚类），
 * 而非「每次请求」变（那反而异常）。取值整体像一台普通 Windows Chrome 桌面机，与
 * [BILI_USER_AGENT] 自洽。
 *
 * 仅保留会**渲染进 ExClimbWuzhi payload** 的字段（分辨率/核数/内存/时区）——它们已足以在安装间
 * 打散指纹。工单原稿的 webgl/canvas 字段当前 payload 无对应槽位，落库却不发属投机死状态，故略去；
 * 将来 payload 增加 webgl/canvas 槽位时再补。
 */
@Serializable
internal data class DeviceFingerprint(
    val screenW: Int,
    val screenH: Int,
    val availW: Int,
    val availH: Int,
    val colorDepth: Int = 24,
    val hardwareConcurrency: Int,
    val deviceMemory: Int,
    val timezoneOffsetMin: Int = -480,   // Asia/Shanghai 固定
) {
    companion object {
        /** 常见桌面分辨率池（宽 to 高）。 */
        val SCREENS = listOf(
            1920 to 1080, 2560 to 1440, 1536 to 864, 1440 to 900,
            1366 to 768, 1600 to 900, 1280 to 720, 3840 to 2160,
        )
        val CORES = listOf(4, 6, 8, 12, 16)
        val MEMORY = listOf(4, 8, 16)

        /** Windows 任务栏高度：availH = screenH - 任务栏；availW = screenW。 */
        const val TASKBAR_H = 48

        /** 只在无档案时调用一次。用 [SecureRandom] 从**合理值池**里选，不是全随机乱数。 */
        fun generate(random: Random = SecureRandom()): DeviceFingerprint {
            val (w, h) = SCREENS[random.nextInt(SCREENS.size)]
            return DeviceFingerprint(
                screenW = w,
                screenH = h,
                availW = w,
                availH = h - TASKBAR_H,
                hardwareConcurrency = CORES[random.nextInt(CORES.size)],
                deviceMemory = MEMORY[random.nextInt(MEMORY.size)],
            )
        }
    }
}
