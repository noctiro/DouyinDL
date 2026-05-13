package com.noctiro.douyindl.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.noctiro.douyindl.MainActivity
import com.noctiro.douyindl.R
import com.noctiro.douyindl.data.VideoInfo
import com.noctiro.douyindl.util.formatEta
import com.noctiro.douyindl.util.formatFileSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var notificationManager: NotificationManager
    private var downloader: VideoDownloader? = null

    companion object {
        private const val CHANNEL_ID = "download_progress"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_COMPLETE_ID = 2

        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_USER_AGENT = "extra_user_agent"

        private const val ACTION_CANCEL = "com.noctiro.douyindl.ACTION_CANCEL_DOWNLOAD"

        var currentDownloader: VideoDownloader? = null
            private set

        var isRunning = false
            private set

        fun start(context: Context, info: VideoInfo) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, info.url)
                putExtra(EXTRA_TITLE, info.title)
                putExtra(EXTRA_USER_AGENT, info.userAgent)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            currentDownloader?.cancel()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            currentDownloader?.cancel()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT) ?: ""

        val info = VideoInfo(url = url, title = title, videoId = "", userAgent = userAgent)

        val notification = buildProgressNotification(title, 0, "").build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true

        val dl = VideoDownloader(applicationContext)
        downloader = dl
        currentDownloader = dl

        dl.progressListener = DownloadProgressListener { progress, downloaded, total, speed, eta ->
            val speedText = "${formatFileSize(speed)}/s"
            val etaText = if (eta > 0) formatEta(this, eta) else ""
            val subText = if (etaText.isNotEmpty()) "$speedText · $etaText" else speedText
            val notification = buildProgressNotification(title, (progress * 100).toInt(), subText).build()
            notificationManager.notify(NOTIFICATION_ID, notification)
        }

        dl.completeListener = DownloadCompleteListener { state, failReason ->
            when (state) {
                DownloadState.Completed -> {
                    showCompleteNotification(title)
                    stopSelf()
                }
                DownloadState.Failed -> {
                    showFailedNotification(title, failReason)
                    stopSelf()
                }
                else -> stopSelf()
            }
        }

        dl.start(info, serviceScope)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        currentDownloader = null
        downloader = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_download),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildProgressNotification(title: String, progress: Int, subText: String): NotificationCompat.Builder {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notification_downloading_title))
            .setContentText(title)
            .setSubText(subText)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_delete, getString(R.string.cancel_download), cancelIntent)
    }

    private fun showCompleteNotification(title: String) {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notification_download_complete))
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.notify(NOTIFICATION_COMPLETE_ID, notification)
    }

    private fun showFailedNotification(title: String, reason: String?) {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.notification_download_failed))
            .setContentText(reason ?: title)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.notify(NOTIFICATION_COMPLETE_ID, notification)
    }
}
