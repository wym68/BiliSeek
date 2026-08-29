package com.biliseek.app.download

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
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
import com.biliseek.app.data.resolveRangeHeader
import com.biliseek.core.network.BiliNetwork
import com.biliseek.core.player.AudioQuality
import com.biliseek.core.player.DownloadSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
) {
    val isDone: Boolean get() = status == DownloadStatus.DONE
    val isFailed: Boolean get() = status == DownloadStatus.FAILED
    val isActive: Boolean get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.QUEUED
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
class DownloadManager private constructor(private val app: Application) {

    private val db = BiliSeekDatabase.get(app)
    private val dao = db.downloadDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
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
    var resolveSource: (suspend (String, Long?, AudioQuality) -> DownloadSource)? = null

    /** 下载档位。由 AppViewModel 注入（读设置）。 */
    var qualityProvider: (() -> AudioQuality)? = null

    /** 当前正在执行的任务，通知栏进度用。 */
    val active: DownloadTask? get() = activeRef.get()
    private val activeRef = AtomicReference<DownloadTask?>(null)

    // ── 入口 ──────────────────────────────────────────────

    /** 入队一个下载。取流解析失败也会落一条 FAILED 记录，让失败可见可重试。 */
    fun enqueue(request: DownloadRequest) {
        scope.launch {
            queueMutex.withLock {
                // 同一 bvid 的旧失败记录先清掉，避免 (bvid, 0) 占着唯一索引
                dao.all().forEach { old ->
                    if (old.bvid == request.bvid && old.status == DownloadStatus.FAILED) {
                        dao.deleteById(old.id)
                    }
                }
                try {
                    val source = requireNotNull(resolveSource) { "下载取流未接线" }
                        .invoke(request.bvid, request.cid, downloadQuality())
                    val cid = source.cid
                    dao.find(request.bvid, cid)?.let { existing ->
                        if (existing.status == DownloadStatus.DONE) deleteFiles(existing)
                    }
                    dao.upsert(
                        entity(
                            request = request,
                            cid = cid,
                            durationSec = (source.durationMs / 1000).coerceAtLeast(0),
                            status = DownloadStatus.QUEUED,
                            error = "",
                        ),
                    )
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "下载取流失败：${e.message}")
                    dao.upsert(
                        entity(
                            request = request,
                            cid = request.cid ?: 0L,
                            durationSec = 0,
                            status = DownloadStatus.FAILED,
                            error = "取流失败：${e.message ?: "未知错误"}",
                        ),
                    )
                }
                refreshTasks()
                startService()
            }
        }
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

    /**
     * 删除一条下载记录。**按位置区分语义**（M9 §9.5 修订）：
     * - 私有目录：删文件 + 删表行（用户删记录就是不要这个文件了）
     * - 公共 / SAF：由 UI 弹窗让用户选——[deleteFile] 为 false 只清记录
     *   （用户自己导出到别处的文件不碰）；为 true 连源文件一起删
     *   （MediaStore 条目 / SAF 文档一并移除）
     */
    fun delete(id: Long, deleteFile: Boolean = false) {
        scope.launch {
            queueMutex.withLock {
                val row = dao.all().firstOrNull { it.id == id } ?: return@withLock
                if (row.location == DownloadLocation.PRIVATE || deleteFile) deleteFiles(row)
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

    /** 由服务调用。串行处理队列，队列空了收尾。
     *
     * 必须整体切到 IO：下载是阻塞式 OkHttp + MediaMuxer，跑在主线程会
     * `NetworkOnMainThreadException`（服务侧 scope 是 Main.immediate）。
     */
    suspend fun processQueue(service: DownloadService) {
        if (!processingRef.compareAndSet(false, true)) return
        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                while (true) {
                    val next = dao.byStatus(DownloadStatus.QUEUED).firstOrNull() ?: break
                    processOne(next, service)
                }
            }
        } finally {
            processingRef.set(false)
            activeRef.set(null)
            refreshTasks()
            // 收尾前再确认一次队列：处理期间可能又有人入队，
            // 若服务在任务还有剩余时停掉，新任务会卡在 QUEUED 等不到下次启动
            if (dao.byStatus(DownloadStatus.QUEUED).isEmpty()) service.finish()
        }
    }

    private suspend fun processOne(row: DownloadEntity, service: DownloadService) {
        dao.upsert(row.copy(status = DownloadStatus.DOWNLOADING, error = ""))
        val running = row.toTask(status = DownloadStatus.DOWNLOADING, progress = 0f)
        activeRef.set(running)
        refreshTasks()
        service.showNotification(running)

        try {
            val source = requireNotNull(resolveSource) { "下载取流未接线" }
                .invoke(row.bvid, row.cid, downloadQuality())
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
            val done = doneRow.toTask(status = DownloadStatus.DONE, progress = 1f)
            activeRef.set(done)
            refreshTasks()
            service.showNotification(done)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "下载失败：${e.message}", e)
            // 不留半成品：mux 失败 / 下载中断都清掉 staging 目录
            stagingRoot(row.bvid, row.cid).deleteRecursively()
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
            activeRef.set(failed)
            refreshTasks()
            service.showNotification(failed)
        }
    }

    /** 单条流（只音频 / 视频轨）：流式写盘 + Range 断点续传。 */
    private suspend fun downloadToFile(
        url: String,
        dest: File,
        row: DownloadEntity,
        service: DownloadService,
    ) {
        dest.parentFile?.mkdirs()
        val existing = dest.length()
        val call = http.newCall(
            Request.Builder()
                .url(url)
                .apply { resolveRangeHeader(existing)?.let { header("Range", it) } }
                .build(),
        )
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("空响应体")
            val append = response.code == 206
            if (!append) dest.delete() // 服务器忽略了 Range，从头写
            val trackTotal = body.contentLength()
            var written = if (append) existing else 0L
            var lastNotify = 0L
            java.io.FileOutputStream(dest, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastNotify >= NOTIFY_INTERVAL_MS) {
                            lastNotify = now
                            val total = if (trackTotal > 0) trackTotal + existing else 0L
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
        activeRef.set(task)
        refreshTasks()
        service.showNotification(task)
    }

    // ── mux：只封装、不重编码 ────────────────────────────

    /** AAC + H.264/HEVC/AV1 都能进 MP4。失败时由调用方删掉半成品并报「可重试」。 */
    private fun muxToMp4(audioFile: File, videoFile: File, outFile: File) {
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val audioTrack = addTrack(muxer, audioFile)
            val videoTrack = addTrack(muxer, videoFile)
            muxer.start()
            copyTrackSamples(muxer, audioFile, audioTrack)
            copyTrackSamples(muxer, videoFile, videoTrack)
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

    private fun copyTrackSamples(muxer: MediaMuxer, file: File, track: Int) {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        extractor.selectTrack(0)
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        val info = MediaCodec.BufferInfo()
        // muxer 要求同轨样本时间戳严格递增，B 站文件偶有重复/倒挂，丢掉即可
        var lastTimeUs = Long.MIN_VALUE
        try {
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val timeUs = extractor.sampleTime
                if (timeUs > lastTimeUs) {
                    info.set(0, size, timeUs, extractor.sampleFlags)
                    muxer.writeSampleData(track, buffer, info)
                    lastTimeUs = timeUs
                }
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
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
        fileSize = fileSize,
    )

    /** 从 DB 全量刷新任务列表。进行中的任务用内存里的实时进度覆盖。 */
    private suspend fun refreshTasks() {
        val active = activeRef.get()
        val rows = dao.all()
        _tasks.value = rows
            .sortedByDescending { it.downloadedAt }
            .map { row ->
                if (active != null && active.id == row.id && row.status == DownloadStatus.DOWNLOADING) {
                    active.copy(
                        id = row.id,
                        title = row.title,
                        content = row.content,
                        location = row.location,
                    )
                } else {
                    row.toTask(row.status, 0f)
                }
            }
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
