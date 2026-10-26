package com.biliseek.app.download

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.biliseek.app.data.BiliSeekDatabase
import com.biliseek.app.data.DownloadContent
import com.biliseek.app.data.DownloadEntity
import com.biliseek.app.data.DownloadLocation
import com.biliseek.app.data.DownloadStatus
import com.biliseek.app.data.downloadFileName
import com.biliseek.app.data.isReplaceableFailedRecord
import com.biliseek.app.data.shouldSkipEnqueue
import com.biliseek.app.data.clampDownloadConcurrency
import com.biliseek.app.data.downloadResolveDelayMs
import com.biliseek.app.data.DOWNLOAD_RESOLVE_MIN_INTERVAL_MS
import com.biliseek.app.data.resolveRangeHeader
import com.biliseek.app.data.MAX_STALLED_RESUMES
import com.biliseek.app.data.contentRangeTotal
import com.biliseek.app.data.grownSampleBufferSize
import com.biliseek.app.data.isPartialStale
import com.biliseek.app.data.nextStalledCount
import com.biliseek.app.data.sampleBufferSize
import com.biliseek.app.data.shouldResumeDownload
import com.biliseek.app.data.shouldWriteSample
import com.biliseek.app.data.MuxPick
import com.biliseek.app.data.nextMuxPick
import com.biliseek.app.data.stagedProgress
import com.biliseek.core.network.BiliNetwork
import com.biliseek.core.player.AudioQuality
import com.biliseek.core.player.DownloadSource
import com.biliseek.core.player.VIDEO_QUALITY_AUTO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** UI 看到的下载任务。来自 DB 行 + 内存里的实时进度。 */
data class DownloadTask(
    val id: Long,
    val bvid: String,
    val cid: Long,
    val title: String,
    val author: String,
    val coverUrl: String,
    val content: String,
    val location: String,
    val status: String,
    /** 0..1。0 表示总量未知（无 Content-Length），进度条转圈。 */
    val progress: Float,
    val error: String,
    val fileSize: Long,
    /** 秒；三点菜单映射成搜索结果时要用。 */
    val durationSec: Long = 0,
) {
    val isDone: Boolean get() = status == DownloadStatus.DONE
    val isFailed: Boolean get() = status == DownloadStatus.FAILED
    val isActive: Boolean get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.QUEUED
    val isPaused: Boolean get() = status == DownloadStatus.PAUSED
}

/** 一次下载请求。cid 可空——取流时会解析出真实 cid。 */
data class DownloadRequest(
    val bvid: String,
    val cid: Long?,
    val title: String,
    val author: String = "",
    val coverUrl: String = "",
    /** 内容：只音频 / 视频 */
    val content: String,
    /** 保存位置：私有 / 公共 / SAF */
    val location: String,
    /** SAF 授权树 uri（location == SAF 时非空） */
    val safTreeUri: String = "",
    /** 下载画质（qn），[VIDEO_QUALITY_AUTO] = 最高一档。只音频时不用。 */
    val videoQn: Int = VIDEO_QUALITY_AUTO,
)

/**
 * 下载管理器：任务队列、断点续传、mux 合成、三处保存位置。
 *
 * **前台服务 [DownloadService] 是唯一的执行容器**——下载是长任务，
 * 播放服务不该被它拖住生命周期（两者的存活条件不同）。本类只管状态与执行，
 * 通知与前台声明由服务负责。
 *
 * 队列语义：同一 (bvid, cid) 只保留一条记录（唯一索引），重新下载即覆盖。
 * 中断恢复：进程被杀后行停留在 QUEUED / DOWNLOADING，App 再启动时
 * [resumePending] 把它们捞回来续传（.part 文件 + Range 头）。
 */
/** HTTP 状态不对（403 / 404 …）：不是传输中断，不续传。 */
private class HttpStatusException(code: Int) : IOException("HTTP $code")

class DownloadManager private constructor(private val app: Application) {

    private val db = BiliSeekDatabase.get(app)
    private val dao = db.downloadDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    /** 下载取流的节流：同一时刻只取一条，两次之间至少隔 [DOWNLOAD_RESOLVE_MIN_INTERVAL_MS]。 */
    private val resolveMutex = Mutex()
    /** 正在执行的任务（id → 子协程），取消下载用。 */
    private val runningJobs = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    /** 正在暂停的任务：子协程被取消时据此标「暂停」、留半成品，而不是当成删除清掉。 */
    private val pausing: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var lastResolveAt: Long? = null
    private val processingRef = AtomicReference(false)

    private val http = OkHttpClient.Builder()
        .apply {
            BiliNetwork.get(app).mediaRequestHeaders.forEach { (name, value) ->
                addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header(name, value).build())
                }
            }
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    /** 取流直链。由 AppViewModel 注入（挂在 PlayerConnection 上，同一份解析器）。 */
    var resolveSource: (suspend (String, Long?, AudioQuality, Int) -> DownloadSource)? = null

    /** 下载档位。由 AppViewModel 注入（读设置）。 */
    var qualityProvider: (() -> AudioQuality)? = null

    /** 当前正在执行的任务，通知栏进度用。 */
    val active: DownloadTask? get() = activeTasks.values.firstOrNull()

    /** 正在执行的任务（id → 带实时进度的任务）。并发下载时可能有多条。 */
    private val activeTasks = java.util.concurrent.ConcurrentHashMap<Long, DownloadTask>()

    /** 同时下载数。由 AppViewModel 注入（读设置），每轮开工时读一次。 */
    var concurrencyProvider: (() -> Int)? = null

    // ── 入口 ──────────────────────────────────────────────

    /**
     * 入队一个下载。**入队时不取流**：只落一条 QUEUED 记录，轮到它时（[processOne]）才取直链——
     * 批量入队 20 个分 P 不会瞬间打出 20 次 playurl，直链也不会在排队期间过期。
     * cid 未知（个别入口拿不到分 P）时先记 0，取流时再换成真实 cid。
     */
    fun enqueue(request: DownloadRequest) = enqueueAll(listOf(request))

    /** 批量入队：在同一个协程里按给定顺序写入，P1、P2、P3 的下载顺序与选择顺序一致。 */
    fun enqueueAll(requests: List<DownloadRequest>) {
        if (requests.isEmpty()) return
        scope.launch {
            queueMutex.withLock {
                requests.forEach { enqueueLocked(it) }
                refreshTasks()
                startService()
            }
        }
    }

    /** 入队一条（调用方持有 [queueMutex]）。 */
    private suspend fun enqueueLocked(request: DownloadRequest) {
        // 只清这次要顶替的旧失败记录（同一 P，或分 P 未知的占位），别的 P 的失败记录保留
        dao.all().forEach { old ->
            if (isReplaceableFailedRecord(old.bvid, old.cid, old.status, request.bvid, request.cid)) {
                dao.deleteById(old.id)
            }
        }
        val cid = request.cid?.takeIf { it > 0 } ?: 0L
        val existing = dao.find(request.bvid, cid)
        if (shouldSkipEnqueue(existing?.status)) return
        // 已完成的视为重新下载：旧文件先删，免得留下孤儿文件
        if (existing?.status == DownloadStatus.DONE) deleteFiles(existing)
        dao.upsert(
            entity(
                request = request,
                cid = cid,
                durationSec = 0,
                status = DownloadStatus.QUEUED,
                error = "",
            ),
        )
    }

    /** 全部失败的下载重新排队（「已下载」页失败段的「全部重试」）。 */
    fun retryAllFailed() {
        scope.launch {
            queueMutex.withLock {
                val failed = dao.byStatus(DownloadStatus.FAILED)
                if (failed.isEmpty()) return@withLock
                failed.forEach { dao.upsert(it.copy(status = DownloadStatus.QUEUED, error = "")) }
                refreshTasks()
                startService()
            }
        }
    }

    /** 清除全部失败记录。失败的任务没有成品文件，删记录和留着续传用的半成品。 */
    fun clearFailed() {
        scope.launch {
            queueMutex.withLock {
                dao.byStatus(DownloadStatus.FAILED).forEach {
                    deleteStaging(it)
                    dao.deleteById(it.id)
                }
                refreshTasks()
            }
        }
    }

    /** 没下完的记录留着续传用的半成品（cacheDir 里），删记录时一起清。 */
    private fun deleteStaging(row: DownloadEntity) {
        if (row.status != DownloadStatus.DONE) stagingRoot(row.bvid, row.cid).deleteRecursively()
    }

    /** 重试一条失败 / 中断的下载。 */
    fun retry(id: Long) {
        scope.launch {
            queueMutex.withLock {
                val row = dao.all().firstOrNull { it.id == id }
                    ?: return@withLock
                if (row.status != DownloadStatus.FAILED) return@withLock
                dao.upsert(row.copy(status = DownloadStatus.QUEUED, error = ""))
                refreshTasks()
                startService()
            }
        }
    }

    // ── 暂停 / 继续 / 全部取消（设计稿：2026-10-04-download-improvements）──────────

    /** 暂停一条：在下的中断传输、半成品留着（标暂停在子协程收尾时做，见 processOne）；排队的直接标暂停。 */
    fun pause(id: Long) {
        scope.launch { queueMutex.withLock { pauseLocked(id) } }
    }

    /** 全部暂停：在下的和排队的都暂停。 */
    fun pauseAll() {
        scope.launch {
            queueMutex.withLock {
                dao.all().filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
                    .forEach { pauseLocked(it.id) }
            }
        }
    }

    private suspend fun pauseLocked(id: Long) {
        val row = dao.all().firstOrNull { it.id == id } ?: return
        val job = runningJobs[id]
        if (job != null) {
            pausing += id
            job.cancel()
        } else if (row.status == DownloadStatus.QUEUED || row.status == DownloadStatus.DOWNLOADING) {
            dao.upsert(row.copy(status = DownloadStatus.PAUSED, error = ""))
            refreshTasks()
        }
    }

    /** 继续一条暂停的：改回排队，轮到时从断点接着下。 */
    fun resume(id: Long) {
        scope.launch {
            queueMutex.withLock {
                val row = dao.all().firstOrNull { it.id == id } ?: return@withLock
                if (row.status != DownloadStatus.PAUSED) return@withLock
                dao.upsert(row.copy(status = DownloadStatus.QUEUED, error = ""))
                refreshTasks()
                startService()
            }
        }
    }

    /** 全部继续。 */
    fun resumeAll() {
        scope.launch {
            queueMutex.withLock {
                val paused = dao.byStatus(DownloadStatus.PAUSED)
                if (paused.isEmpty()) return@withLock
                paused.forEach { dao.upsert(it.copy(status = DownloadStatus.QUEUED, error = "")) }
                refreshTasks()
                startService()
            }
        }
    }

    /** 全部取消：在下、排队、暂停的记录连同半成品一起删掉（界面先确认）。 */
    fun cancelAll() {
        scope.launch {
            queueMutex.withLock {
                val pending = dao.all().filter {
                    it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED ||
                        it.status == DownloadStatus.PAUSED
                }
                pending.forEach { row ->
                    pausing -= row.id
                    runningJobs[row.id]?.cancel()
                    deleteStaging(row)
                    dao.deleteById(row.id)
                }
                refreshTasks()
            }
        }
    }

    /**
     * 删除一条下载记录。**按位置区分语义**（M9 §9.5 修订）：
     * - 私有目录：删文件 + 删表行（用户删记录就是不要这个文件了）
     * - 公共 / SAF：由 UI 弹窗让用户选——[deleteFile] 为 false 只清记录
     *   （用户自己导出到别处的文件不碰）；为 true 连源文件一起删
     *   （MediaStore 条目 / SAF 文档一并移除）
     */
    fun delete(id: Long, deleteFile: Boolean = false) {
        // 正在下载：先中断传输（子协程收到取消后自己清半成品）
        runningJobs[id]?.cancel()
        scope.launch {
            queueMutex.withLock {
                val row = dao.all().firstOrNull { it.id == id } ?: return@withLock
                if (row.location == DownloadLocation.PRIVATE || deleteFile) deleteFiles(row)
                deleteStaging(row)
                dao.deleteById(id)
                refreshTasks()
            }
        }
    }

    /**
     * App 启动时调用：把中断的任务捞回队列。
     *
     * DOWNLOADING 说明进程被杀了——.part 文件还在，直接续传；
     * QUEUED 说明没轮到，继续排着。
     */
    suspend fun resumePending() {
        queueMutex.withLock {
            dao.byStatus(DownloadStatus.DOWNLOADING).forEach {
                dao.upsert(it.copy(status = DownloadStatus.QUEUED))
            }
            refreshTasks()
            if (dao.byStatus(DownloadStatus.QUEUED).isNotEmpty()) startService()
        }
    }

    // ── 执行 ──────────────────────────────────────────────

    /**
     * 由服务调用。按设置开 1..3 个工位并行处理队列，队列空了收尾。
     *
     * 每个工位在锁内领取下一条排队任务（[claimNext]），领到即标为下载中，两个工位不会领到同一条。
     * 所有工位收工后再看一眼队列：期间新入队、而工位已经退出的任务接着开一轮处理。
     *
     * 必须整体切到 IO：下载是阻塞式 OkHttp + MediaMuxer，跑在主线程会
     * `NetworkOnMainThreadException`（服务侧 scope 是 Main.immediate）。
     */
    suspend fun processQueue(service: DownloadService) {
        if (!processingRef.compareAndSet(false, true)) return
        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                while (true) {
                    val workers = clampDownloadConcurrency(concurrencyProvider?.invoke() ?: 1)
                    kotlinx.coroutines.coroutineScope {
                        repeat(workers) { launch { workerLoop(service) } }
                    }
                    if (dao.byStatus(DownloadStatus.QUEUED).isEmpty()) break
                }
            }
        } finally {
            processingRef.set(false)
            activeTasks.clear()
            refreshTasks()
            // 收尾前再确认一次队列：处理期间可能又有人入队，
            // 若服务在任务还有剩余时停掉，新任务会卡在 QUEUED 等不到下次启动
            if (dao.byStatus(DownloadStatus.QUEUED).isEmpty()) service.finish()
        }
    }

    /** 一个工位：领一条做一条，领不到就收工。 */
    private suspend fun workerLoop(service: DownloadService) {
        while (true) {
            val next = claimNext() ?: return
            runCancellable(next, service)
        }
    }

    /** 在锁内领取下一条排队任务并标为下载中。 */
    private suspend fun claimNext(): DownloadEntity? = queueMutex.withLock {
        val next = dao.byStatus(DownloadStatus.QUEUED).firstOrNull() ?: return@withLock null
        dao.upsert(next.copy(status = DownloadStatus.DOWNLOADING, error = ""))
        next
    }

    /**
     * 每条任务跑在自己的子协程里，登记到 [runningJobs]，[delete] 取消下载中的任务时据此中断传输。
     * 子协程被取消不会连带取消队列循环（join 正常返回），队列接着处理下一条。
     */
    private suspend fun runCancellable(row: DownloadEntity, service: DownloadService) {
        kotlinx.coroutines.coroutineScope {
            val job = launch { processOne(row, service) }
            runningJobs[row.id] = job
            try {
                job.join()
            } finally {
                runningJobs.remove(row.id)
                activeTasks.remove(row.id)
                // 点暂停时任务恰好已经下完：留下的暂停标记作废
                pausing.remove(row.id)
            }
        }
    }

    private suspend fun processOne(queued: DownloadEntity, service: DownloadService) {
        var row = queued
        dao.upsert(row.copy(status = DownloadStatus.DOWNLOADING, error = ""))
        val running = row.toTask(status = DownloadStatus.DOWNLOADING, progress = 0f)
        activeTasks[row.id] = running
        refreshTasks()
        notify(service, running)

        try {
            val source = resolveThrottled(row)
            // 排队取流期间被取消（表行已删）：到此为止，不能再写回
            if (dao.find(queued.bvid, queued.cid)?.id != queued.id) return
            if (row.cid != source.cid) {
                // 入队时 cid 未知（记的 0）：换成真实 cid；同一 P 已有别的记录时按情况让路
                row = adoptResolvedCid(row, source.cid) ?: return
            }
            if (row.durationSec <= 0) row = row.copy(durationSec = (source.durationMs / 1000).coerceAtLeast(0))
            dao.upsert(row.copy(status = DownloadStatus.DOWNLOADING, error = ""))
            val fileName = downloadFileName(row.bvid, row.cid, row.content)
            val staging = stagingFile(row.bvid, row.cid, row.content)
            val playUri = when (row.content) {
                DownloadContent.AUDIO -> {
                    downloadToFile(source.audioUrl, staging, row, service)
                    writeToDestination(row, staging, fileName)
                }
                else -> {
                    val videoUrl = requireNotNull(source.videoUrl) { "该内容没有视频轨" }
                    downloadAndMux(source.audioUrl, videoUrl, row, service)
                    writeToDestination(row, staging, fileName)
                }
            }

            val size = fileSizeOf(playUri)
            // 下载期间用户可能已取消（删了表行）：刚落的文件一起清掉，不写回——
            // 否则会出现「点了取消，任务照旧完成并重新出现」的假象
            if (dao.find(row.bvid, row.cid) == null) {
                deleteFileByUri(playUri)
                return
            }
            val doneRow = if (row.content == DownloadContent.VIDEO) {
                row.copy(
                    status = DownloadStatus.DONE,
                    error = "",
                    videoFile = playUri.toString(),
                    fileSize = size,
                    downloadedAt = System.currentTimeMillis(),
                )
            } else {
                row.copy(
                    status = DownloadStatus.DONE,
                    error = "",
                    audioFile = playUri.toString(),
                    fileSize = size,
                    downloadedAt = System.currentTimeMillis(),
                )
            }
            dao.upsert(doneRow)
            // 半成品旁边记总长度的 .total 等零碎文件一起清掉
            stagingRoot(row.bvid, row.cid).deleteRecursively()
            val done = doneRow.toTask(status = DownloadStatus.DONE, progress = 1f)
            activeTasks.remove(row.id)
            refreshTasks()
            notify(service, done)
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (pausing.remove(row.id)) {
                // 暂停：半成品留着，继续时从断点接着下。协程已被取消，写库要放在 NonCancellable 里
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    queueMutex.withLock {
                        if (dao.find(row.bvid, row.cid)?.id == row.id) {
                            dao.upsert(row.copy(status = DownloadStatus.PAUSED, error = ""))
                        }
                    }
                    activeTasks.remove(row.id)
                    refreshTasks()
                }
            } else {
                // 用户取消：表行已由 delete() 删掉，这里只清半成品，不标失败
                stagingRoot(row.bvid, row.cid).deleteRecursively()
            }
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "下载失败：${e.message}", e)
            // 半成品留着：点「重试」时从断点接着下（总长度对不上会自动从头下，见 transferOnce）；
            // 删除记录 / 清除失败记录时再清（deleteStaging）
            val failedRow = row.copy(
                status = DownloadStatus.FAILED,
                error = "下载失败：${e.message ?: "未知错误"}",
            )
            dao.upsert(failedRow)
            val failed = failedRow.toTask(
                status = DownloadStatus.FAILED,
                progress = 0f,
                error = failedRow.error,
            )
            activeTasks.remove(row.id)
            refreshTasks()
            notify(service, failed)
        }
    }

    /**
     * 下载取流，按 [DOWNLOAD_RESOLVE_MIN_INTERVAL_MS] 节流（[downloadResolveDelayMs]）。
     * 取流失败按普通下载失败处理（记录标 FAILED、可重试），不重试、不连发。
     */
    private suspend fun resolveThrottled(row: DownloadEntity): DownloadSource = resolveMutex.withLock {
        val wait = downloadResolveDelayMs(lastResolveAt, System.currentTimeMillis())
        if (wait > 0) kotlinx.coroutines.delay(wait)
        try {
            requireNotNull(resolveSource) { "下载取流未接线" }
                .invoke(row.bvid, row.cid.takeIf { it > 0 }, downloadQuality(), row.videoQn)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("取流失败：${e.message ?: "未知错误"}", e)
        } finally {
            lastResolveAt = System.currentTimeMillis()
        }
    }

    /**
     * 入队时 cid 未知的记录换成真实 cid。同一 P 已有别的记录时：
     * 正在下载 → 本条让路（删掉本条，返回 null，不重复下）；已完成 → 视为重新下载，旧文件与旧记录删掉；
     * 排队 / 失败 → 旧记录删掉，由本条接手。
     */
    private suspend fun adoptResolvedCid(row: DownloadEntity, cid: Long): DownloadEntity? = queueMutex.withLock {
        val other = dao.find(row.bvid, cid)
        if (other != null && other.id != row.id) {
            if (other.status == DownloadStatus.DOWNLOADING) {
                dao.deleteById(row.id)
                refreshTasks()
                return@withLock null
            }
            if (other.status == DownloadStatus.DONE) deleteFiles(other)
            dao.deleteById(other.id)
        }
        row.copy(cid = cid)
    }

    /**
     * 单条流（只音频 / 视频轨）：流式写盘 + Range 断点续传。
     *
     * 下到一半连接被断开（视频文件大，常见 `unexpected end of stream`）时，用同一个地址从已写的位置接着下，
     * 连续 [MAX_STALLED_RESUMES] 次没有新数据才算失败；HTTP 状态错误（403 / 404 …）直接失败
     * （设计稿：2026-10-04-download-improvements）。不重新请求取流接口。
     */
    private suspend fun downloadToFile(
        url: String,
        dest: File,
        row: DownloadEntity,
        service: DownloadService,
    ) {
        dest.parentFile?.mkdirs()
        var stalled = 0
        while (true) {
            val before = dest.length()
            try {
                transferOnce(url, dest, row, service)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                stalled = nextStalledCount(stalled, madeProgress = dest.length() > before)
                if (!shouldResumeDownload(e is HttpStatusException, stalled)) throw e
                android.util.Log.w(TAG, "下载中断，从 ${dest.length()} 字节接着下：${e.message}")
            }
        }
    }

    /**
     * 一次传输：有半成品就带 Range 接着写。第一次拿到总长度时记在半成品旁边（`.total`），
     * 续传（断线、暂停后继续、进程被杀后恢复）时总长度对不上就说明换了文件，丢掉半成品从头下。
     */
    private suspend fun transferOnce(
        url: String,
        dest: File,
        row: DownloadEntity,
        service: DownloadService,
    ) {
        val totalFile = File(dest.path + ".total")
        val recordedTotal = totalFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        val existing = dest.length()
        val call = http.newCall(
            Request.Builder()
                .url(url)
                .apply { resolveRangeHeader(existing)?.let { header("Range", it) } }
                .build(),
        )
        call.execute().use { response ->
            if (response.code == 416 && existing > 0) {
                // 续传位置已到文件末尾：之前其实已经下全了
                if (recordedTotal != null && existing >= recordedTotal) return
                dest.delete()
                totalFile.delete()
                throw IOException("续传位置不对，从头下载")
            }
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val body = response.body
            val append = response.code == 206
            val responseTotal = if (append) {
                contentRangeTotal(response.header("Content-Range"))
            } else {
                body.contentLength().takeIf { it > 0 }
            }
            if (append && isPartialStale(recordedTotal, responseTotal)) {
                dest.delete()
                totalFile.delete()
                throw IOException("文件变了，从头下载")
            }
            if (!append) dest.delete() // 服务器忽略了 Range，从头写
            responseTotal?.let { totalFile.writeText(it.toString()) }
            var written = if (append) existing else 0L
            var lastNotify = 0L
            java.io.FileOutputStream(dest, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        // 取消下载：每读一块检查一次，数据在流动时几毫秒内就能停下
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastNotify >= NOTIFY_INTERVAL_MS) {
                            lastNotify = now
                            val total = responseTotal ?: 0L
                            val progress = if (total > 0) written.toFloat() / total else 0f
                            notifyProgress(row, progress.coerceIn(0f, 1f), service)
                        }
                    }
                }
            }
        }
    }

    /** 视频：音频轨 + 视频轨顺序下载，mux 成 MP4。 */
    private suspend fun downloadAndMux(
        audioUrl: String,
        videoUrl: String,
        row: DownloadEntity,
        service: DownloadService,
    ) {
        val stagingAudio = stagingPart(row.bvid, row.cid, "audio")
        val stagingVideo = stagingPart(row.bvid, row.cid, "video")
        val stagingMux = stagingFile(row.bvid, row.cid, row.content)
        // 两条轨各按自身进度折算（串行下载，避免并行时的写盘竞态）。
        // 进度是给用户看的，轨道间不必精确对齐。
        downloadToFile(audioUrl, stagingAudio, row, service)
        downloadToFile(videoUrl, stagingVideo, row, service)
        stagingMux.parentFile?.mkdirs()
        muxToMp4(stagingAudio, stagingVideo, stagingMux)
        // mux 成功才删两条半成品；失败时由 processOne 统一清理 staging 目录
        stagingAudio.delete()
        stagingVideo.delete()
    }

    private suspend fun notifyProgress(row: DownloadEntity, progress: Float, service: DownloadService) {
        val task = row.toTask(status = DownloadStatus.DOWNLOADING, progress = progress)
        activeTasks[row.id] = task
        refreshTasks()
        notify(service, task)
    }

    // ── mux：只封装、不重编码 ────────────────────────────

    /**
     * AAC + H.264/HEVC/AV1 都能进 MP4。失败时由调用方删掉半成品并报「可重试」。
     * 两条轨按时间交错写（[nextMuxPick]），文件里音视频交错存放。
     */
    private fun muxToMp4(audioFile: File, videoFile: File, outFile: File) {
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val audioTrack = addTrack(muxer, audioFile)
            val videoTrack = addTrack(muxer, videoFile)
            muxer.start()
            TrackSamples(audioFile, audioTrack).use { audio ->
                TrackSamples(videoFile, videoTrack).use { video ->
                    while (true) {
                        when (nextMuxPick(audio.nextTimeUs, video.nextTimeUs)) {
                            MuxPick.AUDIO -> audio.writeNext(muxer)
                            MuxPick.VIDEO -> video.writeNext(muxer)
                            null -> break
                        }
                    }
                }
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
    }

    private fun addTrack(muxer: MediaMuxer, file: File): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return muxer.addTrack(extractor.getTrackFormat(0))
        } finally {
            extractor.release()
        }
    }

    /** 一条轨的样本读取器：按解码顺序一帧一帧写进 muxer 的 [track]。 */
    private class TrackSamples(file: File, private val track: Int) : AutoCloseable {
        private val extractor = MediaExtractor().apply {
            setDataSource(file.absolutePath)
            selectTrack(0)
        }
        private val isVideo: Boolean
        private var buffer: ByteBuffer
        private val info = MediaCodec.BufferInfo()
        private var lastTimeUs = Long.MIN_VALUE

        init {
            // 高码率视频的关键帧常超过 64KB：按轨道的最大帧大小分配，至少 1MB（sampleBufferSize）
            val format = extractor.getTrackFormat(0)
            val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                null
            }
            buffer = ByteBuffer.allocate(sampleBufferSize(maxInput))
            isVideo = format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }

        /** 下一帧的时间戳；读完了为 null。 */
        val nextTimeUs: Long? get() = extractor.sampleTime.takeIf { it >= 0 }

        /** 写下一帧并前进一帧。 */
        fun writeNext(muxer: MediaMuxer) {
            val size = readSample()
            val timeUs = extractor.sampleTime
            // 读不出数据（文件尾坏帧）：跳过它，不然 nextTimeUs 不变会原地打转
            if (size >= 0 && shouldWriteSample(isVideo, timeUs, lastTimeUs)) {
                info.set(0, size, timeUs, extractor.sampleFlags)
                muxer.writeSampleData(track, buffer, info)
                lastTimeUs = timeUs
            }
            extractor.advance()
        }

        private fun readSample(): Int {
            while (true) {
                try {
                    return extractor.readSampleData(buffer, 0)
                } catch (e: IllegalArgumentException) {
                    // 这一帧比缓冲区还大：翻倍后重读同一帧（读失败时 extractor 不前进）
                    buffer = ByteBuffer.allocate(grownSampleBufferSize(buffer.capacity()) ?: throw e)
                }
            }
        }

        override fun close() = extractor.release()
    }

    // ── 保存位置 ──────────────────────────────────────────

    /** 三处位置的统一写入口。返回播放用的 URI。 */
    private fun writeToDestination(row: DownloadEntity, staging: File, fileName: String): Uri =
        when (row.location) {
            DownloadLocation.PRIVATE -> writePrivate(staging, fileName)
            DownloadLocation.PUBLIC -> writePublic(staging, fileName, isVideo = row.content == DownloadContent.VIDEO)
            DownloadLocation.SAF -> writeSaf(staging, fileName, row.safTreeUri)
            else -> writePrivate(staging, fileName)
        }

    private fun writePrivate(staging: File, fileName: String): Uri {
        val dir = File(app.filesDir, "downloads").apply { mkdirs() }
        val target = File(dir, fileName)
        staging.copyTo(target, overwrite = true)
        staging.delete()
        return Uri.fromFile(target)
    }

    private fun writePublic(staging: File, fileName: String, isVideo: Boolean): Uri {
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val relative = (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC) +
            "/BiliSeek/"
        val resolver = app.contentResolver
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // 重新下载同一内容时替换上一次的同名文件（MediaStore 同名会自动加「(1)」）
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(fileName),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    resolver.delete(
                        android.content.ContentUris.withAppendedId(collection, id),
                        null,
                        null,
                    )
                }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "audio/mp4")
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore 写入失败")
        if (android.os.Build.VERSION.SDK_INT < 29) {
            // API 26–28：没有 RELATIVE_PATH，写公开目录需要 WRITE_EXTERNAL_STORAGE
            val dir = File(
                Environment.getExternalStoragePublicDirectory(
                    if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC,
                ),
                "BiliSeek",
            ).apply { mkdirs() }
            val target = File(dir, fileName)
            staging.copyTo(target, overwrite = true)
            staging.delete()
        } else {
            resolver.openOutputStream(uri, "w")?.use { out ->
                staging.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("无法打开 MediaStore 输出流")
            staging.delete()
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        return uri
    }

    private fun writeSaf(staging: File, fileName: String, treeUri: String): Uri {
        require(treeUri.isNotBlank()) { "SAF 目录未授权" }
        val root = DocumentFile.fromTreeUri(app, Uri.parse(treeUri))
            ?: throw IOException("SAF 目录不可用")
        val mime = if (fileName.endsWith(".mp4")) "video/mp4" else "audio/mp4"
        // 重新下载同一内容时替换上一次的同名文件——这是「替换我们自己的下载」，
        // 不是动用户别的文件。不删的话 createFile 会生成「xxx (1).m4a」的重复文件
        root.findFile(fileName)?.delete()
        val doc = root.createFile(mime, fileName) ?: throw IOException("无法创建目标文件")
        app.contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
            staging.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("无法打开 SAF 输出流")
        staging.delete()
        return doc.uri
    }

    // ── 图片保存 ──────────────────────────────────────────

    /**
     * 保存图片到系统相册（Pictures/BiliSeek/）。
     * API 29+ 走 MediaStore 无需权限；API 26–28 需 WRITE_EXTERNAL_STORAGE（manifest 已声明）。
     */
    suspend fun saveImage(url: String): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = "BiliSeek_${System.currentTimeMillis()}.jpg"
            // 1. 下载到临时文件
            val request = okhttp3.Request.Builder().url(url).build()
            val staging = File(app.cacheDir, "image_staging/$fileName").apply { parentFile?.mkdirs() }
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                resp.body?.byteStream()?.use { input ->
                    staging.outputStream().use { out -> input.copyTo(out) }
                } ?: throw IOException("空响应体")
            }
            // 2. 写入 MediaStore.Images
            writeImageToPublic(staging, fileName)
        }
    }

    private fun writeImageToPublic(staging: File, fileName: String): Uri {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val relative = Environment.DIRECTORY_PICTURES + "/BiliSeek/"
        val resolver = app.contentResolver
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            resolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(fileName), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    resolver.delete(
                        android.content.ContentUris.withAppendedId(collection, c.getLong(0)), null, null,
                    )
                }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore 写入失败")
        if (android.os.Build.VERSION.SDK_INT < 29) {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "BiliSeek").apply { mkdirs() }
            val target = File(dir, fileName)
            staging.copyTo(target, overwrite = true)
            staging.delete()
        } else {
            resolver.openOutputStream(uri, "w")?.use { out -> staging.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("无法打开 MediaStore 输出流")
            staging.delete()
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    // ── 工具 ──────────────────────────────────────────────

    private fun downloadQuality(): AudioQuality {
        val s = qualityProvider?.invoke() ?: return AudioQuality.AUTO
        // 下载不做无损：SAVER 小文件，其余一律最高码率 AAC
        return if (s == AudioQuality.SAVER) AudioQuality.SAVER else AudioQuality.AUTO
    }

    private fun stagingFile(bvid: String, cid: Long, content: String): File =
        File(stagingRoot(bvid, cid), downloadFileName(bvid, cid, content))

    private fun stagingPart(bvid: String, cid: Long, kind: String): File =
        File(stagingRoot(bvid, cid), "$kind.part")

    private fun stagingRoot(bvid: String, cid: Long): File =
        File(app.cacheDir, "downloads/$bvid/$cid")

    private fun entity(
        request: DownloadRequest,
        cid: Long,
        durationSec: Long,
        status: String,
        error: String,
    ) = DownloadEntity(
        bvid = request.bvid,
        cid = cid,
        title = request.title,
        author = request.author,
        coverUrl = request.coverUrl,
        durationSec = durationSec,
        content = request.content,
        location = request.location,
        safTreeUri = request.safTreeUri,
        audioFile = "",
        videoFile = "",
        fileSize = 0,
        status = status,
        error = error,
        downloadedAt = System.currentTimeMillis(),
        videoQn = request.videoQn,
    )

    private fun deleteFileByUri(uri: Uri) {
        when (uri.scheme) {
            "file" -> runCatching { File(uri.path.orEmpty()).delete() }
            "content" -> runCatching {
                // SAF 文档 URI 走 DocumentsProvider 的 deleteDocument；
                // MediaStore 条目直接 contentResolver.delete（系统顺带删文件）
                if (DocumentsContract.isDocumentUri(app, uri)) {
                    DocumentsContract.deleteDocument(app.contentResolver, uri)
                } else {
                    app.contentResolver.delete(uri, null, null)
                }
            }
        }
    }

    /** 删掉一条记录关联的所有源文件（file:// 与 content:// 都支持）。 */
    private fun deleteFiles(row: DownloadEntity) {
        listOf(row.audioFile, row.videoFile)
            .filter { it.isNotBlank() }
            .forEach { uriString ->
                deleteFileByUri(Uri.parse(uriString))
            }
    }

    private fun fileSizeOf(uri: Uri): Long = when (uri.scheme) {
        "file" -> uri.path?.let { File(it).length() } ?: 0L
        else -> runCatching {
            app.contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: 0L
        }.getOrDefault(0L)
    }

    private fun DownloadEntity.toTask(
        status: String,
        progress: Float,
        fileSize: Long = this.fileSize,
        error: String = this.error,
    ) = DownloadTask(
        id = id,
        bvid = bvid,
        cid = cid,
        title = title,
        author = author,
        coverUrl = coverUrl,
        content = content,
        location = location,
        status = status,
        progress = progress,
        error = error,
        durationSec = durationSec,
        fileSize = fileSize,
    )

    /**
     * 更新通知。只有一条在下载时显示它本身；多条并行时合成一条「正在下载 N 项」+ 平均进度，
     * 免得几个任务轮流刷同一条通知、标题来回跳。
     */
    private fun notify(service: DownloadService, latest: DownloadTask) {
        val running = activeTasks.values.filter { it.status == DownloadStatus.DOWNLOADING }
        val shown = if (running.size <= 1) {
            latest
        } else {
            latest.copy(
                title = "正在下载 ${running.size} 项",
                content = "",
                location = "",
                status = DownloadStatus.DOWNLOADING,
                progress = running.map { it.progress }.average().toFloat(),
            )
        }
        service.showNotification(shown)
    }

    /** 从 DB 全量刷新任务列表。进行中的任务用内存里的实时进度覆盖。 */
    private suspend fun refreshTasks() {
        val rows = dao.all()
        _tasks.value = rows
            .sortedByDescending { it.downloadedAt }
            .map { row ->
                val active = activeTasks[row.id]
                if (active != null && row.status == DownloadStatus.DOWNLOADING) {
                    active.copy(
                        id = row.id,
                        title = row.title,
                        content = row.content,
                        location = row.location,
                    )
                } else if (row.status == DownloadStatus.PAUSED) {
                    row.toTask(row.status, pausedProgress(row))
                } else {
                    row.toTask(row.status, 0f)
                }
            }
    }

    /** 暂停的任务按半成品算进度（「已暂停 · 45%」）：只音频一段，视频是音轨、视频轨两段。 */
    private fun pausedProgress(row: DownloadEntity): Float {
        val files = if (row.content == DownloadContent.VIDEO) {
            listOf(stagingPart(row.bvid, row.cid, "audio"), stagingPart(row.bvid, row.cid, "video"))
        } else {
            listOf(stagingFile(row.bvid, row.cid, row.content))
        }
        return stagedProgress(
            files.map { f ->
                f.length() to File(f.path + ".total").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
            },
        )
    }

    private fun startService() {
        runCatching {
            val intent = Intent(app, DownloadService::class.java)
            ContextCompat.startForegroundService(app, intent)
        }.onFailure { android.util.Log.w(TAG, "启动下载服务失败：${it.message}") }
    }

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "BiliSeekDownload"
        private const val BUFFER_SIZE = 64 * 1024
        private const val NOTIFY_INTERVAL_MS = 800L

        @Volatile
        private var instance: DownloadManager? = null

        fun get(context: Context): DownloadManager =
            instance ?: synchronized(this) {
                instance ?: DownloadManager(context.applicationContext as Application).also {
                    instance = it
                }
            }
    }
}
