package com.biliseek.core.asr.internal

/** 识别引擎要的采样格式：16 kHz 单声道 float（-1..1）。 */
internal object ResamplePolicy {
    const val TARGET_RATE = 16_000

    /** 16 bit PCM → float。 */
    fun pcm16ToFloat(src: ShortArray, count: Int = src.size): FloatArray =
        FloatArray(count) { src[it] / 32768f }

    /** 交织多声道 → 单声道（各声道取平均）。[count] 为交织样本总数。 */
    fun downmix(interleaved: FloatArray, count: Int, channels: Int): FloatArray {
        if (channels <= 1) return if (count == interleaved.size) interleaved else interleaved.copyOf(count)
        val frames = count / channels
        return FloatArray(frames) { f ->
            var sum = 0f
            val base = f * channels
            for (c in 0 until channels) sum += interleaved[base + c]
            sum / channels
        }
    }

    /** 流式重采样器；采样率变化（解码器输出格式变更）时新建一个。 */
    fun resampler(inRate: Int, outRate: Int = TARGET_RATE): LinearResampler = LinearResampler(inRate, outRate)
}

/**
 * 线性插值重采样，按块流式处理：块与块之间保留上一块最后一个样本，接缝处不跳变、不丢样本。
 * 语音识别对重采样质量不敏感（16 kHz 以下的频段足够），线性插值即可。
 */
internal class LinearResampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate

    /** 下一个输出样本在「当前块」坐标系里的位置；-1..0 之间表示落在上一块末尾与本块开头之间。 */
    private var pos = 0.0
    private var last = 0f

    init {
        require(inRate > 0 && outRate > 0) { "bad rate $inRate -> $outRate" }
    }

    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        if (count == 0) return FloatArray(0)
        if (inRate == outRate) return if (count == input.size) input else input.copyOf(count)
        val out = FloatArray(((count - pos) / step).toInt() + 2)
        var n = 0
        while (pos < count - 1) {
            val i = kotlin.math.floor(pos).toInt()
            val frac = (pos - i).toFloat()
            val a = if (i < 0) last else input[i]
            val b = input[i + 1]
            out[n++] = a + (b - a) * frac
            pos += step
        }
        pos -= count
        last = input[count - 1]
        return if (n == out.size) out else out.copyOf(n)
    }
}
