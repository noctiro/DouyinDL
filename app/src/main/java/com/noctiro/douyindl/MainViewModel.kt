package com.noctiro.douyindl

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noctiro.douyindl.data.ResException
import com.noctiro.douyindl.data.VideoInfo
import com.noctiro.douyindl.data.VideoParserManager
import com.noctiro.douyindl.download.DownloadState
import com.noctiro.douyindl.download.VideoDownloader
import kotlinx.coroutines.launch
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

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
                errorMessage = resolveErrorMessage(e)
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

    private fun resolveErrorMessage(e: Exception): String {
        val ctx = getApplication<Application>()
        if (e is ResException) {
            return ctx.getString(e.resId, *e.args)
        }
        val cause = e.cause ?: e
        return when (cause) {
            is SocketTimeoutException -> ctx.getString(R.string.error_timeout)
            is UnknownHostException -> ctx.getString(R.string.error_unknown_host)
            is SSLException -> ctx.getString(R.string.error_ssl)
            is SocketException -> ctx.getString(R.string.error_connection_reset)
            else -> when {
                e is SocketTimeoutException -> ctx.getString(R.string.error_timeout)
                e is UnknownHostException -> ctx.getString(R.string.error_unknown_host)
                e is SSLException -> ctx.getString(R.string.error_ssl)
                e is SocketException -> ctx.getString(R.string.error_connection_reset)
                e.message?.contains("timeout", ignoreCase = true) == true -> ctx.getString(R.string.error_timeout)
                e.message?.contains("reset", ignoreCase = true) == true -> ctx.getString(R.string.error_connection_reset)
                else -> e.message ?: ctx.getString(R.string.error_parse_failed)
            }
        }
    }
}
