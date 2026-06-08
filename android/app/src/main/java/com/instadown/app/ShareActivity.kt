package com.instadown.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ShareActivity : ComponentActivity() {

    private val bridge by lazy { PythonBridge(applicationContext) }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* proceed regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val url = extractUrl(intent) ?: run { finish(); return }

        setContent {
            InstaDownTheme {
                ShareSheet(
                    url = url,
                    bridge = bridge,
                    onDismiss = { finish() },
                    onDownload = { selectedIndices ->
                        enqueueDownload(url, selectedIndices)
                        finish()
                    },
                )
            }
        }
    }

    private fun enqueueDownload(url: String, selectedIndices: IntArray) {
        val data = Data.Builder()
            .putString(DownloadWorker.KEY_URL, url)
            .putString(DownloadWorker.KEY_SELECTED, selectedIndices.joinToString(","))
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(applicationContext).enqueue(request)
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        val pattern = Regex("""https?://(?:www\.)?instagram\.com/\S+""")
        val match = pattern.find(text) ?: return null
        return match.value.trimEnd('.', ',', ')', ']', ';')
    }
}

// ── Sealed state ──────────────────────────────────────────────────────────────

private sealed interface SheetState {
    data object Loading : SheetState
    data class Ready(val result: MetadataResult) : SheetState
    data class Err(val message: String) : SheetState
    data object NotSignedIn : SheetState
}

// ── Gradient brush ────────────────────────────────────────────────────────────

private val igBrush: Brush
    get() = Brush.linearGradient(
        colors = IgGradientColors,
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )

// ── Root composable ───────────────────────────────────────────────────────────

@Composable
private fun ShareSheet(
    url: String,
    bridge: PythonBridge,
    onDismiss: () -> Unit,
    onDownload: (IntArray) -> Unit,
) {
    var state by remember { mutableStateOf<SheetState>(SheetState.Loading) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(url) {
        val result = withContext(Dispatchers.IO) { bridge.fetchMetadata(url) }
        val newState = when {
            !result.success && result.error?.contains("signed in", ignoreCase = true) == true ->
                SheetState.NotSignedIn
            !result.success -> SheetState.Err(result.error ?: "unknown error")
            else -> SheetState.Ready(result)
        }
        state = newState
        // Prefetch all thumbnails immediately so they're in cache when the grid renders
        if (newState is SheetState.Ready) {
            newState.result.thumbnails.forEach { thumbUrl ->
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context).data(thumbUrl).allowRgb565(true).build()
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x85000000))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { /* consume click — don't propagate to scrim */ },
        ) {
            SheetContent(url = url, state = state, onDismiss = onDismiss, onDownload = onDownload)
        }
    }
}

// ── Sheet body ────────────────────────────────────────────────────────────────

@Composable
private fun SheetContent(
    url: String,
    state: SheetState,
    onDismiss: () -> Unit,
    onDownload: (IntArray) -> Unit,
) {
    val selectedSet = remember { mutableStateOf(emptySet<Int>()) }

    LaunchedEffect(state) {
        if (state is SheetState.Ready) {
            selectedSet.value = (0 until state.result.count).toSet()
        }
    }

    Column(modifier = Modifier.padding(bottom = 28.dp)) {
        // Drag handle
        Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier.width(40.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }

        // Header row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(igBrush),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Download, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Save Post", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                val shortUrl = url.removePrefix("https://").removePrefix("www.").let {
                    if (it.length > 42) it.take(42) + "…" else it
                }
                Text(shortUrl, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp))
            }
        }

        // Divider
        Box(modifier = Modifier.fillMaxWidth().height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant))

        // Content area
        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
            when (val s = state) {
                SheetState.Loading -> ShimmerRow()
                SheetState.NotSignedIn -> NotSignedInContent(onDismiss = onDismiss)
                is SheetState.Err -> ErrContent(message = s.message)
                is SheetState.Ready -> ReadyContent(
                    result = s.result,
                    selectedSet = selectedSet.value,
                    onToggle = { i ->
                        selectedSet.value =
                            if (i in selectedSet.value) selectedSet.value - i
                            else selectedSet.value + i
                    },
                    onSelectAll = { selectedSet.value = (0 until s.result.count).toSet() },
                )
            }
        }

        // Footer buttons
        val canDownload = state is SheetState.Ready && selectedSet.value.isNotEmpty()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.weight(1f).height(50.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        onDismiss()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("Cancel", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            Box(
                modifier = Modifier.weight(1f).height(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (canDownload) igBrush
                        else Brush.linearGradient(listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surfaceVariant,
                        ))
                    )
                    .clickable(
                        enabled = canDownload,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) {
                        if (state is SheetState.Ready) onDownload(selectedSet.value.toIntArray())
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Filled.Download, null,
                        tint = if (canDownload) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp))
                    Text("Download", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = if (canDownload) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ── Content states ────────────────────────────────────────────────────────────

@Composable
private fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = 0f, targetValue = 600f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer_offset",
    )
    return Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceVariant,
        ),
        start = Offset(offset, offset),
        end = Offset(offset + 300f, offset + 300f),
    )
}

@Composable
private fun ShimmerRow() {
    val brush = shimmerBrush()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) {
            Box(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).background(brush))
        }
    }
}

@Composable
private fun ReadyContent(
    result: MetadataResult,
    selectedSet: Set<Int>,
    onToggle: (Int) -> Unit,
    onSelectAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (result.type == "carousel") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(result.thumbnails) { index, thumbUrl ->
                    val selected = index in selectedSet
                    Box(
                        modifier = Modifier.size(80.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                if (selected) 2.dp else 0.dp,
                                if (selected) igBrush else SolidColor(Color.Transparent),
                                RoundedCornerShape(12.dp),
                            )
                            .clickable(indication = null,
                                interactionSource = remember { MutableInteractionSource() }) {
                                onToggle(index)
                            },
                    ) {
                        val shimmerBrush = shimmerBrush()
                        val context = androidx.compose.ui.platform.LocalContext.current
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(thumbUrl)
                                .crossfade(true)
                                .allowRgb565(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().alpha(if (selected) 1f else 0.35f),
                            loading = {
                                Box(modifier = Modifier.fillMaxSize().background(shimmerBrush))
                            },
                        )
                        if (!selected) {
                            Box(
                                modifier = Modifier.fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Close, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${result.count} photo${if (result.count != 1) "s" else ""}",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("·", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Select all",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(indication = null,
                        interactionSource = remember { MutableInteractionSource() }) { onSelectAll() },
                )
            }
        } else {
            Text("1 photo ready", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NotSignedInContent(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Sign in to Instagram first to enable downloads.",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier.fillMaxWidth().height(46.dp)
                .clip(RoundedCornerShape(12.dp)).background(igBrush)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    onDismiss()
                    context.startActivity(
                        Intent(context, SettingsActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("Open Settings", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ErrContent(message: String) {
    Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
}
