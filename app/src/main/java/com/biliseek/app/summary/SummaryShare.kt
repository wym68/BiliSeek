package com.biliseek.app.summary

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** AI 总结的分享与导出文件名（工单 AI-6）。 */
object SummaryShare {

    /** 通过 FileProvider 分享 `.md` 文件（只暴露 filesDir/ai_summary/）。 */
    fun share(context: Context, file: File, title: String) {
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/markdown")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "分享 AI 总结"))
    }

    fun fileName(title: String): String = SummaryFileNamePolicy.fileName(title)
}
