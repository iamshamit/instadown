package com.instadown.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The main (and only) entry-point activity. Receives either:
 *  - the LAUNCHER intent (user tapped the app icon)
 *  - a SEND/text intent from the system share sheet (user shared a URL)
 */
class MainActivity : ComponentActivity() {

    private val bridge by lazy { PythonBridge(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedUrl = extractInstagramUrlFromIntent(intent)

        setContent {
            InstaDownTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        sharedUrl = sharedUrl,
                        bridge = bridge,
                        onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-launch with the new share intent. The cleanest way is to
        // recreate() so the new URL flows through onCreate.
        setIntent(intent)
        recreate()
    }

    /** Pulls the first instagram.com URL out of an EXTRA_TEXT blob. */
    private fun extractInstagramUrlFromIntent(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        val match = Regex("""https?://(?:www\.)?instagram\.com/\S+""").find(text)
        return match?.value?.trimEnd('.', ',', ')', ']', ';')
    }
}

private sealed interface UiState {
    data object Idle : UiState
    data class Working(val url: String) : UiState
    data class Ready(val url: String, val files: List<DownloadedFile>) : UiState
    data class Error(val url: String, val message: String) : UiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    sharedUrl: String?,
    bridge: PythonBridge,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UiState>(UiState.Idle) }
    var manualUrl by remember { mutableStateOf("") }

    // If we were launched via the share sheet, kick off the download
    // automatically once we have the URL.
    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null && state is UiState.Idle) {
            manualUrl = sharedUrl
            state = UiState.Working(sharedUrl)
            scope.launch {
                val result = withContext(Dispatchers.IO) { bridge.download(sharedUrl) }
                state = if (result.success) {
                    UiState.Ready(sharedUrl, result.files)
                } else {
                    UiState.Error(sharedUrl, result.error ?: "unknown error")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("InstaDown") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Manual-entry card (always visible).
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste an Instagram post URL",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = manualUrl,
                        onValueChange = { manualUrl = it },
                        placeholder = { Text("https://www.instagram.com/p/…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            if (manualUrl.isBlank()) return@Button
                            val url = manualUrl.trim()
                            state = UiState.Working(url)
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { bridge.download(url) }
                                state = if (r.success) {
                                    UiState.Ready(url, r.files)
                                } else {
                                    UiState.Error(url, r.error ?: "unknown error")
                                }
                            }
                        },
                        enabled = manualUrl.isNotBlank() && state !is UiState.Working,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download")
                    }
                }
            }

            // State-dependent UI.
            when (val s = state) {
                UiState.Idle -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("How it works", fontWeight = FontWeight.SemiBold)
                            Text(
                                "1. Open Instagram, find a post, tap Share, then InstaDown.\n" +
                                    "2. Or paste a URL above and tap Download.\n" +
                                    "3. Tap Save to copy the image to Downloads/InstaDown/.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                is UiState.Working -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Downloading from Instagram…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                is UiState.Ready -> {
                    s.files.forEach { file ->
                        FileCard(
                            file = file,
                            onSave = {
                                val src = File(file.path)
                                try {
                                    val uri = SaveHelper.saveToDownloads(context, src)
                                    Toast.makeText(
                                        context,
                                        "Saved to Downloads/InstaDown/${file.name}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            },
                        )
                    }
                }

                is UiState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                Icons.Filled.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Column {
                                Text("Download failed", fontWeight = FontWeight.SemiBold)
                                Text(
                                    s.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileCard(file: DownloadedFile, onSave: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Thumbnail: render via Coil from the local file.
                AsyncImage(
                    model = File(file.path),
                    contentDescription = file.name,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                    )
                    Text(
                        file.sizeHuman,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save to Downloads/InstaDown/")
            }
        }
    }
}
