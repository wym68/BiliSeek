package com.biliseek.core.asr

import android.content.Context
import android.os.Process
import com.biliseek.core.asr.internal.AsrCheckpoint
import com.biliseek.core.asr.internal.AsrTextPolicy
import com.biliseek.core.asr.internal.PcmDecoder
import com.biliseek.core.asr.internal.ResamplePolicy
import com.biliseek.core.asr.internal.VadParams
import com.biliseek.core.asr.internal.VadSegmentPolicy
import com.biliseek.core.asr.model.AsrModelCatalog
import com.biliseek.core.asr.model.AsrModelManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.Executors

/**
 * 离线识别入口（工单 AI-7）：音频文件 → 解码 → Silero VAD → SenseVoice → 带时间戳的句子。
 *
 * - 串行执行（进程内同一时间只跑一个识别）；跑在独立的低优先级线程，不占主线程。
 * - [checkpoint] 已有内容时从最后一句的 endMs 继续；每识别完一句就追加落盘。
 * - 取消协程即停止喂数据并释放原生对象，checkpoint 保留（下次可续）。
 * - 失败以 [AsrProgress.Failed] 发射后正常结束，不抛异常。
 */
class OfflineTranscriber(context: Context, private val models: AsrModelManager) {
    private val assets = context.applicationContext.assets

    fun transcribe(
        audio: File,
        checkpoint: File?,
        numThreads: Int = defaultThreads(),
        spec: AsrModelSpec = AsrModelCatalog.default,
    ): Flow<AsrProgress> {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "offline-asr")
        }
        return flow {
            serial.withLock { run(audio, checkpoint, numThreads, spec) }
        }
            .flowOn(executor.asCoroutineDispatcher())
            .onCompletion { executor.shutdown() }
    }

    private suspend fun FlowCollector<AsrProgress>.run(audio: File, checkpointFile: File?, numThreads: Int, spec: AsrModelSpec) {
        val dir = models.modelDir(spec)
        if (dir == null) {
            emit(AsrProgress.Failed(AsrError.ModelNotReady))
            return
        }
        val checkpoint = checkpointFile?.let(::AsrCheckpoint)
        val previous = checkpoint?.load().orEmpty()
        val resumeMs = AsrCheckpoint.resumeMs(previous)

        var decoder: PcmDecoder? = null
        var vad: Vad? = null
        var recognizer: OfflineRecognizer? = null
        try {
            decoder = try {
                PcmDecoder(audio, resumeMs)
            } catch (e: PcmDecoder.DecodeException) {
                emit(AsrProgress.Failed(AsrError.DecodeFailed(e.message.orEmpty())))
                return
            }
            val params = VadSegmentPolicy.DEFAULT
            try {
                vad = Vad(assets, vadConfig(params))
                recognizer = OfflineRecognizer(null, recognizerConfig(dir, numThreads))
            } catch (e: UnsatisfiedLinkError) {
                emit(AsrProgress.Failed(AsrError.EngineFailed("原生库加载失败：${e.message}")))
                return
            } catch (e: OutOfMemoryError) {
                emit(AsrProgress.Failed(AsrError.OutOfMemory))
                return
            } catch (e: Exception) {
                emit(AsrProgress.Failed(AsrError.EngineFailed(e.message ?: e.javaClass.simpleName)))
                return
            }

            val v = checkNotNull(vad)
            val r = checkNotNull(recognizer)
            val totalMs = decoder.durationMs
            val all = previous.filter { it.text.isNotBlank() }.toMutableList()
            val pending = mutableListOf<AsrSegment>()
            val window = FloatArray(params.windowSize)
            var windowFill = 0
            var fedSamples = 0L
            var lastEmit = 0L
            val started = System.nanoTime()

            suspend fun drain() {
                while (!v.empty()) {
                    currentCoroutineContext().ensureActive()
                    val seg = v.front()
                    v.pop()
                    if (!VadSegmentPolicy.shouldKeep(seg.samples.size, RATE, params)) continue
                    for (range in VadSegmentPolicy.split(seg.samples.size, RATE, params)) {
                        val samples = seg.samples.copyOfRange(range.first, range.last + 1)
                        val start = VadSegmentPolicy.toMs(seg.start.toLong() + range.first, RATE, resumeMs)
                        val end = VadSegmentPolicy.toMs(seg.start.toLong() + range.last + 1, RATE, resumeMs)
                        val stream = r.createStream()
                        val result = try {
                            stream.acceptWaveform(samples, RATE)
                            r.decode(stream)
                            r.getResult(stream)
                        } finally {
                            stream.release()
                        }
                        val segment = AsrSegment(start, end, AsrTextPolicy.clean(result.text), AsrTextPolicy.language(result.lang))
                        // 空文本也落盘：它推进了续做起点
                        checkpoint?.append(segment)
                        if (segment.text.isNotBlank()) {
                            all += segment
                            pending += segment
                        }
                    }
                }
            }

            suspend fun report(force: Boolean) {
                val now = System.currentTimeMillis()
                if (!force && pending.isEmpty() && now - lastEmit < EMIT_INTERVAL_MS) return
                lastEmit = now
                val processed = resumeMs + fedSamples * 1000 / RATE
                emit(AsrProgress.Running(processed, maxOf(totalMs, processed), pending.toList()))
                pending.clear()
            }

            while (true) {
                currentCoroutineContext().ensureActive()
                val chunk = decoder.next() ?: break
                var i = 0
                while (i < chunk.size) {
                    val n = minOf(chunk.size - i, window.size - windowFill)
                    System.arraycopy(chunk, i, window, windowFill, n)
                    windowFill += n
                    i += n
                    if (windowFill == window.size) {
                        v.acceptWaveform(window.copyOf())
                        fedSamples += window.size
                        windowFill = 0
                        drain()
                    }
                }
                report(force = false)
            }
            if (windowFill > 0) {
                v.acceptWaveform(window.copyOf(windowFill))
                fedSamples += windowFill
            }
            // 收尾：把最后一个未闭合的语音片段交给识别
            v.flush()
            drain()
            report(force = true)

            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val processedMs = fedSamples * 1000 / RATE
            val rtf = if (processedMs > 0) elapsedMs.toFloat() / processedMs else 0f
            emit(AsrProgress.Done(all.toList(), rtf))
        } catch (e: PcmDecoder.DecodeException) {
            emit(AsrProgress.Failed(AsrError.DecodeFailed(e.message.orEmpty())))
        } catch (e: OutOfMemoryError) {
            emit(AsrProgress.Failed(AsrError.OutOfMemory))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(AsrProgress.Failed(AsrError.EngineFailed(e.message ?: e.javaClass.simpleName)))
        } finally {
            runCatching { recognizer?.release() }
            runCatching { vad?.release() }
            runCatching { decoder?.close() }
        }
    }

    private fun vadConfig(p: VadParams) = VadModelConfig().apply {
        sileroVadModelConfig = SileroVadModelConfig().apply {
            model = AsrModelCatalog.VAD_ASSET
            threshold = p.threshold
            minSilenceDuration = p.minSilenceSec
            minSpeechDuration = p.minSpeechSec
            maxSpeechDuration = p.maxSpeechSec
            windowSize = p.windowSize
        }
        sampleRate = RATE
        numThreads = 1
    }

    private fun recognizerConfig(dir: File, threads: Int) = OfflineRecognizerConfig().apply {
        featConfig = FeatureConfig().apply {
            sampleRate = RATE
            featureDim = 80
        }
        modelConfig = OfflineModelConfig().apply {
            senseVoice = OfflineSenseVoiceModelConfig().apply {
                model = File(dir, "model.int8.onnx").absolutePath
                language = "auto"
                useInverseTextNormalization = true
            }
            tokens = File(dir, "tokens.txt").absolutePath
            numThreads = threads
            debug = false
        }
    }

    companion object {
        private const val RATE = ResamplePolicy.TARGET_RATE
        private const val EMIT_INTERVAL_MS = 500L
        private val serial = Mutex()

        /** 默认线程数：min(4, 核数 / 2)。识别会和播放争 CPU，不吃满。 */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    }
}
