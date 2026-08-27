package com.biliseek.core.player.internal

/**
 * FLAC 解码器最小输入缓冲，1 MiB。
 *
 * 部分设备只给 FLAC 解码器分配 32 KiB，而 B 站 Hi-Res 的单帧可能超过这个尺寸，
 * 解码时抛 `Insufficient capacity`——表现为一播就崩。
 */
internal const val FLAC_MIN_INPUT_SIZE = 1 shl 20

/**
 * 决定是否要抬高解码器的输入缓冲，以及抬到多少。
 *
 * 抽成纯函数是**因为这条路径验不到真机**：撞上它需要大会员账号 + 特定机型的解码器，
 * 手上两样都没有。既然实测无门，至少把判定逻辑本身钉死在单测里——
 * 否则这段代码只能靠「看起来对」来担保。
 *
 * @param mime 解码器的 MIME，如 `audio/flac`
 * @param currentMaxInputSize 系统已给出的输入缓冲；未设置时传 0
 * @return 需要改写时返回新值，不需要动时返回 `null`
 */
internal fun resolveCodecMaxInputSize(mime: String, currentMaxInputSize: Int): Int? = when {
    // 只抬 FLAC：别的编码没这个毛病，无谓地要大缓冲只是白占内存
    !mime.contains("flac", ignoreCase = true) -> null
    currentMaxInputSize >= FLAC_MIN_INPUT_SIZE -> null
    else -> FLAC_MIN_INPUT_SIZE
}
