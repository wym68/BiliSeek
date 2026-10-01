package com.biliseek.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.biliseek.app.MainActivity
import com.biliseek.app.R
import com.biliseek.app.data.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 下载前台服务。与 [com.biliseek.core.player.PlaybackService] **分开**：
 * 播放服务跟着用户听，下载服务跟着任务队列，两者的存活条件不同，
 * 不该互相拖住生命周期。
 *
 * 状态与执行都在 [DownloadManager]，本类只负责前台声明与通知呈现。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = DownloadManager.get(this)
        // 先确保前台状态成立，通知内容随后由 processQueue 逐条更新
        startForegroundCompat(manager.active ?: placeholderTask())
        scope.launch { manager.processQueue(this@DownloadService) }
        return START_NOT_STICKY
    }

    /** 队列空了，收尾。 */
    fun finish() {
        stopForegroundCompat()
        stopSelf()
    }

    /** 更新通知。进行中显示进度，失败可点按进入 App 重试。 */
    fun showNotification(task: DownloadTask) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(task))
    }

    private fun startForegroundCompat(task: DownloadTask) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification(task), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification(task))
        }
    }

    /** 没有进行中的任务时（服务刚起、队列还在解析取流），先显示一个占位。 */
    private fun placeholderTask(): DownloadTask = DownloadTask(
        id = 0,
        bvid = "",
        cid = 0,
        title = "BiliSeek 下载",
        author = "",
        coverUrl = "",
        content = "",
        location = "",
        status = DownloadStatus.QUEUED,
        progress = 0f,
        error = "",
        fileSize = 0,
    )

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(task: DownloadTask): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = task.title.ifBlank { "下载中" }
        val subtitle = when (task.status) {
            DownloadStatus.DOWNLOADING -> {
                // 多条并行时管理器合成一条汇总（content 为空）：只显示总进度
                if (task.content.isBlank()) {
                    if (task.progress > 0) "总进度 ${(task.progress * 100).toInt()}%" else "下载中"
                } else {
                    val kind = if (task.content == com.biliseek.app.data.DownloadContent.VIDEO) "视频" else "音频"
                    val loc = locationLabel(task.location)
                    val pct = if (task.progress > 0) " · ${(task.progress * 100).toInt()}%" else ""
                    "$kind · $loc$pct"
                }
            }
            DownloadStatus.QUEUED -> "排队中"
            DownloadStatus.DONE -> "下载完成"
            DownloadStatus.FAILED -> task.error.ifBlank { "下载失败" }
            else -> ""
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentIntent)
            .setOngoing(task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.QUEUED)
            .setOnlyAlertOnce(true)
        if (task.status == DownloadStatus.DOWNLOADING && task.progress > 0) {
            builder.setProgress(100, (task.progress * 100).toInt(), false)
        }
        return builder.build()
    }

    private fun locationLabel(location: String): String = when (location) {
        com.biliseek.app.data.DownloadLocation.PRIVATE -> "应用内"
        com.biliseek.app.data.DownloadLocation.PUBLIC -> "公共媒体库"
        com.biliseek.app.data.DownloadLocation.SAF -> "自定义目录"
        else -> ""
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIF_ID = 1001
    }
}
