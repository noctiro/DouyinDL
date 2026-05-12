package com.noctiro.douyindl.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.noctiro.douyindl.data.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DownloadState {
    Idle, Downloading, Completed, Failed
}

class VideoDownloader(private val appContext: Context) {

    var downloadState by mutableStateOf(DownloadState.Idle)
        private set

    var downloadProgress by mutableFloatStateOf(0f)
        private set

    var totalBytes by mutableLongStateOf(-1L)
        private set

    var downloadedBytes by mutableLongStateOf(0L)
        private set

    var downloadSpeed by mutableLongStateOf(0L)
        private set

    var etaSeconds by mutableLongStateOf(-1L)
        private set

    var downloadFailReason by mutableStateOf<String?>(null)
        private set

    private var downloadId: Long = -1L
    private var downloadedFileUri: Uri? = null
    private var pollJob: Job? = null

    fun start(info: VideoInfo, scope: CoroutineScope) {
        if (downloadState == DownloadState.Downloading) return

        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val request = DownloadManager.Request(info.url.toUri()).apply {
            setTitle(info.title)
            setDescription("正在下载抖音视频")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_MOVIES,
                "DouyinDL/${info.title}.mp4"
            )
            addRequestHeader("User-Agent", info.userAgent)
        }

        downloadId = dm.enqueue(request)
        downloadState = DownloadState.Downloading
        downloadProgress = 0f
        downloadedBytes = 0L
        downloadSpeed = 0L
        etaSeconds = -1L
        downloadFailReason = null

        pollJob = scope.launch {
            pollDownloadProgress(dm, downloadId)
        }
    }

    fun cancel() {
        if (downloadState != DownloadState.Downloading) return

        pollJob?.cancel()
        pollJob = null

        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if (downloadId != -1L) {
            dm.remove(downloadId)
        }

        downloadState = DownloadState.Idle
        downloadProgress = 0f
        downloadedBytes = 0L
        downloadSpeed = 0L
        etaSeconds = -1L
    }

    fun reset() {
        cancel()
        totalBytes = -1L
        downloadFailReason = null
        downloadedFileUri = null
    }

    fun getDownloadedFileUri(): Uri? = downloadedFileUri

    private suspend fun pollDownloadProgress(dm: DownloadManager, id: Long) {
        withContext(Dispatchers.IO) {
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()
            while (true) {
                val query = DownloadManager.Query().setFilterById(id)
                val cursor = dm.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    val bytesDown = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val bytesTotal = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    cursor.close()

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            downloadProgress = 1f
                            downloadedBytes = bytesTotal
                            totalBytes = bytesTotal
                            downloadSpeed = 0L
                            etaSeconds = 0L
                            downloadedFileUri = dm.getUriForDownloadedFile(id)
                            downloadState = DownloadState.Completed
                            return@withContext
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = cursor.getInt(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                            )
                            downloadFailReason = mapDownloadError(reason)
                            downloadState = DownloadState.Failed
                            etaSeconds = -1L
                            return@withContext
                        }
                        else -> {
                            val now = System.currentTimeMillis()
                            val elapsed = now - lastTime
                            if (elapsed >= 500) {
                                val instantSpeed = (bytesDown - lastBytes) * 1000 / elapsed
                                downloadSpeed = if (downloadSpeed == 0L) {
                                    instantSpeed
                                } else {
                                    (downloadSpeed * 3 + instantSpeed) / 4
                                }
                                lastBytes = bytesDown
                                lastTime = now
                            }
                            downloadedBytes = bytesDown
                            if (bytesTotal > 0) {
                                totalBytes = bytesTotal
                                downloadProgress = bytesDown.toFloat() / bytesTotal.toFloat()
                                etaSeconds = if (downloadSpeed > 0) {
                                    (bytesTotal - bytesDown) / downloadSpeed
                                } else {
                                    -1L
                                }
                            }
                        }
                    }
                } else {
                    cursor?.close()
                    downloadState = DownloadState.Failed
                    etaSeconds = -1L
                    return@withContext
                }
                delay(500L)
            }
        }
    }

    private fun mapDownloadError(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "未找到存储设备"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "网络数据错误"
        DownloadManager.ERROR_CANNOT_RESUME -> "无法恢复下载"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "重定向次数过多"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "服务器返回错误"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "文件已存在"
        else -> "下载失败 (错误码: $reason)"
    }
}
