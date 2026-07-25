package com.scorchedphoto.app.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService

class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stackTrace = intent.getStringExtra(EXTRA_STACK_TRACE).orEmpty()
        setContent {
            MaterialTheme {
                CrashScreen(
                    stackTrace = stackTrace,
                    onCopy = { copyToClipboard(stackTrace) },
                    onShare = { shareText(stackTrace) },
                    onClose = {
                        finishAffinity()
                        Runtime.getRuntime().exit(0)
                    },
                )
            }
        }
    }

    private fun copyToClipboard(text: String) {
        getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("Crash log", text))
    }

    private fun shareText(text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(sendIntent, "Share crash log"))
    }

    companion object {
        const val EXTRA_STACK_TRACE = "stack_trace"
    }
}

@Composable
private fun CrashScreen(stackTrace: String, onCopy: () -> Unit, onShare: () -> Unit, onClose: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Scorched Photo Crashed", style = MaterialTheme.typography.headlineSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onCopy) { Text("Copy") }
                Button(onClick = onShare) { Text("Share") }
                Button(onClick = onClose) { Text("Close") }
            }
            Text(
                text = stackTrace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}
