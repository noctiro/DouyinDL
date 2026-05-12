package com.noctiro.douyindl.ui.screen

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noctiro.douyindl.MainViewModel
import com.noctiro.douyindl.ParseState
import com.noctiro.douyindl.ui.component.ErrorCard
import com.noctiro.douyindl.ui.component.InputSection
import com.noctiro.douyindl.ui.component.VideoInfoCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAbout by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val versionText = remember(context) {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = packageInfo.versionName ?: "unknown"
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
        "版本: $versionName ($versionCode)"
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("DouyinDL") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = versionText,
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
                TextButton(onClick = { showAbout = false }) { Text("关闭") }
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
                    IconButton(onClick = { showAbout = true }) {
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
