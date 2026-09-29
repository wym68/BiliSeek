package com.biliseek.core.asr.internal

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * 音频文件 → 16 kHz 单声道 float，按块拉取（不整段读入内存：1 小时 16k float 约 230MB）。
 *
 * 收尾（phone-whisper v1.0.3 的教训）：输入端送出 EOS 后解码器里还积压着最后几帧，
 * 必须一直取输出直到**输出端**出现 `BUFFER_FLAG_END_OF_STREAM` 才算结束，否则最后几个字会丢。
 *
 * 断点续做：[startMs] > 0 时 `seekTo` 到之前的同步帧，解码后丢弃时间戳早于起点的样本，
 * 所以输出的第 0 个样本恰好对应 [startMs]。
 */
internal class PcmDecoder(file: File, private val startMs: Long) : AutoCloseable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var sampleRate: Int
    private var channels: Int
    private var floatPcm = false
    private var resampler: LinearResampler

    /** 音频总时长（容器声明值；拿不到为 0）。 */
    val durationMs: Long

    init {
        try {
            extractor.setDataSource(file.absolutePath)
        } catch (e: IOException) {
            extractor.release()
            throw DecodeException("无法打开音频文件：${e.message}")
        }
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (track == null) {
            extractor.release()
            throw DecodeException("文件里没有音轨")
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        resampler = ResamplePolicy.resampler(sampleRate)
        if (startMs > 0) extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec = try {
            MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(format, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            extractor.release()
            throw DecodeException("不支持的音频格式：${e.message}")
        }
    }

    /** 下一块 16 kHz 单声道样本；解码结束返回 null。可能返回空数组（本轮只喂了输入），调用方继续拉即可。 */
    fun next(): FloatArray? {
        if (outputDone) return null
        try {
            if (!inputDone) feedInput()
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    resampler = ResamplePolicy.resampler(sampleRate)
                    return EMPTY
                }
                index < 0 -> return EMPTY
            }
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val out = if (info.size > 0) readOutput(index) else EMPTY
            codec.releaseOutputBuffer(index, false)
            if (eos) outputDone = true
            return out
        } catch (e: IllegalStateException) {
            throw DecodeException("解码失败：${e.message}")
        }
    }

    private fun feedInput() {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun readOutput(index: Int): FloatArray {
        val buffer = codec.getOutputBuffer(index) ?: return EMPTY
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.order(ByteOrder.nativeOrder())
        val interleaved: FloatArray
        if (floatPcm) {
            val fb = buffer.asFloatBuffer()
            interleaved = FloatArray(fb.remaining()).also { fb.get(it) }
        } else {
            val sb = buffer.asShortBuffer()
            val shorts = ShortArray(sb.remaining()).also { sb.get(it) }
            interleaved = ResamplePolicy.pcm16ToFloat(shorts)
        }
        var mono = ResamplePolicy.downmix(interleaved, interleaved.size, channels)
        // 丢弃断点之前的样本（seek 落在更早的同步帧）
        val startUs = startMs * 1000
        val ptsUs = info.presentationTimeUs
        if (startMs > 0 && ptsUs < startUs) {
            val skip = ((startUs - ptsUs) * sampleRate / 1_000_000).toInt()
            if (skip >= mono.size) return EMPTY
            mono = mono.copyOfRange(skip, mono.size)
        }
        return resampler.process(mono)
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    class DecodeException(message: String) : Exception(message)

    private companion object {
        const val TIMEOUT_US = 10_000L
        val EMPTY = FloatArray(0)
    }
}
