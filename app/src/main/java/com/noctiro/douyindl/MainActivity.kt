package com.noctiro.douyindl

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noctiro.douyindl.ui.screen.MainScreen
import com.noctiro.douyindl.ui.theme.DouyinDLTheme

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
