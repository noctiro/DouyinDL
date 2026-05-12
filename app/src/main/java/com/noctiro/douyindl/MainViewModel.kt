package com.noctiro.douyindl

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noctiro.douyindl.data.VideoInfo
import com.noctiro.douyindl.data.VideoParserManager
import com.noctiro.douyindl.download.DownloadState
import com.noctiro.douyindl.download.VideoDownloader
import kotlinx.coroutines.launch

enum class ParseState {
    Idle, Loading, Success, Error
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val parserManager = VideoParserManager()
    val downloader = VideoDownloader(application)

    var inputUrl by mutableStateOf("")
        private set

    var parseState by mutableStateOf(ParseState.Idle)
        private set

    var videoInfo by mutableStateOf<VideoInfo?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var totalBytes by mutableLongStateOf(-1L)
        private set

    val downloadState: DownloadState get() = downloader.downloadState
    val downloadProgress: Float get() = downloader.downloadProgress
    val downloadedBytes: Long get() = downloader.downloadedBytes
    val downloadTotalBytes: Long get() = downloader.totalBytes
    val downloadSpeed: Long get() = downloader.downloadSpeed
    val downloadFailReason: String? get() = downloader.downloadFailReason
    val etaSeconds: Long get() = downloader.etaSeconds

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
        totalBytes = -1L
        downloader.reset()

        viewModelScope.launch {
            try {
                val info = parserManager.parse(inputUrl)
                videoInfo = info
                parseState = ParseState.Success
                fetchFileSize(info)
            } catch (e: Exception) {
                errorMessage = e.message ?: "解析失败"
                parseState = ParseState.Error
            }
        }
    }

    fun downloadVideo() {
        val info = videoInfo ?: return
        downloader.start(info, viewModelScope)
    }

    fun cancelDownload() = downloader.cancel()

    fun getDownloadedFileUri() = downloader.getDownloadedFileUri()

    fun reset() {
        inputUrl = ""
        parseState = ParseState.Idle
        videoInfo = null
        errorMessage = null
        totalBytes = -1L
        downloader.reset()
    }

    private fun fetchFileSize(info: VideoInfo) {
        viewModelScope.launch {
            try {
                val length = parserManager.fetchFileSize(info.url, info.userAgent)
                if (length > 0) totalBytes = length
            } catch (_: Exception) { }
        }
    }
}
