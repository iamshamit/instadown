package com.instadown.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private val bridge by lazy { PythonBridge(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            InstaDownTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScreen(
                        sharedUrl = null,
                        bridge = bridge,
                        onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    )
                }
            }
        }
    }


}

private sealed interface UiState {
    data object Idle : UiState
    data class Working(val url: String) : UiState
    data class Ready(val url: String, val files: List<DownloadedFile>) : UiState
    data class Error(val url: String, val message: String) : UiState
}

private val igBrush: Brush
    get() = Brush.linearGradient(
        colors = IgGradientColors,
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )

@Composable
fun AppIcon(size: Dp = 68.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f))
            .background(igBrush),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Download,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

@Composable
private fun AccentLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.5.dp)
            .background(igBrush.copy(alpha = 0.6f)),
    )
}

private fun Brush.copy(alpha: Float): Brush = Brush.linearGradient(
    colors = IgGradientColors.map { it.copy(alpha = alpha) },
    start = Offset(0f, 0f),
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
)

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
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current

    val filled = manualUrl.isNotBlank()
    val isWorking = state is UiState.Working

    var lastScroll by remember { mutableIntStateOf(0) }
    var fabVisible by remember { mutableStateOf(true) }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }.collect { current ->
            fabVisible = current <= lastScroll || current == 0
            lastScroll = current
        }
    }

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

    fun handleDownload() {
        if (!filled || isWorking) return
        val url = manualUrl.trim()
        state = UiState.Working(url)
        scope.launch {
            val r = withContext(Dispatchers.IO) { bridge.download(url) }
            state = if (r.success) UiState.Ready(url, r.files)
                    else UiState.Error(url, r.error ?: "unknown error")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "InstaDown",
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        letterSpacing = (-0.5).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                AccentLine()

                // ── Input card ──────────────────────────────────────────────
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
                    val borderBrush = if (filled) igBrush
                                      else SolidColor(MaterialTheme.colorScheme.outline)
                    val borderWidth = if (filled) 1.5.dp else 1.dp

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(borderWidth, borderBrush, RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // URL row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Icon(
                                Icons.Filled.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                modifier = Modifier.size(15.dp),
                            )
                            androidx.compose.foundation.text.BasicTextField(
                                value = manualUrl,
                                onValueChange = { manualUrl = it; if (state !is UiState.Idle) state = UiState.Idle },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
                                ),
                                cursorBrush = SolidColor(Color(0xFF833AB4)),
                                modifier = Modifier.weight(1f),
                                decorationBox = { inner ->
                                    if (manualUrl.isEmpty()) {
                                        Text(
                                            "https://www.instagram.com/p/…",
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                                        )
                                    }
                                    inner()
                                },
                            )
                            if (filled) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { manualUrl = ""; state = UiState.Idle },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Clear",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(11.dp),
                                    )
                                }
                            }
                        }

                        // Download button
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (filled) igBrush
                                    else Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                    ),
                                )
                                .clickable(
                                    enabled = filled && !isWorking,
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) { handleDownload() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (isWorking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.5.dp,
                                        color = Color.White,
                                        trackColor = Color.White.copy(alpha = 0.22f),
                                    )
                                    Text(
                                        "Downloading…",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Download,
                                        contentDescription = null,
                                        tint = if (filled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        "Download",
                                        color = if (filled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Error banner ─────────────────────────────────────────────
                if (state is UiState.Error) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 4.dp)
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.20f), RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFEF4444).copy(alpha = 0.08f))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(18.dp).padding(top = 1.dp),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    "Download failed",
                                    color = Color(0xFFEF4444),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    (state as UiState.Error).message,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                )
                            }
                        }
                    }
                }

                // ── Results ───────────────────────────────────────────────────
                if (state is UiState.Ready) {
                    val s = state as UiState.Ready
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (s.files.size > 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(igBrush)
                                    .clickable {
                                        var saved = 0; var failed = 0
                                        s.files.forEach { file ->
                                            try { SaveHelper.saveToDownloads(context, File(file.path)); saved++ }
                                            catch (e: Exception) { failed++ }
                                        }
                                        val msg = if (failed == 0) "Saved $saved files to ${ThemePrefs.getStorageLabel()}"
                                                  else "Saved $saved, failed $failed"
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(Icons.Filled.Download, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Text("Save All (${s.files.size})", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        s.files.forEach { file ->
                            FileCard(file = file, onSave = {
                                try {
                                    SaveHelper.saveToDownloads(context, File(file.path))
                                    Toast.makeText(context, "Saved — ${file.name}", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            })
                        }
                    }
                }

                // ── Empty state ───────────────────────────────────────────────
                val emptyAlpha by animateFloatAsState(
                    targetValue = if (filled) 0.25f else 1f,
                    animationSpec = tween(300),
                    label = "emptyAlpha",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(horizontal = 40.dp, vertical = 40.dp)
                        .alpha(emptyAlpha),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        AppIcon(size = 72.dp)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Download Instagram Posts",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                letterSpacing = (-0.3).sp,
                            )
                            Text(
                                "Paste a link above, or share a post\ndirectly from Instagram",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                lineHeight = 23.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(88.dp))
            }

            // ── FAB ──────────────────────────────────────────────────────────
            AnimatedVisibility(
                visible = fabVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(igBrush)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            val text = clipboardManager.getText()?.text
                            if (!text.isNullOrBlank()) manualUrl = text.trim()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ContentPaste,
                        contentDescription = "Paste URL",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FileCard(file: DownloadedFile, onSave: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AsyncImage(
                model = File(file.path),
                contentDescription = file.name,
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(file.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(file.sizeHuman, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(igBrush)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { onSave() },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Download, null, tint = Color.White, modifier = Modifier.size(17.dp))
                Text("Save to Downloads", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
