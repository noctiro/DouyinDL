package com.noctiro.douyindl.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noctiro.douyindl.MainViewModel
import com.noctiro.douyindl.ParseState

@Composable
internal fun InputSection(vm: MainViewModel) {
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
