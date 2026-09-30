package com.biliseek.app.summary

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.biliseek.app.MainActivity
import com.biliseek.app.R
import com.biliseek.app.data.AiSummaryEntity
import com.biliseek.app.data.AiSummaryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AI 总结前台服务（工单 AI-5）。与播放服务、下载服务互不绑定生命周期：锁屏、切到别的 App 都不中断。
 *
 * 状态与执行都在 [SummaryManager]；本类只负责前台声明、进度通知与完成 / 失败通知（带出路按钮）。
 */
class SummaryService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastNotifyAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = SummaryManager.get(this)
        startForegroundCompat(progressNotification(manager.active.value, SummaryLive("准备中")))
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        if (id > 0) {
            cancelResult(id)
            when (intent?.action) {
                ACTION_RETRY -> manager.retry(id)
                ACTION_BACKUP -> manager.switchToBackup(id)
                ACTION_NOTE -> manager.makeTranscriptNote(id)
            }
        }
        scope.launch { manager.processQueue(this@SummaryService) }
        return START_NOT_STICKY
    }

    /** 队列空了，收尾（完成 / 失败通知保留）。 */
    fun finish() {
        scope.launch {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    /** 进度通知。节流：至多每 700ms 刷一次（流式正文更新很密）。 */
    fun showProgress(row: AiSummaryEntity, live: SummaryLive) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyAt < NOTIFY_INTERVAL_MS && live.streamingText.isNotEmpty()) return
        lastNotifyAt = now
        manager().notify(PROGRESS_ID, progressNotification(row, live))
    }

    /** 完成或失败：独立通知，可点开阅读页；失败时带至多两个出路按钮（工单 §3.7.7）。 */
    fun showResult(row: AiSummaryEntity, actions: List<FailureAction>) {
        val done = row.status == AiSummaryStatus.DONE
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (done) "AI 总结已生成" else "AI 总结失败")
            .setContentText(if (done) row.title else "${row.title} · ${row.error}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (done) row.title else "${row.title}\n${row.error}"))
            .setContentIntent(openApp(row))
            .setAutoCancel(true)
        if (!done) {
            actions.filter { it != FailureAction.USE_OFFICIAL }.take(2).forEach { action ->
                builder.addAction(0, action.label, actionIntent(row.id, action))
            }
        }
        manager().notify(resultId(row.id), builder.build())
    }

    private fun progressNotification(row: AiSummaryEntity?, live: SummaryLive): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(row?.title?.ifBlank { null } ?: "AI 总结")
            .setContentText(live.stage)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(row))
        val fraction = live.fraction
        if (fraction != null) builder.setProgress(100, (fraction * 100).toInt(), false) else builder.setProgress(0, 0, true)
        return builder.build()
    }

    private fun openApp(row: AiSummaryEntity?): PendingIntent = PendingIntent.getActivity(
        this,
        (row?.id ?: 0).toInt(),
        Intent(this, MainActivity::class.java).apply {
            if (row != null) {
                putExtra(EXTRA_OPEN_BVID, row.bvid)
                putExtra(EXTRA_OPEN_CID, row.cid)
            }
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 「去设置」「生成原文笔记」「重试」「改用备用模型」。去设置只打开 App。 */
    private fun actionIntent(id: Long, action: FailureAction): PendingIntent {
        if (action == FailureAction.OPEN_SETTINGS) {
            return PendingIntent.getActivity(
                this,
                resultId(id),
                Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_AI_SETTINGS, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val intent = Intent(this, SummaryService::class.java)
            .setAction(
                when (action) {
                    FailureAction.SWITCH_TO_BACKUP -> ACTION_BACKUP
                    FailureAction.TRANSCRIPT_NOTE -> ACTION_NOTE
                    else -> ACTION_RETRY
                },
            )
            .putExtra(EXTRA_ID, id)
        return if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(this, resultId(id) + action.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getService(this, resultId(id) + action.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }

    private fun cancelResult(id: Long) = manager().cancel(resultId(id))

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        manager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.ai_summary_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun manager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN_BVID = "biliseek.summary.open_bvid"
        const val EXTRA_OPEN_CID = "biliseek.summary.open_cid"
        const val EXTRA_OPEN_AI_SETTINGS = "biliseek.summary.open_ai_settings"

        private const val CHANNEL_ID = "ai_summary"
        private const val PROGRESS_ID = 1101
        private const val RESULT_ID_BASE = 1200
        private const val NOTIFY_INTERVAL_MS = 700L
        private const val EXTRA_ID = "biliseek.summary.id"
        private const val ACTION_RETRY = "biliseek.summary.RETRY"
        private const val ACTION_BACKUP = "biliseek.summary.BACKUP"
        private const val ACTION_NOTE = "biliseek.summary.NOTE"

        private fun resultId(id: Long): Int = RESULT_ID_BASE + (id % 5_000).toInt() * 10
    }
}
