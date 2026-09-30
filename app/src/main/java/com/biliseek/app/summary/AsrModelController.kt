package com.biliseek.app.summary

import android.net.Uri
import com.biliseek.core.asr.AsrModelSpec
import com.biliseek.core.asr.ModelSource
import com.biliseek.core.asr.ModelState
import com.biliseek.core.asr.model.AsrModelCatalog
import com.biliseek.core.asr.model.AsrModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 设置页「离线识别模型」卡片的编排（工单 AI-8）。操作跑在自己的作用域里：离开设置页不中断；
 * 进程被杀后 `.part` 保留，下次点下载按 Range 续传。
 */
class AsrModelController(
    private val models: AsrModelManager,
    private val network: NetworkMonitor,
    private val emitMessage: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    val spec: AsrModelSpec = AsrModelCatalog.default
    val state: StateFlow<ModelState> = models.state

    fun isMetered(): Boolean = network.isMetered()

    fun download(source: ModelSource) = launch("模型下载完成") { models.download(spec, source) }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        launch("模型导入完成") { models.import(spec, uris) }
    }

    fun delete() = launch("已删除离线识别模型") { models.delete(spec) }

    fun cancel() {
        job?.cancel()
    }

    private fun launch(doneMessage: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                block()
                emitMessage(doneMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 状态已是 Broken，卡片上会显示原因；这里只补一条提示
                emitMessage(e.message ?: "操作失败")
            }
        }
    }
}
