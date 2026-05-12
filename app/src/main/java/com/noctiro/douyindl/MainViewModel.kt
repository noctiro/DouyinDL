package com.noctiro.douyindl

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noctiro.douyindl.data.DouyinParser
import com.noctiro.douyindl.data.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.net.toUri

enum class ParseState {
    Idle, Loading, Success, Error
}

enum class DownloadState {
    Idle, Downloading, Completed, Failed
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val parser = DouyinParser()

    var inputUrl by mutableStateOf("")
        private set

    var parseState by mutableStateOf(ParseState.Idle)
        private set

    var videoInfo by mutableStateOf<VideoInfo?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var downloadState by mutableStateOf(DownloadState.Idle)
        private set

    var downloadProgress by mutableFloatStateOf(0f)
        private set

    private var downloadId: Long = -1L

    fun updateInput(text: String) {
        inputUrl = text
        if (parseState == ParseState.Error) {
            parseState = ParseState.Idle
            errorMessage = null
        }
    }

    fun parseUrl() {
        if (inputUrl.isBlank()) return
        parseState = ParseState.Loading
        errorMessage = null
        videoInfo = null
        downloadState = DownloadState.Idle
        downloadProgress = 0f

        viewModelScope.launch {
            try {
                val info = parser.parseShareUrl(inputUrl)
                videoInfo = info
                parseState = ParseState.Success
            } catch (e: Exception) {
                errorMessage = e.message ?: "解析失败"
                parseState = ParseState.Error
            }
        }
    }

    fun downloadVideo() {
        val info = videoInfo ?: return
        if (downloadState == DownloadState.Downloading) return

        val context = getApplication<Application>()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val request = DownloadManager.Request(info.url.toUri()).apply {
            setTitle(info.title)
            setDescription("正在下载抖音视频")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_MOVIES,
                "DouyinDL/${info.title}.mp4"
            )
            addRequestHeader(
                "User-Agent",
                info.userAgent
            )
        }

        downloadId = dm.enqueue(request)
        downloadState = DownloadState.Downloading
        downloadProgress = 0f

        viewModelScope.launch {
            pollDownloadProgress(dm, downloadId)
        }
    }

    private suspend fun pollDownloadProgress(dm: DownloadManager, id: Long) {
        withContext(Dispatchers.IO) {
            while (true) {
                val query = DownloadManager.Query().setFilterById(id)
                val cursor = dm.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    val bytesDownloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val bytesTotal = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    cursor.close()

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            downloadProgress = 1f
                            downloadState = DownloadState.Completed
                            return@withContext
                        }
                        DownloadManager.STATUS_FAILED -> {
                            downloadState = DownloadState.Failed
                            return@withContext
                        }
                        else -> {
                            if (bytesTotal > 0) {
                                downloadProgress = bytesDownloaded.toFloat() / bytesTotal.toFloat()
                            }
                        }
                    }
                } else {
                    cursor?.close()
                    downloadState = DownloadState.Failed
                    return@withContext
                }
                delay(500L)
            }
        }
    }

    fun getDownloadedFileUri(): Uri? {
        val info = videoInfo ?: return null
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "DouyinDL/${info.title}.mp4"
        )
        if (!file.exists()) return null
        val context = getApplication<Application>()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun getDownloadedDirIntent(): Intent {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "DouyinDL"
        )
        dir.mkdirs()
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMovies%2FDouyinDL")
        return Intent(Intent.ACTION_VIEW).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun reset() {
        inputUrl = ""
        parseState = ParseState.Idle
        videoInfo = null
        errorMessage = null
        downloadState = DownloadState.Idle
        downloadProgress = 0f
    }
}
