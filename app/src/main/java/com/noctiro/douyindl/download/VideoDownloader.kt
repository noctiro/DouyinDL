package com.noctiro.douyindl.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noctiro.douyindl.R
import com.noctiro.douyindl.data.VideoInfo
import com.noctiro.douyindl.util.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

enum class DownloadState {
    Idle, Downloading, Completed, Failed
}

fun interface DownloadProgressListener {
    fun onProgress(progress: Float, downloadedBytes: Long, totalBytes: Long, speed: Long, etaSeconds: Long)
}

fun interface DownloadCompleteListener {
    fun onComplete(state: DownloadState, failReason: String?)
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

    private var downloadedFileUri: Uri? = null
    private var downloadJob: Job? = null
    @Volatile
    private var cancelled = false

    var progressListener: DownloadProgressListener? = null
    var completeListener: DownloadCompleteListener? = null

    private val client = HttpClient.instance

    companion object {
        private const val BUFFER_SIZE = 8192

        fun threadCountFor(fileSize: Long): Int = when {
            fileSize < 1 * 1024 * 1024 -> 1
            fileSize < 10 * 1024 * 1024 -> 2
            fileSize < 50 * 1024 * 1024 -> 4
            else -> 8
        }
    }

    fun start(info: VideoInfo, scope: CoroutineScope) {
        if (downloadState == DownloadState.Downloading) return

        cancelled = false
        downloadState = DownloadState.Downloading
        downloadProgress = 0f
        downloadedBytes = 0L
        downloadSpeed = 0L
        etaSeconds = -1L
        downloadFailReason = null
        downloadedFileUri = null

        downloadJob = scope.launch {
            try {
                download(info)
            } catch (e: Exception) {
                if (!cancelled) {
                    downloadFailReason = e.message ?: appContext.getString(R.string.error_download_failed)
                    downloadState = DownloadState.Failed
                    etaSeconds = -1L
                    completeListener?.onComplete(DownloadState.Failed, downloadFailReason)
                }
            }
        }
    }

    fun cancel() {
        if (downloadState != DownloadState.Downloading) return

        cancelled = true
        downloadJob?.cancel()
        downloadJob = null

        downloadState = DownloadState.Idle
        downloadProgress = 0f
        downloadedBytes = 0L
        downloadSpeed = 0L
        etaSeconds = -1L
        completeListener?.onComplete(DownloadState.Idle, null)
    }

    fun reset() {
        cancel()
        totalBytes = -1L
        downloadFailReason = null
        downloadedFileUri = null
    }

    fun getDownloadedFileUri(): Uri? = downloadedFileUri

    private suspend fun download(info: VideoInfo) = withContext(Dispatchers.IO) {
        val contentLength = HttpClient.fetchContentLength(info.url, info.userAgent)
        if (contentLength <= 0) throw Exception(appContext.getString(R.string.error_get_file_size))

        totalBytes = contentLength

        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val outputDir = File(moviesDir, "DouyinDL")
        if (!outputDir.exists()) outputDir.mkdirs()

        val outputFile = File(outputDir, "${info.title}.mp4")
        if (outputFile.exists()) outputFile.delete()

        RandomAccessFile(outputFile, "rw").use { raf ->
            raf.setLength(contentLength)
        }

        val threadCount = threadCountFor(contentLength)
        val chunkSize = contentLength / threadCount
        val downloaded = AtomicLong(0L)

        val progressJob = launch {
            trackProgress(downloaded, contentLength)
        }

        try {
            val tasks = (0 until threadCount).map { i ->
                val start = i * chunkSize
                val end = if (i == threadCount - 1) contentLength - 1 else (start + chunkSize - 1)
                async(Dispatchers.IO) {
                    downloadChunk(info.url, info.userAgent, outputFile, start, end, downloaded)
                }
            }
            tasks.awaitAll()
        } finally {
            progressJob.cancel()
        }

        if (cancelled) {
            outputFile.delete()
            return@withContext
        }

        downloadedBytes = contentLength
        downloadProgress = 1f
        downloadSpeed = 0L
        etaSeconds = 0L
        downloadedFileUri = Uri.fromFile(outputFile)
        downloadState = DownloadState.Completed

        scanFile(outputFile)
        completeListener?.onComplete(DownloadState.Completed, null)
    }

    private suspend fun downloadChunk(
        url: String,
        userAgent: String,
        file: File,
        start: Long,
        end: Long,
        downloaded: AtomicLong
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Range", "bytes=$start-$end")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw Exception(appContext.getString(R.string.error_chunk_download, response.code))
            }

            val body = response.body ?: throw Exception(appContext.getString(R.string.error_empty_response))
            val input = body.byteStream()
            val buffer = ByteArray(BUFFER_SIZE)

            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(start)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (cancelled) return
                    raf.write(buffer, 0, bytesRead)
                    downloaded.addAndGet(bytesRead.toLong())
                }
            }
        }
    }

    private suspend fun trackProgress(downloaded: AtomicLong, total: Long) {
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()

        while (true) {
            delay(500L)
            val currentBytes = downloaded.get()
            val now = System.currentTimeMillis()
            val elapsed = now - lastTime

            if (elapsed >= 500) {
                val instantSpeed = (currentBytes - lastBytes) * 1000 / elapsed
                downloadSpeed = if (downloadSpeed == 0L) {
                    instantSpeed
                } else {
                    (downloadSpeed * 3 + instantSpeed) / 4
                }
                lastBytes = currentBytes
                lastTime = now
            }

            downloadedBytes = currentBytes
            downloadProgress = currentBytes.toFloat() / total.toFloat()
            etaSeconds = if (downloadSpeed > 0) {
                (total - currentBytes) / downloadSpeed
            } else {
                -1L
            }

            progressListener?.onProgress(downloadProgress, downloadedBytes, totalBytes, downloadSpeed, etaSeconds)
        }
    }

    private fun scanFile(file: File) {
        android.media.MediaScannerConnection.scanFile(
            appContext,
            arrayOf(file.absolutePath),
            arrayOf("video/mp4"),
            null
        )
    }
}
