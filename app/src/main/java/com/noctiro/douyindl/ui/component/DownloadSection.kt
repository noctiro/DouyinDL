package com.noctiro.douyindl.ui.component

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noctiro.douyindl.MainViewModel
import com.noctiro.douyindl.download.DownloadState
import com.noctiro.douyindl.util.formatEta
import com.noctiro.douyindl.util.formatFileSize

@Composable
internal fun DownloadSection(vm: MainViewModel) {
    when (vm.downloadState) {
        DownloadState.Idle -> {
            FilledTonalButton(
                onClick = { vm.downloadVideo() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (vm.totalBytes > 0) "下载视频 (${formatFileSize(vm.totalBytes)})"
                        else "下载视频"
                    )
                }
            }
        }
        DownloadState.Downloading -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("下载中...", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${(vm.downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (vm.downloadProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { vm.downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val sizeText = if (vm.totalBytes > 0) {
                        "${formatFileSize(vm.downloadedBytes)} / ${formatFileSize(vm.totalBytes)}"
                    } else {
                        formatFileSize(vm.downloadedBytes)
                    }
                    Text(sizeText, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    val speedAndEta = buildString {
                        if (vm.downloadSpeed > 0) append("${formatFileSize(vm.downloadSpeed)}/s")
                        if (vm.etaSeconds > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("剩余 ${formatEta(vm.etaSeconds)}")
                        }
                    }
                    if (speedAndEta.isNotEmpty()) {
                        Text(speedAndEta, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                }
                OutlinedButton(
                    onClick = { vm.cancelDownload() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("取消下载")
                    }
                }
            }
        }
        DownloadState.Completed -> {
            val context = LocalContext.current
            FilledTonalButton(
                onClick = {
                    try {
                        val uri = vm.getDownloadedFileUri() ?: return@FilledTonalButton
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) { }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("打开视频")
                }
            }
        }
        DownloadState.Failed -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.downloadFailReason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                FilledTonalButton(
                    onClick = { vm.downloadVideo() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("下载失败，点击重试")
                    }
                }
            }
        }
    }
}
