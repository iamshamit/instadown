# Background Download Sheet — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the blocking share-intent download with an instant bottom sheet that fetches metadata in the background, lets the user pick carousel images, then dismisses and downloads silently via WorkManager.

**Architecture:** A new `ShareBottomSheetActivity` (transparent theme, appears over Instagram) receives the `ACTION_SEND` intent. It immediately calls `fetch_metadata()` (a new Python function that iterates gallery-dl's extractor without writing files) to get image count + thumbnail URLs. The user picks images and taps Download — the sheet closes, a `DownloadWorker` (WorkManager) does the actual download in background, and a notification shows progress + completion.

**Tech Stack:** Jetpack Compose · Material 3 · WorkManager 2.9.1 · Coil 2.7.0 (already present) · Chaquopy/gallery-dl · Kotlin coroutines

---

## File Map

| Action | File |
|--------|------|
| Create | `android/app/src/main/java/com/instadown/app/NotificationHelper.kt` |
| Create | `android/app/src/main/java/com/instadown/app/DownloadWorker.kt` |
| Create | `android/app/src/main/java/com/instadown/app/ShareBottomSheetActivity.kt` |
| Modify | `android/app/src/main/python/instadown_android.py` |
| Modify | `android/app/src/main/java/com/instadown/app/PythonBridge.kt` |
| Modify | `android/app/src/main/java/com/instadown/app/InstaDownApp.kt` |
| Modify | `android/app/src/main/java/com/instadown/app/MainActivity.kt` |
| Modify | `android/app/src/main/AndroidManifest.xml` |
| Modify | `android/app/src/main/res/values/themes.xml` |
| Modify | `android/app/src/main/res/values/strings.xml` |
| Modify | `android/app/build.gradle.kts` |

---

## Task 1 — Add WorkManager dependency, transparent theme, notification strings

**Files:**
- Modify: `android/app/build.gradle.kts`
- Modify: `android/app/src/main/res/values/themes.xml`
- Modify: `android/app/src/main/res/values/strings.xml`

- [ ] **Add WorkManager to `build.gradle.kts`** — in the `dependencies` block after the existing coil line:

```kotlin
implementation("androidx.work:work-runtime-ktx:2.9.1")
```

- [ ] **Add transparent theme to `themes.xml`** — append inside `<resources>`:

```xml
<style name="Theme.InstaDown.Transparent" parent="android:Theme.Material.Light.NoActionBar">
    <item name="android:windowIsTranslucent">true</item>
    <item name="android:windowBackground">@android:color/transparent</item>
    <item name="android:windowContentOverlay">@null</item>
    <item name="android:windowNoTitle">true</item>
    <item name="android:windowIsFloating">false</item>
    <item name="android:backgroundDimEnabled">false</item>
    <item name="android:statusBarColor">@android:color/transparent</item>
    <item name="android:navigationBarColor">@android:color/transparent</item>
</style>
```

- [ ] **Add notification strings to `strings.xml`** — append inside `<resources>`:

```xml
<string name="notif_channel_name">Downloads</string>
<string name="notif_failed">Download failed — tap to retry</string>
```

- [ ] **Verify build** — run from `android/` directory:

```
./gradlew assembleRelease
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Commit:**

```
git add android/app/build.gradle.kts android/app/src/main/res/values/themes.xml android/app/src/main/res/values/strings.xml
git commit -m "build: add WorkManager dep, transparent theme, notification strings"
```

---

## Task 2 — Python: add `warmup()` and `fetch_metadata()`

**Files:**
- Modify: `android/app/src/main/python/instadown_android.py`

- [ ] **Append both functions to the end of `instadown_android.py`:**

```python
def warmup() -> None:
    """Pre-import gallery-dl modules to eliminate first-call import latency."""
    import gallery_dl.extractor  # noqa: F401
    import gallery_dl.config     # noqa: F401


def fetch_metadata(url: str, cookies_path: str) -> dict[str, Any]:
    """Collect image metadata without downloading files.

    Uses gallery-dl's extractor iterator — only fetches post JSON from
    Instagram, never writes image files to disk.

    Returns:
        {"ok": True,  "count": N, "thumbnails": [...], "type": "single"|"carousel"}
        {"ok": False, "error": "..."}
    """
    if not url:
        return {"ok": False, "error": "empty URL"}
    if not cookies_path or not Path(cookies_path).is_file():
        return {"ok": False, "error": "not signed in"}

    try:
        import gallery_dl.extractor as gdl_ext
        import gallery_dl.config as gdl_cfg

        gdl_cfg.set(("extractor", "instagram"), "cookies", cookies_path)
        gdl_cfg.set(("extractor", "instagram"), "videos", False)
        gdl_cfg.set(("output",), "quiet", "true")

        extractor = gdl_ext.find(url)
        if extractor is None:
            return {"ok": False, "error": "unsupported URL — only instagram.com links work"}

        extractor.initialize()

        thumbnails: list[str] = []
        for msg in extractor:
            if msg[0] == 1:  # Message.Url
                _, item_url, keywords = msg
                thumb = (
                    keywords.get("thumbnail")
                    or keywords.get("display_url")
                    or str(item_url)
                )
                thumbnails.append(str(thumb))

        if not thumbnails:
            return {
                "ok": False,
                "error": "no media found — the post may be private, a Reel, or the link is wrong",
            }

        return {
            "ok": True,
            "count": len(thumbnails),
            "thumbnails": thumbnails,
            "type": "carousel" if len(thumbnails) > 1 else "single",
        }
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": str(exc)}
```

- [ ] **Commit:**

```
git add android/app/src/main/python/instadown_android.py
git commit -m "feat(python): add fetch_metadata() and warmup() to instadown_android"
```

---

## Task 3 — `PythonBridge`: add `MetadataResult` + `fetchMetadata()`

**Files:**
- Modify: `android/app/src/main/java/com/instadown/app/PythonBridge.kt`

- [ ] **Add `MetadataResult` data class** — append after the existing `DownloadResult` data class at the bottom of `PythonBridge.kt`:

```kotlin
data class MetadataResult(
    val success: Boolean,
    val count: Int = 0,
    val thumbnails: List<String> = emptyList(),
    val type: String = "single",
    val error: String? = null,
)
```

- [ ] **Add `fetchMetadata()` method** — append inside the `PythonBridge` class body, after `clearCookies()`:

```kotlin
fun fetchMetadata(url: String): MetadataResult {
    val cookiesFile = cookiesFile()
    if (!cookiesFile.exists()) {
        return MetadataResult(
            success = false,
            error = "Not signed in. Open Settings and sign in to Instagram first.",
        )
    }
    val raw: PyObject = try {
        module.callAttr("fetch_metadata", url, cookiesFile.absolutePath)
    } catch (t: Throwable) {
        return MetadataResult(success = false, error = "Python call failed: ${t.message}")
    }
    val json = raw.toString()
    return try {
        val obj = JSONObject(json)
        val ok = obj.optBoolean("ok", false)
        if (!ok) return MetadataResult(success = false, error = obj.optString("error", "unknown error"))
        val arr = obj.getJSONArray("thumbnails")
        val thumbs = (0 until arr.length()).map { arr.getString(it) }
        MetadataResult(
            success = true,
            count = obj.getInt("count"),
            thumbnails = thumbs,
            type = obj.optString("type", "single"),
        )
    } catch (t: Throwable) {
        MetadataResult(success = false, error = "Bad response from Python: $json")
    }
}
```

- [ ] **Commit:**

```
git add android/app/src/main/java/com/instadown/app/PythonBridge.kt
git commit -m "feat: add MetadataResult and fetchMetadata() to PythonBridge"
```

---

## Task 4 — Update `InstaDownApp`: pre-warm gallery-dl + create notification channel

**Files:**
- Modify: `android/app/src/main/java/com/instadown/app/InstaDownApp.kt`

- [ ] **Replace the entire file with:**

```kotlin
package com.instadown.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlin.concurrent.thread

class InstaDownApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        ThemePrefs.init(this)
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        createNotificationChannel()
        thread(isDaemon = true, name = "gdl-warmup") {
            try {
                Python.getInstance()
                    .getModule("instadown_android")
                    .callAttr("warmup")
            } catch (_: Throwable) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NotificationHelper.CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                enableVibration(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        lateinit var instance: InstaDownApp
            private set
    }
}
```

- [ ] **Commit:**

```
git add android/app/src/main/java/com/instadown/app/InstaDownApp.kt
git commit -m "feat: pre-warm gallery-dl at startup, create download notification channel"
```

---

## Task 5 — Create `NotificationHelper.kt`

**Files:**
- Create: `android/app/src/main/java/com/instadown/app/NotificationHelper.kt`

- [ ] **Create the file:**

```kotlin
package com.instadown.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_ID = "instadown_downloads"
    private const val NOTIF_ID = 1001

    fun showProgress(context: Context, count: Int) {
        val text = if (count == 1) "Downloading 1 photo…" else "Downloading $count photos…"
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    fun showComplete(context: Context, saved: Int) {
        val label = ThemePrefs.getStorageLabel()
        val text = if (saved == 1) "1 photo saved to $label" else "$saved photos saved to $label"
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    fun showFailure(context: Context, url: String) {
        val retryIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("prefill_url", url)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, retryIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_failed))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }
}
```

- [ ] **Commit:**

```
git add android/app/src/main/java/com/instadown/app/NotificationHelper.kt
git commit -m "feat: add NotificationHelper for download progress/complete/failure"
```

---

## Task 6 — Create `DownloadWorker.kt`

**Files:**
- Create: `android/app/src/main/java/com/instadown/app/DownloadWorker.kt`

- [ ] **Create the file:**

```kotlin
package com.instadown.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_URL = "url"
        // Comma-separated selected indices, e.g. "0,2,3". Empty string = save all.
        const val KEY_SELECTED = "selected_indices"
    }

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val selectedRaw = inputData.getString(KEY_SELECTED) ?: ""
        val selectedSet: Set<Int> = if (selectedRaw.isBlank()) emptySet()
            else selectedRaw.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

        val bridge = PythonBridge(applicationContext)
        if (!bridge.cookiesFile().exists()) {
            NotificationHelper.showFailure(applicationContext, url)
            return Result.failure()
        }

        val displayCount = if (selectedSet.isEmpty()) 1 else selectedSet.size
        NotificationHelper.showProgress(applicationContext, displayCount)

        val result = bridge.download(url)
        if (!result.success) {
            NotificationHelper.showFailure(applicationContext, url)
            return Result.failure()
        }

        val toSave = if (selectedSet.isEmpty()) result.files
            else result.files.filterIndexed { index, _ -> index in selectedSet }

        var saved = 0
        toSave.forEach { file ->
            try {
                SaveHelper.saveToDownloads(applicationContext, File(file.path))
                saved++
            } catch (_: Exception) {}
        }

        if (saved == 0) {
            NotificationHelper.showFailure(applicationContext, url)
            return Result.failure()
        }

        NotificationHelper.showComplete(applicationContext, saved)
        return Result.success()
    }
}
```

- [ ] **Commit:**

```
git add android/app/src/main/java/com/instadown/app/DownloadWorker.kt
git commit -m "feat: add DownloadWorker — background download via WorkManager"
```

---

## Task 7 — Update `AndroidManifest.xml`

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`

- [ ] **Replace the entire file with:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".InstaDownApp"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.InstaDown"
        android:usesCleartextTraffic="false"
        tools:targetApi="35">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.InstaDown">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".ShareBottomSheetActivity"
            android:exported="true"
            android:theme="@style/Theme.InstaDown.Transparent"
            android:taskAffinity=""
            android:excludeFromRecents="true">
            <intent-filter android:label="@string/share_target_label">
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>

        <activity
            android:name=".LoginActivity"
            android:exported="false"
            android:label="@string/login_title"
            android:theme="@style/Theme.InstaDown" />

        <activity
            android:name=".SettingsActivity"
            android:exported="false"
            android:label="@string/settings_title"
            android:theme="@style/Theme.InstaDown" />

    </application>
</manifest>
```

- [ ] **Commit:**

```
git add android/app/src/main/AndroidManifest.xml
git commit -m "feat: move share intent to ShareBottomSheetActivity, add POST_NOTIFICATIONS"
```

---

## Task 8 — Update `MainActivity.kt` — remove share handling

**Files:**
- Modify: `android/app/src/main/java/com/instadown/app/MainActivity.kt`

- [ ] **In `MainActivity.onCreate()`**, replace:

```kotlin
val sharedUrl = extractInstagramUrlFromIntent(intent)

setContent {
    InstaDownTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            MainScreen(
                sharedUrl = sharedUrl,
                bridge = bridge,
                onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
            )
        }
    }
}
```

with:

```kotlin
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
```

- [ ] **Delete `onNewIntent()` override** — remove these lines entirely:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    recreate()
}
```

- [ ] **Delete `extractInstagramUrlFromIntent()`** — remove the private function entirely:

```kotlin
private fun extractInstagramUrlFromIntent(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_SEND) return null
    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
    val match = Regex("""https?://(?:www\.)?instagram\.com/\S+""").find(text)
    return match?.value?.trimEnd('.', ',', ')', ']', ';')
}
```

- [ ] **Commit:**

```
git add android/app/src/main/java/com/instadown/app/MainActivity.kt
git commit -m "refactor: remove share intent handling from MainActivity"
```

---

## Task 9 — Create `ShareBottomSheetActivity.kt`

**Files:**
- Create: `android/app/src/main/java/com/instadown/app/ShareBottomSheetActivity.kt`

- [ ] **Create the file:**

```kotlin
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ShareBottomSheetActivity : ComponentActivity() {

    private val bridge by lazy { PythonBridge(applicationContext) }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* proceed regardless of result */ }

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
        val selectedStr = selectedIndices.joinToString(",")
        val data = Data.Builder()
            .putString(DownloadWorker.KEY_URL, url)
            .putString(DownloadWorker.KEY_SELECTED, selectedStr)
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
        val match = Regex("""https?://(?:www\.)?instagram\.com/\S+""").find(text)
        return match?.value?.trimEnd('.', ',', ')', ']', ';')
    }
}

// ── Sealed state ─────────────────────────────────────────────────────────────

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

    LaunchedEffect(url) {
        val result = withContext(Dispatchers.IO) { bridge.fetchMetadata(url) }
        state = when {
            !result.success && result.error?.contains("signed in", ignoreCase = true) == true ->
                SheetState.NotSignedIn
            !result.success -> SheetState.Err(result.error ?: "unknown error")
            else -> SheetState.Ready(result)
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
                ) { /* consume — don't propagate to scrim */ },
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
private fun ShimmerRow() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = 0f, targetValue = 600f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer_offset",
    )
    val brush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceVariant,
        ),
        start = Offset(offset, offset),
        end = Offset(offset + 300f, offset + 300f),
    )
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
                        AsyncImage(
                            model = thumbUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().alpha(if (selected) 1f else 0.35f),
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
        Text("Sign in to Instagram first to enable downloads.",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
```

- [ ] **Commit:**

```
git add android/app/src/main/java/com/instadown/app/ShareBottomSheetActivity.kt
git commit -m "feat: add ShareBottomSheetActivity with shimmer, thumbnail selection, gradient theme"
```

---

## Task 10 — Build, install, and verify

- [ ] **Build release APK:**

```
cd android
./gradlew assembleRelease
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Install on device:**

```
adb install -r app/build/outputs/apk/release/app-release.apk
```

- [ ] **Test share flow:**
  1. Open Instagram, find a carousel post, tap Share → InstaDown
  2. Verify: bottom sheet slides up over Instagram immediately
  3. Verify: shimmer animation shows while metadata loads (~2–3s)
  4. Verify: thumbnails appear with all selected (gradient border)
  5. Tap one thumbnail — verify it dims + X overlay appears
  6. Tap "Select all" — verify all re-selected
  7. Tap Download — verify sheet closes instantly, Instagram stays visible
  8. Verify: "Downloading N photos…" notification appears
  9. Verify: "N photos saved to Downloads/InstaDown" notification after ~30s

- [ ] **Test single image post** — repeat share flow with a non-carousel post, verify "1 photo ready" text shown (no thumbnail grid).

- [ ] **Test not-signed-in state** — sign out in Settings, share a post, verify "Sign in first" message + "Open Settings" button appears in sheet.

- [ ] **Push and create release:**

```
cd ..
git push origin main
gh release create v0.2.0 \
  "android/app/build/outputs/apk/release/app-release.apk#InstaDown-v0.2.0.apk" \
  --title "InstaDown v0.2.0 — Background downloads" \
  --notes "Share a post → instant bottom sheet → download happens in background. No more waiting."
```
