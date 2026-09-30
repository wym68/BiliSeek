package com.biliseek.app.summary

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.biliseek.app.data.AiSummaryEntity
import com.biliseek.app.data.AiSummarySource
import com.biliseek.app.data.AiSummaryStatus
import com.biliseek.app.data.BiliSeekDatabase
import com.biliseek.core.ai.AiConnectionTester
import com.biliseek.core.ai.AiError
import com.biliseek.core.ai.Chapter
import com.biliseek.core.ai.SummaryMarkdown
import com.biliseek.core.ai.SummaryProgress
import com.biliseek.core.ai.SummaryRequest
import com.biliseek.core.ai.Summarizer
import com.biliseek.core.ai.Transcript
import com.biliseek.core.ai.VideoContext
import com.biliseek.core.ai.provider.AiEndpointConfig
import com.biliseek.core.network.BiliNetwork
import com.biliseek.core.network.dto.ConclusionModelResult
import com.biliseek.core.network.dto.SubtitleTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条视频（某一 P）的元信息。 */
data class SummaryVideo(
    val bvid: String,
    val cid: Long,
    val title: String,
    val pageTitle: String = "",
    val author: String = "",
    val upMid: Long = 0,
    val coverUrl: String = "",
    val durationMs: Long = 0,
    /** 简介与分区：提示词参考信息（纠正同音错字），可空。 */
    val context: VideoContext? = null,
)

/** 原文从哪来。 */
sealed interface JobSource {
    data class Subtitle(val track: SubtitleTrack, val chapters: List<Chapter>) : JobSource

    /** 本地识别（AI-8）。 */
    data class LocalAsr(val chapters: List<Chapter>) : JobSource

    /** 执行时再决定：先查字幕，没有字幕且离线识别就绪时本地识别（多 P 批量用）。 */
    data object Auto : JobSource
}

/** 进行中的实时进度（只在内存，不落库）。 */
data class SummaryLive(
    val stage: String,
    /** 0..1；null = 不确定进度。 */
    val fraction: Float? = null,
    /** 最终合并的流式正文（已去掉思考内容）。 */
    val streamingText: String = "",
)

/** 阅读页看到的一条总结。 */
data class SummaryUiState(
    /** 当前显示的版本。 */
    val entity: AiSummaryEntity,
    val live: SummaryLive?,
    /** 这条视频的全部版本，新的在前（含 [entity]）。 */
    val versions: List<AiSummaryEntity> = listOf(entity),
)

/**
 * 本地识别的执行者（AI-8 注入）。把 [row] 对应视频的音频识别成原文；[workDir] 放音频与识别断点。
 */
fun interface LocalAsrRunner {
    suspend fun transcribe(
        row: AiSummaryEntity,
        chapters: List<Chapter>,
        workDir: File,
        onProgress: (processedMs: Long, totalMs: Long) -> Unit,
    ): Transcript
}

/**
 * AI 总结的任务队列与执行（工单 AI-5）。照 [com.biliseek.app.download.DownloadManager] 的写法：
 * **[SummaryService] 是唯一的执行容器**，本类只管状态与执行，通知由服务呈现。
 *
 * 任务串行；每一步的成果都落盘（原文 transcript.json、总结断点 checkpoint.json、识别断点），
 * 任何失败都不丢已花掉的成本。进程被杀后 [resumePending] 按断点重新入队。
 */
class SummaryManager private constructor(private val app: Application) {

    /** AI 专用 HTTP 客户端：与 B 站的 client 完全分开（工单 §3.6）。 */
    val aiHttp: OkHttpClient = OkHttpClient()
    val settings: AiSettingsStore = AiSettingsStore.get(app)
    val network: NetworkMonitor = NetworkMonitor(app)

    private val dao = BiliSeekDatabase.get(app).aiSummaryDao()
    private val repository = BiliNetwork.get(app).repository
    private val summarizer = Summarizer(aiHttp)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private val root = SummaryFiles.root(app)

    /** 本地识别执行者（AI-8 在 App 启动时注入）；未注入时本地识别任务以「离线识别未就绪」失败。 */
    @Volatile
    var localAsrRunner: LocalAsrRunner? = null

    /** 离线识别模型是否已就绪（AI-8 注入）。 */
    @Volatile
    var asrModelReady: () -> Boolean = { false }

    /** 「已下载」里是否有该 (bvid, cid) 的音频（AI-8 注入）；有则本地识别完全离线。 */
    @Volatile
    var hasLocalAudio: suspend (bvid: String, cid: Long) -> Boolean = { _, _ -> false }

    private val _live = MutableStateFlow<Map<Long, SummaryLive>>(emptyMap())

    /** 当前执行中的行（通知用）。 */
    private val _active = MutableStateFlow<AiSummaryEntity?>(null)
    val active: StateFlow<AiSummaryEntity?> = _active.asStateFlow()

    /** 本次进程内记住的章节与参考信息（续做时若丢失则降级为无章节、无参考）。 */
    private val chaptersById = mutableMapOf<Long, List<Chapter>>()
    private val contextById = mutableMapOf<Long, VideoContext>()

    @Volatile
    private var runningId: Long? = null

    @Volatile
    private var runningJob: Job? = null

    fun files(bvid: String, cid: Long) = SummaryFiles(root, bvid, cid)

    /** [versionId] 为 null 显示最新版本；指定的版本已被删除时也退回最新版本。 */
    fun observe(bvid: String, cid: Long, versionId: Long?): Flow<SummaryUiState?> =
        combine(dao.observeAll(bvid, cid), _live) { rows, live ->
            val id = SummaryVersionPolicy.select(rows.map { it.id }, versionId) ?: return@combine null
            val row = rows.first { it.id == id }
            SummaryUiState(row, live[row.id], rows)
        }

    /** 是否已有生成完成的总结（任一版本）。 */
    fun hasSummary(bvid: String, cid: Long): Flow<Boolean> = dao.observeHasDone(bvid, cid)

    /** 这条视频最新的一个版本。 */
    suspend fun find(bvid: String, cid: Long): AiSummaryEntity? = dao.find(bvid, cid)

    // ── 入口 ──────────────────────────────────────────────

    /**
     * 入队一条总结，**总是新建一个版本**（旧版本原样保留，用户自己决定删哪个）。
     * 视频共用的原文已有就直接复用；只有换了来源（例如之前用字幕、这次改本地识别）才作废重取。
     */
    fun enqueue(video: SummaryVideo, style: StyleChoice, customPrompt: String, source: JobSource) {
        scope.launch {
            val versions = dao.findAll(video.bvid, video.cid)
            val files = files(video.bvid, video.cid)
            val sourceKind = when (source) {
                is JobSource.Subtitle -> AiSummarySource.SUBTITLE
                is JobSource.LocalAsr -> AiSummarySource.LOCAL_ASR
                JobSource.Auto -> AiSummarySource.AUTO
            }
            val transcriptOwner = versions.firstOrNull { it.hasTranscript }
            if (SummaryVersionPolicy.shouldDiscardTranscript(transcriptOwner?.sourceKind, sourceKind)) {
                files.transcript.delete()
                dao.setHasTranscript(video.bvid, video.cid, false)
            }
            val now = System.currentTimeMillis()
            val row = AiSummaryEntity(
                id = 0,
                bvid = video.bvid,
                cid = video.cid,
                title = video.title,
                pageTitle = video.pageTitle,
                author = video.author,
                upMid = video.upMid,
                coverUrl = video.coverUrl,
                durationMs = video.durationMs,
                style = style.name,
                customPrompt = customPrompt,
                sourceKind = sourceKind,
                subtitleUrl = (source as? JobSource.Subtitle)?.track?.subtitleUrl.orEmpty(),
                subtitleLan = (source as? JobSource.Subtitle)?.track?.lan.orEmpty(),
                slot = EndpointSlot.PRIMARY.name,
                models = "",
                status = AiSummaryStatus.QUEUED,
                failureKind = "",
                hasTranscript = files.transcript.exists(),
                error = "",
                createdAt = now,
                updatedAt = now,
            )
            val id = dao.upsert(row)
            val chapters = when (source) {
                is JobSource.Subtitle -> source.chapters
                is JobSource.LocalAsr -> source.chapters
                JobSource.Auto -> emptyList()
            }
            synchronized(chaptersById) { chaptersById[id] = chapters }
            video.context?.let { synchronized(contextById) { contextById[id] = it } }
            settings.setLastStyle(style)
            startService()
        }
    }

    /** 用 B 站官方总结（AI-4b）：不调用大模型，直接写 md。 */
    fun saveOfficial(video: SummaryVideo, result: ConclusionModelResult) {
        scope.launch {
            val versions = dao.findAll(video.bvid, video.cid)
            // 官方总结的内容由 B 站决定，已有官方版本就原地更新，不重复堆版本
            val old = SummaryVersionPolicy.officialVersionToReplace(versions.map { it.id to it.sourceKind })
                ?.let { id -> versions.first { it.id == id } }
            val files = files(video.bvid, video.cid)
            val markdown = OfficialSummaryMarkdownPolicy.markdown(
                title = displayTitle(video.title, video.pageTitle),
                meta = meta(video.bvid, video.author, video.durationMs, video.pageTitle, source = null),
                result = result,
                durationMs = video.durationMs.takeIf { it > 0 },
            )
            val now = System.currentTimeMillis()
            val done = AiSummaryEntity(
                id = old?.id ?: 0,
                bvid = video.bvid, cid = video.cid, title = video.title, pageTitle = video.pageTitle,
                author = video.author, upMid = video.upMid, coverUrl = video.coverUrl,
                durationMs = video.durationMs, style = old?.style ?: StyleChoice.OUTLINE.name,
                customPrompt = old?.customPrompt.orEmpty(), sourceKind = AiSummarySource.OFFICIAL,
                subtitleUrl = "", subtitleLan = "", slot = EndpointSlot.PRIMARY.name, models = "",
                status = AiSummaryStatus.DONE, failureKind = "", hasTranscript = files.transcript.exists(),
                error = "", createdAt = old?.createdAt ?: now, updatedAt = now,
            )
            // 先有 id 才能写文件；文件写好之后才把状态置为 DONE，阅读页不会读到还不存在的正文
            val id = old?.id ?: dao.upsert(done.copy(status = AiSummaryStatus.PREPARING))
            files.writeSummary(id, markdown)
            dao.upsert(done.copy(id = id))
        }
    }

    /** 失败后重试：从断点继续（原文已在就不再取字幕 / 识别）。 */
    fun retry(id: Long) = requeue(id) { it }

    /** 用户确认后改用备用模型继续，沿用已完成的分块（工单 §3.7.5）。 */
    fun switchToBackup(id: Long) = requeue(id) { it.copy(slot = EndpointSlot.BACKUP.name) }

    /** 生成原文笔记（不调用大模型）。需要原文；没有原文时先取原文。 */
    fun makeTranscriptNote(id: Long) = requeue(id) { it.copy(style = StyleChoice.TRANSCRIPT_NOTE.name) }

    fun cancel(id: Long) {
        if (runningId == id) runningJob?.cancel(CancellationException("用户取消"))
        scope.launch {
            dao.byId(id)?.takeIf { it.status != AiSummaryStatus.DONE }?.let {
                dao.upsert(it.failed(FailureKind.CANCELLED, "已取消"))
            }
        }
    }

    /** 删除一个版本。这是最后一个版本时连原文、识别断点等共用文件一起清掉。 */
    fun delete(id: Long) {
        if (runningId == id) runningJob?.cancel(CancellationException("删除"))
        scope.launch {
            val row = dao.byId(id) ?: return@launch
            dao.deleteById(id)
            val files = files(row.bvid, row.cid)
            files.deleteVersion(id)
            if (dao.count(row.bvid, row.cid) == 0) files.deleteAll()
        }
    }

    /** 清除这条视频的全部版本，包括原文与识别断点。 */
    fun deleteAll(bvid: String, cid: Long) {
        scope.launch {
            val ids = dao.findAll(bvid, cid).map { it.id }
            if (runningId in ids) runningJob?.cancel(CancellationException("删除"))
            dao.deleteByVideo(bvid, cid)
            files(bvid, cid).deleteAll()
        }
    }

    /**
     * 多版本之前 `summary.md` / `checkpoint.json` 直接放在视频目录里，现在归各版本自己的目录。
     * 幂等，App 启动时跑一遍（已迁移的什么也不做）。
     */
    private fun migrateLegacyLayout() {
        scope.launch {
            runCatching {
                dao.all().forEach { row ->
                    val files = files(row.bvid, row.cid)
                    SummaryLayoutMigration.moveLegacy(files.dir, files.versionDir(row.id))
                }
            }.onFailure { Log.w(TAG, "legacy layout migration failed: ${it.javaClass.simpleName}") }
        }
    }

    /** App 启动时调用：进程被杀前没做完的任务按断点重新入队。 */
    fun resumePending() {
        migrateLegacyLayout()
        scope.launch {
            val pending = dao.byStatuses(AiSummaryStatus.RESUMABLE)
            if (pending.isEmpty()) return@launch
            pending.forEach { dao.upsert(it.copy(status = AiSummaryStatus.QUEUED)) }
            startService()
        }
    }

    private fun requeue(id: Long, transform: (AiSummaryEntity) -> AiSummaryEntity) {
        scope.launch {
            val row = dao.byId(id) ?: return@launch
            dao.upsert(
                transform(row).copy(
                    status = AiSummaryStatus.QUEUED,
                    failureKind = "",
                    error = "",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            startService()
        }
    }

    private fun startService() {
        ContextCompat.startForegroundService(app, Intent(app, SummaryService::class.java))
    }

    // ── 执行（由 SummaryService 调用）─────────────────────

    /** 串行跑完队列。队列空了通知服务收尾。 */
    suspend fun processQueue(service: SummaryService) {
        if (!queueMutex.tryLock()) return
        try {
            while (true) {
                val row = dao.nextQueued() ?: break
                coroutineScope {
                    val job = launch { runOne(row, service) }
                    runningId = row.id
                    runningJob = job
                    job.join()
                }
                runningId = null
                runningJob = null
                _active.value = null
                _live.update { it - row.id }
            }
        } finally {
            queueMutex.unlock()
            service.finish()
        }
    }

    private suspend fun runOne(initial: AiSummaryEntity, service: SummaryService) {
        var row = initial
        try {
            while (true) {
                when (attempt(row, service)) {
                    Outcome.Done, Outcome.Failed -> return
                    Outcome.WaitNetwork -> {
                        row = update(row) { it.copy(status = AiSummaryStatus.WAITING_NETWORK) }
                        publish(row, SummaryLive("等待网络后自动继续"), service)
                        if (!network.awaitOnline(SummaryNetworkPolicy.MAX_WAIT_NETWORK_MS)) {
                            finishFailed(row, FailureKind.OFFLINE, AiErrorText.describe(AiError.Offline), service)
                            return
                        }
                        row = dao.byId(row.id) ?: return
                    }
                }
            }
        } catch (e: CancellationException) {
            // 取消：断点保留，状态由 cancel()/delete() 写
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "summary failed: ${e.javaClass.simpleName}")
            finishFailed(row, FailureKind.SOURCE, e.message?.take(200) ?: "未知错误", service)
        }
    }

    private enum class Outcome { Done, Failed, WaitNetwork }

    private suspend fun attempt(start: AiSummaryEntity, service: SummaryService): Outcome {
        var row = start
        val style = StyleChoice.entries.firstOrNull { it.name == row.style } ?: StyleChoice.OUTLINE
        val files = files(row.bvid, row.cid)
        _active.value = row

        // 1. 预检（工单 §3.7.3）：在下载音频 / 本地识别之前确认配置可用
        var config: AiEndpointConfig? = null
        if (style.needsLlm) {
            row = update(row) { it.copy(status = AiSummaryStatus.PREPARING) }
            publish(row, SummaryLive("检查 AI 配置"), service)
            val slot = EndpointSlot.valueOf(row.slot)
            config = settings.resolve(slot)
            if (config == null) {
                finishFailed(row, FailureKind.CONFIG, "尚未配置${if (slot == EndpointSlot.BACKUP) "备用" else "总结"}模型，或 Key 需要重新填写", service)
                return Outcome.Failed
            }
            if (!settings.isVerified(config)) {
                if (!network.online.value) return Outcome.WaitNetwork
                val error = AiConnectionTester.test(config, aiHttp)
                if (error != null) return failOrWait(row, error, config, service)
                settings.markVerified(config)
            }
        }

        // 2. 原文：已有就复用（不再打 B 站 / 不再识别）
        if (row.sourceKind == AiSummarySource.AUTO && !files.transcript.exists()) {
            publish(row, SummaryLive("查找字幕"), service)
            if (!network.online.value) return Outcome.WaitNetwork
            val probe = probeSubtitles(repository, row.bvid, row.cid, row.durationMs.takeIf { it > 0 })
            if (probe.failed && !network.online.value) return Outcome.WaitNetwork
            synchronized(chaptersById) { chaptersById[row.id] = probe.chapters }
            val asrReady = localAsrRunner != null && asrModelReady()
            row = when (val source = SummarySourcePolicy.resolve(probe.pick, asrReady, hasLocalAudio(row.bvid, row.cid))) {
                is SummarySource.UseSubtitle -> update(row) {
                    it.copy(
                        sourceKind = AiSummarySource.SUBTITLE,
                        subtitleUrl = source.track.subtitleUrl,
                        subtitleLan = source.track.lan,
                    )
                }
                is SummarySource.UseLocalAsr -> update(row) { it.copy(sourceKind = AiSummarySource.LOCAL_ASR) }
                is SummarySource.NeedsLoginOrAsr -> if (source.asrReady) {
                    update(row) { it.copy(sourceKind = AiSummarySource.LOCAL_ASR) }
                } else {
                    finishFailed(row, FailureKind.SOURCE, "字幕需要登录后才能获取；也可以先下载离线识别模型", service)
                    return Outcome.Failed
                }
                SummarySource.NeedsAsrModel -> {
                    finishFailed(row, FailureKind.SOURCE, "这个视频没有字幕，需要先在设置里下载离线识别模型", service)
                    return Outcome.Failed
                }
            }
        }
        val chapters = synchronized(chaptersById) { chaptersById[row.id] }.orEmpty()
        val transcript = files.readTranscript() ?: run {
            val t = when (row.sourceKind) {
                AiSummarySource.SUBTITLE -> {
                    row = update(row) { it.copy(status = AiSummaryStatus.PREPARING) }
                    publish(row, SummaryLive("获取字幕"), service)
                    if (!network.online.value) return Outcome.WaitNetwork
                    val track = SubtitleTrack(lan = row.subtitleLan, subtitleUrl = row.subtitleUrl)
                    try {
                        BiliSubtitleSource(repository, track, chapters).load()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!network.online.value) return Outcome.WaitNetwork
                        finishFailed(row, FailureKind.SOURCE, "获取字幕失败，请稍后重试", service)
                        return Outcome.Failed
                    }
                }
                AiSummarySource.LOCAL_ASR -> {
                    val runner = localAsrRunner
                    if (runner == null) {
                        finishFailed(row, FailureKind.SOURCE, "离线识别未就绪", service)
                        return Outcome.Failed
                    }
                    row = update(row) { it.copy(status = AiSummaryStatus.TRANSCRIBING) }
                    publish(row, SummaryLive("本地识别"), service)
                    val current = row
                    runner.transcribe(current, chapters, files.dir) { processed, total ->
                        publish(
                            current,
                            SummaryLive(
                                // total 为 0：还在下载音频（时长未知）
                                stage = if (total > 0) {
                                    "本地识别 ${formatClock(processed)} / ${formatClock(total)}"
                                } else {
                                    "下载音频"
                                },
                                fraction = if (total > 0) processed.toFloat() / total else null,
                            ),
                            service,
                        )
                    }
                }
                else -> {
                    finishFailed(row, FailureKind.SOURCE, "没有可用的原文来源", service)
                    return Outcome.Failed
                }
            }
            if (t.segments.isEmpty()) {
                finishFailed(row, FailureKind.SOURCE, "原文为空（没有识别到语音或字幕为空）", service)
                return Outcome.Failed
            }
            files.writeTranscript(t)
            row = update(row) { it.copy(hasTranscript = true) }
            dao.setHasTranscript(row.bvid, row.cid, true) // 原文是各版本共用的
            t
        }

        // 3. 总结
        row = update(row) { it.copy(status = AiSummaryStatus.SUMMARIZING) }
        publish(row, SummaryLive(if (style.needsLlm) "准备总结" else "生成原文笔记"), service)
        if (style.needsLlm && !network.online.value) return Outcome.WaitNetwork
        val request = SummaryRequest(
            title = displayTitle(row.title, row.pageTitle),
            meta = meta(row.bvid, row.author, row.durationMs, row.pageTitle, row.sourceKind),
            style = style.toStyle(row.customPrompt),
            transcript = transcript,
            chat = config ?: AiEndpointConfig("", "", "", ""),
            context = synchronized(contextById) { contextById[row.id] },
            checkpointDir = files.versionDir(row.id),
        )
        var outcome = Outcome.Failed
        summarizer.summarize(request).collect { progress ->
            when (progress) {
                is SummaryProgress.Chunking -> publish(row, SummaryLive("分段总结 0/${progress.total}", 0f), service)
                is SummaryProgress.ChunkDone -> publish(
                    row,
                    SummaryLive("分段总结 ${progress.done}/${progress.total}", progress.done.toFloat() / progress.total),
                    service,
                )
                is SummaryProgress.Streaming -> publish(row, SummaryLive("生成中", null, progress.markdownSoFar), service)
                is SummaryProgress.Done -> {
                    files.writeSummary(row.id, progress.result.markdown)
                    row = update(row) {
                        it.copy(
                            status = AiSummaryStatus.DONE,
                            models = progress.result.models.joinToString("\n"),
                            failureKind = "",
                            error = "",
                        )
                    }
                    service.showResult(row, emptyList())
                    outcome = Outcome.Done
                }
                is SummaryProgress.Failed -> {
                    outcome = failOrWait(row, progress.error, config, service)
                }
            }
        }
        return outcome
    }

    /** 网络类失败且当前离线 → 等待网络；其余按类别落为失败（工单 §3.7.4）。 */
    private suspend fun failOrWait(
        row: AiSummaryEntity,
        error: AiError,
        config: AiEndpointConfig?,
        service: SummaryService,
    ): Outcome {
        val networkLike = error is AiError.Network || error == AiError.StreamInterrupted || error == AiError.Offline
        if (networkLike && !network.online.value) return Outcome.WaitNetwork
        if (error is AiError.Config && config != null) settings.clearVerified(config)
        finishFailed(row, FailureKind.of(error), AiErrorText.describe(error, config?.presetId), service)
        return Outcome.Failed
    }

    private suspend fun finishFailed(row: AiSummaryEntity, kind: FailureKind, message: String, service: SummaryService) {
        val failed = update(row) { it.failed(kind, message) }
        val backupReady = settings.state.value.backupReady || settings.resolve(EndpointSlot.BACKUP) != null
        val actions = FailureActionPolicy.actions(
            kind = kind,
            hasTranscript = failed.hasTranscript,
            hasBackup = backupReady,
            usingBackup = failed.slot == EndpointSlot.BACKUP.name,
            officialAvailable = false,
        )
        service.showResult(failed, actions)
    }

    private suspend fun update(row: AiSummaryEntity, transform: (AiSummaryEntity) -> AiSummaryEntity): AiSummaryEntity {
        val next = transform(row).copy(updatedAt = System.currentTimeMillis())
        dao.upsert(next)
        _active.value = next
        return next
    }

    private fun publish(row: AiSummaryEntity, live: SummaryLive, service: SummaryService) {
        _live.update { it + (row.id to live) }
        service.showProgress(row, live)
    }

    private fun meta(bvid: String, author: String, durationMs: Long, pageTitle: String, source: String?): Map<String, String> =
        linkedMapOf(
            "UP" to author,
            "时长" to if (durationMs > 0) formatClock(durationMs) else "",
            "分P" to pageTitle,
            "链接" to "https://www.bilibili.com/video/$bvid",
            "生成于" to SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date()),
            "来源" to when (source) {
                AiSummarySource.SUBTITLE -> "B 站字幕"
                AiSummarySource.LOCAL_ASR -> "本地语音识别"
                else -> ""
            },
        )

    private fun displayTitle(title: String, pageTitle: String) =
        if (pageTitle.isNotBlank() && pageTitle != title) "$title · $pageTitle" else title

    companion object {
        private const val TAG = "BiliSeekSummary"

        @Volatile
        private var instance: SummaryManager? = null

        fun get(context: Context): SummaryManager =
            instance ?: synchronized(this) {
                instance ?: SummaryManager(context.applicationContext as Application).also { instance = it }
            }

        fun formatClock(ms: Long): String = SummaryMarkdown.timestamp(ms, useHours = ms >= 3_600_000)
    }
}

private fun AiSummaryEntity.failed(kind: FailureKind, message: String) = copy(
    status = AiSummaryStatus.FAILED,
    failureKind = kind.name,
    error = message.take(200),
    updatedAt = System.currentTimeMillis(),
)
