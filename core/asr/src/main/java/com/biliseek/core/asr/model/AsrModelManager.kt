package com.biliseek.core.asr.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.biliseek.core.asr.AsrModelSpec
import com.biliseek.core.asr.ModelSource
import com.biliseek.core.asr.ModelState
import com.biliseek.core.asr.internal.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 离线识别模型的下载 / 导入 / 删除 / 状态（工单 AI-7）。模型不进 APK，存 `filesDir/asr_models/{specId}/`。
 *
 * 自带独立的 OkHttpClient：不带任何 Cookie、不挂任何拦截器，与 B 站请求完全隔离。
 * 同一时间只进行一个操作（下载 / 导入 / 删除互斥）。
 */
class AsrModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val store = ModelStore(File(appContext.filesDir, "asr_models"), http)
    private val lock = Mutex()

    /** 当前（默认）模型的状态。 */
    val state: StateFlow<ModelState> = store.state

    init {
        store.refresh(AsrModelCatalog.default)
    }

    /** 断点续传（Range），逐文件 SHA-256。失败时状态为 Broken 并抛出；取消时保留已下载部分。 */
    suspend fun download(spec: AsrModelSpec, source: ModelSource) = lock.withLock {
        withContext(Dispatchers.IO) { store.download(spec, source) }
    }

    /** SAF 选的文件：复制后同样校验。 */
    suspend fun import(spec: AsrModelSpec, uris: List<Uri>) = lock.withLock {
        withContext(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            val inputs = uris.map { uri ->
                var name: String? = null
                var size: Long? = null
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            name = c.getString(0)
                            size = if (c.isNull(1)) null else c.getLong(1)
                        }
                    }
                ModelStore.ImportInput(name, size) {
                    resolver.openInputStream(uri) ?: throw java.io.IOException("无法读取所选文件")
                }
            }
            store.import(spec, inputs)
        }
    }

    suspend fun delete(spec: AsrModelSpec) = lock.withLock {
        withContext(Dispatchers.IO) { store.delete(spec) }
    }

    /** Ready 时非空。 */
    fun modelDir(spec: AsrModelSpec): File? = store.modelDir(spec)

    fun isReady(spec: AsrModelSpec = AsrModelCatalog.default): Boolean = store.isInstalled(spec)
}
