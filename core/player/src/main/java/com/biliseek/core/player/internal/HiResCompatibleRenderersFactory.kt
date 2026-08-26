package com.biliseek.core.player.internal

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * 让 Hi-Res FLAC 在真机上放得出来。
 *
 * **问题**：部分设备的系统 FLAC 解码器只给输入缓冲分配 32 KiB，而 B 站 Hi-Res 的
 * 单帧可能超过这个尺寸，解码时抛 `Insufficient capacity`，表现为**一播就崩**。
 *
 * **解法**：在 codec 真正创建之前，把 FLAC 的 `KEY_MAX_INPUT_SIZE` 抬到 1 MiB。
 * 落点选在 [MediaCodecAdapter.Factory] 而不是渲染器的 `getCodecMaxInputSize`——
 * 后者在 Media3 1.11 里是 private，包装适配器工厂才是不依赖内部实现的做法。
 * 见《架构设计篇》§4.4 边界 2。
 *
 * ⚠️ 这条**只有真机 + 大会员账号 + 特定机型**才撞得到，模拟器复现不了。
 * 合入时它是「按已知解法预防」，不是「实测修好」。
 */
@UnstableApi
internal class HiResCompatibleRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    private val adapterFactory = FlacTolerantAdapterFactory(MediaCodecAdapter.Factory.getDefault(context))

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        // 只替换主音频渲染器，不调 super——那会再加一个默认的进来，变成两条音频渲染链。
        // 扩展渲染器（FFmpeg 等）本项目没有引入，`extensionRendererMode` 保持默认的 OFF，
        // 因此这里不处理它不会丢东西；将来若引入扩展解码器，这个分支要一并补上。
        out.add(
            MediaCodecAudioRenderer(
                context,
                adapterFactory,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                audioSink,
            ),
        )
    }
}

@UnstableApi
private class FlacTolerantAdapterFactory(
    private val delegate: MediaCodecAdapter.Factory,
) : MediaCodecAdapter.Factory {

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val format = configuration.mediaFormat
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        // 只抬 FLAC：别的编码没这个毛病，无谓要大缓冲只是白占内存
        if (mime.contains("flac", ignoreCase = true)) {
            val current = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)
            if (current < FLAC_MIN_INPUT_SIZE) {
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FLAC_MIN_INPUT_SIZE)
            }
        }
        return delegate.createAdapter(configuration)
    }

    private companion object {
        const val FLAC_MIN_INPUT_SIZE = 1 shl 20 // 1 MiB
    }
}
