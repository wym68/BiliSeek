package com.biliseek.app.summary

import android.app.Application
import android.net.Uri
import com.biliseek.app.data.AiSummaryEntity
import com.biliseek.app.data.BiliSeekDatabase
import com.biliseek.app.data.DownloadStatus
import com.biliseek.core.ai.Chapter
import com.biliseek.core.ai.Transcript
import com.biliseek.core.asr.AsrProgress
import com.biliseek.core.asr.OfflineTranscriber
import com.biliseek.core.asr.model.AsrModelManager
import com.biliseek.core.network.BiliNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 本地识别的原文来源（工单 AI-8 `LocalAsrSource`，实现 AI-5 的 [LocalAsrRunner]）：
 *
 * 1. 音频：「已下载」里有该 (bvid, cid) → 直接用本地文件（完全离线，不请求 B 站）；
 *    否则用与下载同一个取流解析器（[resolveAudioUrl]，最低音质档）下载到 `audio.m4a`，
 *    走 B 站媒体请求头的 client（不是 AI client），支持 Range 续传，识别完成即删。
 * 2. [OfflineTranscriber] 识别，断点在 `asr_partial.jsonl`；进度回调给 [SummaryManager]。
 * 3. 识别完成把 RTF 并入滑动平均，供下次预估耗时。
 */
class LocalAsrSource(
    private val app: Application,
    private val transcriber: OfflineTranscriber,
    private val settings: AiSettingsStore,
    /** (bvid, cid) → 音频直链（SAVER 档）。由 AppViewModel 注入播放器的取流解析器。 */
    private val resolveAudioUrl: suspend (String, Long) -> String,
) : LocalAsrRunner {

    private val downloadDao = BiliSeekDatabase.get(app).downloadDao()

    private val mediaHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply {
                BiliNetwork.get(app).mediaRequestHeaders.forEach { (name, value) ->
                    addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header(name, value).build()) }
                }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun transcribe(
        row: AiSummaryEntity,
        chapters: List<Chapter>,
        workDir: File,
        onProgress: (processedMs: Long, totalMs: Long) -> Unit,
    ): Transcript {
        workDir.mkdirs()
        val files = SummaryFiles(workDir.parentFile!!, row.bvid, row.cid)
        val (audio, temporary) = prepareAudio(row, files.audio, onProgress)

        var result: Transcript? = null
        var failure: String? = null
        transcriber.transcribe(audio, files.asrPartial).collect { progress ->
            when (progress) {
                is AsrProgress.Running -> onProgress(progress.processedMs, progress.totalMs)
                is AsrProgress.Done -> {
                    if (progress.rtf > 0f) settings.recordAsrRtf(progress.rtf)
                    result = LocalAsrPolicy.toTranscript(progress.segments, chapters)
                }
                is AsrProgress.Failed -> failure = LocalAsrPolicy.describe(progress.error)
            }
        }
        failure?.let { throw IOException(it) }
        val transcript = result ?: throw IOException("离线识别没有结果")
        if (temporary) {
            audio.delete()
            File(audio.parentFile, audio.name + DONE_SUFFIX).delete()
        }
        return transcript
    }

    /** 返回 (音频文件, 是否临时文件)。 */
    private suspend fun prepareAudio(
        row: AiSummaryEntity,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): Pair<File, Boolean> = withContext(Dispatchers.IO) {
        // 已下载：file:// 直接用；content://（公共目录 / SAF）拷一份到私有目录（本地拷贝，不联网）
        val local = downloadDao.find(row.bvid, row.cid)
            ?.takeIf { it.status == DownloadStatus.DONE }
            ?.let { LocalAsrPolicy.pickLocalAudio(it.audioFile, it.videoFile) }
        if (local != null) {
            val uri = Uri.parse(local)
            if (uri.scheme == "file") {
                val file = File(uri.path.orEmpty())
                if (file.exists()) return@withContext file to false
            } else {
                val copied = runCatching {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                }.getOrNull()
                if (copied != null && target.length() > 0) return@withContext target to true
                target.delete()
            }
        }
        // 上次已经下完（识别被打断）：直接用
        val done = File(target.parentFile, target.name + DONE_SUFFIX)
        if (target.exists() && done.exists()) return@withContext target to true

        val url = resolveAudioUrl(row.bvid, row.cid)
        download(url, target, onProgress)
        done.createNewFile()
        target to true
    }

    private suspend fun download(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        val existing = if (target.exists()) target.length() else 0L
        val request = Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()
        val call = mediaHttp.newCall(request)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载音频失败：HTTP ${response.code}")
                val append = response.code == 206
                var written = if (append) existing else 0L
                java.io.FileOutputStream(target, append).use { out ->
                    response.body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= 500) {
                                lastReport = now
                                // 下载阶段不知道音频时长，进度以 0 / 0 表示「准备中」
                                onProgress(0L, 0L)
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: IOException) {
            throw IOException(e.message?.takeIf { it.startsWith("下载音频失败") } ?: "下载音频中断，可稍后重试", e)
        }
    }

    companion object {
        private const val DONE_SUFFIX = ".done"

        @Volatile
        private var models: AsrModelManager? = null

        @Volatile
        private var transcriber: OfflineTranscriber? = null

        /** 进程内唯一的模型管理器（设置页与识别共用同一份状态）。 */
        fun models(app: Application): AsrModelManager =
            models ?: synchronized(this) { models ?: AsrModelManager(app).also { models = it } }

        fun transcriber(app: Application): OfflineTranscriber =
            transcriber ?: synchronized(this) {
                transcriber ?: OfflineTranscriber(app, models(app)).also { transcriber = it }
            }
    }
}
