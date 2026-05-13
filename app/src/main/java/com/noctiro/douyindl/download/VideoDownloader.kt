package com.noctiro.douyindl.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noctiro.douyindl.R
import com.noctiro.douyindl.data.VideoInfo
import com.noctiro.douyindl.util.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
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

    private val client = HttpClient.downloadInstance

    private data class Segment(val start: Long, val end: Long)

    companion object {
        private const val BUFFER_SIZE = 8192
        private const val SEGMENT_SIZE = 2L * 1024 * 1024
        private const val MAX_SEGMENT_RETRIES = 3

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

        withContext(Dispatchers.Main) { totalBytes = contentLength }

        val safeTitle = info.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val tempFile = File(appContext.cacheDir, "${safeTitle}.mp4.tmp")
        if (tempFile.exists()) tempFile.delete()

        try {
            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.setLength(contentLength)
            }

            val segments = buildSegments(contentLength)
            val channel = Channel<Segment>(capacity = Channel.UNLIMITED)
            for (segment in segments) { channel.send(segment) }
            channel.close()

            val workerCount = threadCountFor(contentLength)
            val downloaded = AtomicLong(0L)

            val progressJob = launch {
                trackProgress(downloaded, contentLength)
            }

            try {
                val workers = (0 until workerCount).map {
                    async(Dispatchers.IO) {
                        for (segment in channel) {
                            if (cancelled) break
                            downloadSegmentWithRetry(info.url, info.userAgent, tempFile, segment, downloaded)
                        }
                    }
                }
                workers.awaitAll()
            } finally {
                progressJob.cancel()
            }

            if (cancelled) {
                tempFile.delete()
                return@withContext
            }

            val uri = copyToMediaStore(tempFile, "${safeTitle}.mp4")
            tempFile.delete()

            withContext(Dispatchers.Main) {
                downloadedBytes = contentLength
                downloadProgress = 1f
                downloadSpeed = 0L
                etaSeconds = 0L
                downloadedFileUri = uri
                downloadState = DownloadState.Completed
            }

            completeListener?.onComplete(DownloadState.Completed, null)
        } catch (e: CancellationException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun buildSegments(contentLength: Long): List<Segment> {
        val segments = mutableListOf<Segment>()
        var offset = 0L
        while (offset < contentLength) {
            val end = minOf(offset + SEGMENT_SIZE - 1, contentLength - 1)
            segments.add(Segment(offset, end))
            offset = end + 1
        }
        return segments
    }

    private fun downloadSegmentWithRetry(
        url: String,
        userAgent: String,
        file: File,
        segment: Segment,
        downloaded: AtomicLong
    ) {
        var lastException: Exception? = null
        repeat(MAX_SEGMENT_RETRIES) { attempt ->
            val before = downloaded.get()
            try {
                downloadSegment(url, userAgent, file, segment, downloaded)
                return
            } catch (e: Exception) {
                val written = downloaded.get() - before
                if (written > 0) downloaded.addAndGet(-written)
                lastException = e
                if (attempt < MAX_SEGMENT_RETRIES - 1) {
                    Thread.sleep(500L * (attempt + 1))
                }
            }
        }
        throw lastException!!
    }

    private fun downloadSegment(
        url: String,
        userAgent: String,
        file: File,
        segment: Segment,
        downloaded: AtomicLong
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Range", "bytes=${segment.start}-${segment.end}")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception(appContext.getString(R.string.error_chunk_download, response.code))
            }

            val body = response.body ?: throw Exception(appContext.getString(R.string.error_empty_response))
            val input = body.byteStream()
            val buffer = ByteArray(BUFFER_SIZE)

            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(segment.start)
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

            val speed = if (elapsed >= 500) {
                val instantSpeed = (currentBytes - lastBytes) * 1000 / elapsed
                lastBytes = currentBytes
                lastTime = now
                if (downloadSpeed == 0L) instantSpeed else (downloadSpeed * 3 + instantSpeed) / 4
            } else {
                downloadSpeed
            }

            val progress = currentBytes.toFloat() / total.toFloat()
            val eta = if (speed > 0) (total - currentBytes) / speed else -1L

            withContext(Dispatchers.Main) {
                downloadSpeed = speed
                downloadedBytes = currentBytes
                downloadProgress = progress
                etaSeconds = eta
            }

            progressListener?.onProgress(progress, currentBytes, totalBytes, speed, eta)
        }
    }

    private fun copyToMediaStore(sourceFile: File, displayName: String): Uri {
        val resolver = appContext.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/DouyinDL")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw Exception(appContext.getString(R.string.error_download_failed))

        try {
            val output = resolver.openOutputStream(uri)
                ?: throw Exception(appContext.getString(R.string.error_download_failed))

            output.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out, BUFFER_SIZE)
                }
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        contentValues.clear()
        contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)

        return uri
    }
}
