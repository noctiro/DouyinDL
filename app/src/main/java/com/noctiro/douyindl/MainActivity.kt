package com.noctiro.douyindl

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.noctiro.douyindl.data.VideoInfo
import com.noctiro.douyindl.ui.theme.DouyinDLTheme
import androidx.core.net.toUri
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DouyinDLTheme {
                val vm: MainViewModel = viewModel()
                HandleShareIntent(intent, vm)
                MainScreen(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
private fun HandleShareIntent(intent: Intent?, vm: MainViewModel) {
    LaunchedEffect(intent) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                vm.updateInput(sharedText)
                vm.parseUrl()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAbout by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { },
            icon = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("DouyinDL") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "作者: Noctiro",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "License: AGPL-3.0",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "一个简洁的抖音无水印视频下载工具",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW,
                        "https://github.com/noctiro/DouyinDL".toUri())
                    context.startActivity(intent)
                }) { Text("GitHub") }
            },
            dismissButton = {
                TextButton(onClick = { }) { Text("关闭") }
            }
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("抖音视频下载") },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.Info, contentDescription = "关于")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            InputSection(vm)
            AnimatedVisibility(
                visible = vm.parseState == ParseState.Success && vm.videoInfo != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                vm.videoInfo?.let { VideoInfoCard(it, vm) }
            }
            AnimatedVisibility(
                visible = vm.parseState == ParseState.Error,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ErrorCard(vm.errorMessage ?: "未知错误")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InputSection(vm: MainViewModel) {
    val context = LocalContext.current

    OutlinedTextField(
        value = vm.inputUrl,
        onValueChange = { vm.updateInput(it) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("抖音分享内容") },
        placeholder = { Text("粘贴分享文本或链接，自动识别") },
        singleLine = false,
        maxLines = 3,
        trailingIcon = {
            Row {
                if (vm.inputUrl.isNotEmpty()) {
                    IconButton(onClick = { vm.reset() }) {
                        Icon(Icons.Default.Clear, contentDescription = "清除")
                    }
                }
                IconButton(onClick = {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    val text = cm?.primaryClip?.getItemAt(0)?.text?.toString()
                    if (!text.isNullOrBlank()) {
                        vm.updateInput(text)
                        vm.parseUrl()
                    }
                }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "粘贴")
                }
            }
        }
    )

    FilledTonalButton(
        onClick = { vm.parseUrl() },
        modifier = Modifier.fillMaxWidth(),
        enabled = vm.inputUrl.isNotBlank() && vm.parseState != ParseState.Loading
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (vm.parseState == ParseState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("解析中...")
            } else {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("解析链接")
            }
        }
    }
}

@Composable
private fun VideoInfoCard(info: VideoInfo, vm: MainViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            info.coverUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = "视频封面",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            Text(
                text = info.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "ID: ${info.videoId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )
            DownloadSection(vm)
        }
    }
}

@Composable
private fun DownloadSection(vm: MainViewModel) {
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
                    if (vm.downloadSpeed > 0) {
                        Text("${formatFileSize(vm.downloadSpeed)}/s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
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

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "$bytes B"
    else String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
