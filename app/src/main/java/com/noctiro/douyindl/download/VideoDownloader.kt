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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.acos
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class DownloadState {
    Idle, Downloading, Completed, Failed
}

fun interface DownloadProgressListener {
    fun onProgress(progress: Float, downloadedBytes: Long, totalBytes: Long, speed: Long, etaSeconds: Long)
}

fun interface DownloadCompleteListener {
    fun onComplete(state: DownloadState, failReason: String?)
}

private enum class SchedulerPhase { Probe, Formula, AIMD }

private class SchedulerState(val fileSize: Long) {
    @Volatile var phase: SchedulerPhase = SchedulerPhase.Probe
    val targetN = AtomicInteger(initialNFor(fileSize))
    @Volatile var currentChunkSize: Long = initialChunkFor(fileSize)
    @Volatile var ewmaSpeed: Double = 0.0
    val probeSamples = mutableListOf<Double>()
    val consecutiveFailures = AtomicInteger(0)

    // AIMD window state
    val windowChunkCount = AtomicInteger(0)
    val windowBytes = AtomicLong(0L)
    @Volatile var windowStartTime: Long = System.currentTimeMillis()
    @Volatile var lastWindowThroughput: Double = 0.0
    var windowsSinceCalibration = 0

    val mutex = Mutex()

    companion object {
        fun initialNFor(fileSize: Long): Int = when {
            fileSize < 1L * 1024 * 1024 -> 1
            fileSize < 10L * 1024 * 1024 -> 4
            fileSize < 50L * 1024 * 1024 -> 8
            else -> 12
        }

        fun initialChunkFor(fileSize: Long): Long = when {
            fileSize < 1L * 1024 * 1024 -> fileSize
            fileSize < 10L * 1024 * 1024 -> 1L * 1024 * 1024
            else -> 2L * 1024 * 1024
        }
    }
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
        private const val MAX_SEGMENT_RETRIES = 3
        private const val N_MAX = 16
        private const val CHUNK_MIN = 64L * 1024
        private const val CHUNK_MAX = 4L * 1024 * 1024
        private const val EWMA_ALPHA = 0.3
        private const val PROBE_SAMPLE_COUNT = 10
        private const val AIMD_WINDOW_CHUNKS = 5
        private const val AIMD_INCREASE_THRESHOLD = 0.05
        private const val AIMD_DECREASE_THRESHOLD = -0.10
        private const val AIMD_EXPLORE_PROBABILITY = 0.10
        private const val TARGET_CHUNK_DURATION_SEC = 3.0
        private const val RECALIBRATION_WINDOWS = 10
        private const val ANOMALY_FAILURE_THRESHOLD = 3
        private const val T0_OVERHEAD = 0.1
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
            } catch (e: CancellationException) {
                throw e
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

            val state = SchedulerState(contentLength)
            val segmentChannel = Channel<Segment>(capacity = 16)
            val scaleChannel = Channel<Int>(Channel.CONFLATED)
            val downloaded = AtomicLong(0L)
            val activeWorkers = AtomicInteger(0)
            val allDone = Channel<Unit>(Channel.UNLIMITED)

            val producerJob = launch(Dispatchers.Default) {
                var offset = 0L
                while (offset < contentLength) {
                    val chunkSize = state.currentChunkSize
                    val end = minOf(offset + chunkSize - 1, contentLength - 1)
                    segmentChannel.send(Segment(offset, end))
                    offset = end + 1
                }
                segmentChannel.close()
            }

            val progressJob = launch {
                trackProgress(downloaded, contentLength, state)
            }

            fun spawnWorker() {
                activeWorkers.incrementAndGet()
                launch(Dispatchers.IO) {
                    try {
                        for (segment in segmentChannel) {
                            if (cancelled) break
                            downloadSegmentAdaptive(
                                info.url, info.userAgent, tempFile,
                                segment, downloaded, state, scaleChannel
                            )
                            if (activeWorkers.get() > state.targetN.get()) break
                        }
                    } finally {
                        if (activeWorkers.decrementAndGet() == 0) {
                            allDone.trySend(Unit)
                        }
                    }
                }
            }

            repeat(state.targetN.get()) { spawnWorker() }

            val scaleJob = launch {
                for (newN in scaleChannel) {
                    val active = activeWorkers.get()
                    if (newN > active) {
                        repeat(newN - active) { spawnWorker() }
                    }
                }
            }

            try {
                producerJob.join()
                allDone.receive()
            } finally {
                progressJob.cancel()
                scaleJob.cancel()
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

    private suspend fun downloadSegmentAdaptive(
        url: String,
        userAgent: String,
        file: File,
        segment: Segment,
        downloaded: AtomicLong,
        state: SchedulerState,
        scaleChannel: Channel<Int>
    ) {
        var lastException: Exception? = null

        repeat(MAX_SEGMENT_RETRIES) { attempt ->
            val attemptStart = System.currentTimeMillis()
            val before = downloaded.get()
            try {
                downloadSegment(url, userAgent, file, segment, downloaded)
                val duration = System.currentTimeMillis() - attemptStart
                val segmentBytes = segment.end - segment.start + 1
                state.consecutiveFailures.set(0)
                onSegmentComplete(state, segmentBytes, duration, scaleChannel)
                return
            } catch (e: Exception) {
                val written = downloaded.get() - before
                if (written > 0) downloaded.addAndGet(-written)
                lastException = e
                if (attempt < MAX_SEGMENT_RETRIES - 1) {
                    delay(500L * (attempt + 1))
                }
            }
        }

        onSegmentFailure(state, scaleChannel)
        throw lastException!!
    }

    private suspend fun onSegmentComplete(
        state: SchedulerState,
        segmentBytes: Long,
        durationMs: Long,
        scaleChannel: Channel<Int>
    ) {
        val speedBps = segmentBytes.toDouble() / (durationMs / 1000.0)

        state.ewmaSpeed = if (state.ewmaSpeed == 0.0) speedBps
        else EWMA_ALPHA * speedBps + (1 - EWMA_ALPHA) * state.ewmaSpeed

        when (state.phase) {
            SchedulerPhase.Probe -> handleProbeComplete(state, speedBps, scaleChannel)
            SchedulerPhase.AIMD -> handleAIMDUpdate(state, segmentBytes, scaleChannel)
            SchedulerPhase.Formula -> {}
        }
    }

    private suspend fun handleProbeComplete(
        state: SchedulerState,
        speedBps: Double,
        scaleChannel: Channel<Int>
    ) {
        state.mutex.withLock {
            state.probeSamples.add(speedBps)

            if (state.probeSamples.size >= PROBE_SAMPLE_COUNT) {
                transitionToFormula(state, scaleChannel)
            }
        }
    }

    private fun transitionToFormula(state: SchedulerState, scaleChannel: Channel<Int>) {
        state.phase = SchedulerPhase.Formula

        val optimalN = solveOptimalN(state)
        val chunkSize = (state.ewmaSpeed * TARGET_CHUNK_DURATION_SEC).toLong()
            .coerceIn(CHUNK_MIN, CHUNK_MAX)

        state.currentChunkSize = chunkSize
        state.targetN.set(optimalN)
        state.windowStartTime = System.currentTimeMillis()
        state.windowChunkCount.set(0)
        state.windowBytes.set(0L)
        state.lastWindowThroughput = 0.0
        state.windowsSinceCalibration = 0
        state.phase = SchedulerPhase.AIMD

        scaleChannel.trySend(optimalN)
    }

    private fun solveOptimalN(state: SchedulerState): Int {
        val samples = state.probeSamples
        if (samples.isEmpty()) return 1

        val mu = samples.average()
        if (mu <= 0) return 1

        val variance = if (samples.size > 1) {
            samples.sumOf { (it - mu).pow(2) } / (samples.size - 1)
        } else {
            (0.2 * mu).pow(2)
        }
        val sigma = sqrt(variance).coerceAtLeast(0.1 * mu)

        val s = state.fileSize.toDouble()
        val p = -(s / (mu * T0_OVERHEAD))
        val q = -(2.0 * s * sigma * sigma / (mu * mu * mu * T0_OVERHEAD))

        val discriminant = (q / 2.0).pow(2) + (p / 3.0).pow(3)

        val n: Double = if (discriminant >= 0) {
            val sqrtD = sqrt(discriminant)
            val u = cbrt(-q / 2.0 + sqrtD)
            val v = cbrt(-q / 2.0 - sqrtD)
            u + v
        } else {
            val r = sqrt(-(p / 3.0).pow(3))
            val theta = acos((-q / (2.0 * r)).coerceIn(-1.0, 1.0))
            val roots = (0..2).map { k ->
                2.0 * cbrt(r) * cos((theta + 2.0 * Math.PI * k) / 3.0)
            }
            roots.filter { it > 0 }.minOrNull() ?: 1.0
        }

        return n.roundToInt().coerceIn(1, N_MAX)
    }

    private suspend fun handleAIMDUpdate(
        state: SchedulerState,
        segmentBytes: Long,
        scaleChannel: Channel<Int>
    ) {
        state.windowBytes.addAndGet(segmentBytes)
        val count = state.windowChunkCount.incrementAndGet()

        if (count >= AIMD_WINDOW_CHUNKS) {
            state.mutex.withLock {
                if (state.windowChunkCount.get() < AIMD_WINDOW_CHUNKS) return@withLock

                val elapsed = (System.currentTimeMillis() - state.windowStartTime) / 1000.0
                if (elapsed <= 0) return@withLock
                val windowThroughput = state.windowBytes.get().toDouble() / elapsed

                val prev = state.lastWindowThroughput
                var newN = state.targetN.get()

                if (prev > 0) {
                    val delta = (windowThroughput - prev) / prev
                    when {
                        delta > AIMD_INCREASE_THRESHOLD && newN < N_MAX -> {
                            newN++
                        }
                        delta < AIMD_DECREASE_THRESHOLD && newN > 1 -> {
                            newN = maxOf(1, (newN * 0.8).toInt())
                        }
                        else -> {
                            if (Math.random() < AIMD_EXPLORE_PROBABILITY) {
                                val dir = if (Math.random() < 0.5) 1 else -1
                                newN = (newN + dir).coerceIn(1, N_MAX)
                            }
                        }
                    }
                }

                state.currentChunkSize = (state.ewmaSpeed * TARGET_CHUNK_DURATION_SEC).toLong()
                    .coerceIn(CHUNK_MIN, CHUNK_MAX)

                state.targetN.set(newN)
                state.lastWindowThroughput = windowThroughput
                state.windowChunkCount.set(0)
                state.windowBytes.set(0L)
                state.windowStartTime = System.currentTimeMillis()

                state.windowsSinceCalibration++
                if (state.windowsSinceCalibration >= RECALIBRATION_WINDOWS) {
                    state.windowsSinceCalibration = 0
                    state.probeSamples.clear()
                    state.probeSamples.add(state.ewmaSpeed)
                    val recalN = solveOptimalN(state)
                    if (kotlin.math.abs(recalN - newN) > (newN * 0.2).toInt()) {
                        newN = recalN
                        state.targetN.set(newN)
                    }
                }

                scaleChannel.trySend(newN)
            }
        }
    }

    private fun onSegmentFailure(state: SchedulerState, scaleChannel: Channel<Int>) {
        val failures = state.consecutiveFailures.incrementAndGet()
        if (failures >= ANOMALY_FAILURE_THRESHOLD) {
            val newN = maxOf(1, state.targetN.get() / 2)
            val newC = maxOf(CHUNK_MIN, state.currentChunkSize / 2)
            state.targetN.set(newN)
            state.currentChunkSize = newC
            state.consecutiveFailures.set(0)
            state.windowChunkCount.set(0)
            state.windowBytes.set(0L)
            state.windowStartTime = System.currentTimeMillis()
            state.lastWindowThroughput = 0.0
            scaleChannel.trySend(newN)
        }
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

    private suspend fun trackProgress(downloaded: AtomicLong, total: Long, @Suppress("UNUSED_PARAMETER") state: SchedulerState) {
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()
        var smoothedSpeed = 0L

        while (true) {
            delay(500L)
            val currentBytes = downloaded.get()
            val now = System.currentTimeMillis()
            val elapsed = now - lastTime

            val speed = if (elapsed >= 500) {
                val instantSpeed = (currentBytes - lastBytes) * 1000 / elapsed
                lastBytes = currentBytes
                lastTime = now
                if (smoothedSpeed == 0L) instantSpeed
                else (smoothedSpeed * 3 + instantSpeed) / 4
            } else {
                smoothedSpeed
            }
            smoothedSpeed = speed

            val progress = currentBytes.toFloat() / total.toFloat()
            val eta = if (speed > 0) (total - currentBytes) / speed else -1L

            withContext(Dispatchers.Main) {
                downloadSpeed = speed
                downloadedBytes = currentBytes
                downloadProgress = progress
                etaSeconds = eta
            }

            progressListener?.onProgress(progress, currentBytes, total, speed, eta)
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
