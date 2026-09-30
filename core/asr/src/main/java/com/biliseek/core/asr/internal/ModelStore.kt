package com.biliseek.core.asr.internal

import com.biliseek.core.asr.AsrModelSpec
import com.biliseek.core.asr.ModelFile
import com.biliseek.core.asr.ModelSource
import com.biliseek.core.asr.ModelState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * 模型文件的下载 / 导入 / 校验 / 安装（纯 JVM，`AsrModelManager` 的内核，可用 MockWebServer 单测）。
 *
 * 目录：`{root}/{specId}/`。下载与导入都先写 `{name}.part`，SHA-256 通过才改名成正式文件；
 * 全部文件就位后写 `.verified` 标记（记录各文件哈希），之后启动只看标记与文件大小，不再整文件哈希。
 * 下载失败或取消时保留 `.part`，下次用 HTTP Range 续传。
 */
internal class ModelStore(
    private val root: File,
    private val http: OkHttpClient,
) {
    private val _state = MutableStateFlow<ModelState>(ModelState.NotInstalled)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    fun dir(spec: AsrModelSpec) = File(root, spec.id)

    /** 按磁盘内容刷新状态（启动时、切换模型时调用）。 */
    fun refresh(spec: AsrModelSpec): ModelState {
        val s = if (isInstalled(spec)) ModelState.Ready else ModelState.NotInstalled
        _state.value = s
        return s
    }

    fun isInstalled(spec: AsrModelSpec): Boolean {
        val marker = File(dir(spec), MARKER)
        if (!marker.exists()) return false
        val recorded = runCatching { marker.readLines().toSet() }.getOrDefault(emptySet())
        return spec.files.all { f ->
            val file = File(dir(spec), f.name)
            file.exists() && file.length() == f.bytes && "${f.name}\t${f.sha256}" in recorded
        }
    }

    fun modelDir(spec: AsrModelSpec): File? = dir(spec).takeIf { isInstalled(spec) }

    /** 逐文件下载（已就位且校验过的跳过）。失败时状态置 [ModelState.Broken] 并抛出。取消时状态回 NotInstalled。 */
    suspend fun download(spec: AsrModelSpec, source: ModelSource) {
        val dir = dir(spec).apply { mkdirs() }
        File(dir, MARKER).delete()
        val total = spec.totalBytes
        val progress = ProgressThrottle(total) { done -> _state.value = ModelState.Downloading(done, total) }
        try {
            var base = 0L
            for (file in spec.files) {
                val target = File(dir, file.name)
                if (target.exists() && target.length() == file.bytes && sha256(target) == file.sha256) {
                    base += file.bytes
                    progress.report(base)
                    continue
                }
                target.delete()
                val part = File(dir, file.name + PART)
                fetch(source.baseUrl + file.name, part, file) { done -> progress.report(base + done) }
                _state.value = ModelState.Verifying
                verifyAndInstall(part, target, file)
                base += file.bytes
            }
            finish(spec)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = ModelState.NotInstalled
            throw e
        } catch (e: Exception) {
            _state.value = ModelState.Broken(e.message ?: "下载失败")
            throw e
        }
    }

    /**
     * 导入用户选的文件：[inputs] 为 (文件名, 大小, 打开流)。按文件名匹配清单，名字对不上时按字节数匹配。
     * 已安装的文件不要求重复导入；导入后仍缺文件则 Broken（列出缺哪个）。
     */
    suspend fun import(spec: AsrModelSpec, inputs: List<ImportInput>) {
        val dir = dir(spec).apply { mkdirs() }
        File(dir, MARKER).delete()
        _state.value = ModelState.Verifying
        try {
            for (input in inputs) {
                currentCoroutineContext().ensureActive()
                val file = spec.files.firstOrNull { it.name == input.name }
                    ?: spec.files.firstOrNull { input.size != null && it.bytes == input.size }
                    ?: continue
                val part = File(dir, file.name + PART)
                part.delete()
                input.open().use { src -> part.outputStream().use { src.copyTo(it) } }
                verifyAndInstall(part, File(dir, file.name), file)
            }
            val missing = spec.files.filter { f ->
                val file = File(dir, f.name)
                !(file.exists() && file.length() == f.bytes)
            }
            if (missing.isNotEmpty()) throw IOException("还缺少 ${missing.joinToString("、") { it.name }}")
            // 导入前就在目录里的文件也要过一遍哈希（可能是上次没下完的残留）
            for (f in spec.files) {
                if (sha256(File(dir, f.name)) != f.sha256) {
                    File(dir, f.name).delete()
                    throw IOException("${f.name} 校验失败，请重新导入")
                }
            }
            finish(spec)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = ModelState.NotInstalled
            throw e
        } catch (e: Exception) {
            _state.value = ModelState.Broken(e.message ?: "导入失败")
            throw e
        }
    }

    fun delete(spec: AsrModelSpec) {
        dir(spec).deleteRecursively()
        _state.value = ModelState.NotInstalled
    }

    private fun finish(spec: AsrModelSpec) {
        val marker = File(dir(spec), MARKER)
        marker.writeText(spec.files.joinToString("\n") { "${it.name}\t${it.sha256}" })
        _state.value = ModelState.Ready
    }

    private fun verifyAndInstall(part: File, target: File, file: ModelFile) {
        if (part.length() != file.bytes || sha256(part) != file.sha256) {
            part.delete()
            throw IOException("${file.name} 校验失败（文件不完整或来源不对），请换一个下载来源重试")
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("无法写入 ${file.name}")
    }

    /** 单文件下载，已有 `.part` 时带 Range 续传；服务器不支持 Range（回 200）则从头写。 */
    private suspend fun fetch(url: String, part: File, file: ModelFile, onBytes: (Long) -> Unit) {
        var existing = if (part.exists()) part.length() else 0L
        if (existing > file.bytes) {
            part.delete()
            existing = 0L
        }
        if (existing == file.bytes) return
        val request = Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()
        val call = http.newCall(request)
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { response ->
                val append = when (response.code) {
                    206 -> true
                    200 -> false
                    416 -> return // 已经完整（大小相符时前面已 return；这里交给校验）
                    else -> throw IOException("下载失败：HTTP ${response.code}")
                }
                val body = response.body
                var done = if (append) existing else 0L
                onBytes(done)
                java.io.FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onBytes(done)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw IOException(e.message?.takeIf { it.startsWith("下载失败") } ?: "下载中断，网络恢复后可继续", e)
        } finally {
            handle?.dispose()
        }
    }

    internal class ImportInput(val name: String?, val size: Long?, val open: () -> InputStream)

    companion object {
        const val MARKER = ".verified"
        const val PART = ".part"

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** 下载进度节流：只在整数百分比变化时回调（phone-whisper 的教训：推送过密会塞满主线程消息队列）。 */
internal class ProgressThrottle(private val total: Long, private val emit: (Long) -> Unit) {
    private var lastPercent = -1

    fun report(done: Long) {
        val percent = if (total <= 0) 100 else (done.coerceIn(0, total) * 100 / total).toInt()
        if (percent != lastPercent) {
            lastPercent = percent
            emit(done)
        }
    }
}
