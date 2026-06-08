# Background Download Sheet — Design Spec

**Date:** 2026-06-08
**Status:** Approved

---

## Problem

The current download flow blocks the user for ~30 seconds while gallery-dl fetches from Instagram. The user stares at a spinner in the app and cannot do anything else. The fix is to move the download off the user's critical path entirely.

---

## Scope

Share intent flow only. The paste-URL flow in MainActivity is unchanged.

---

## Architecture

### New files

| File | Purpose |
|---|---|
| `ShareBottomSheetActivity.kt` | Transparent Activity that owns the bottom sheet UI. Receives the `ACTION_SEND` share intent. |
| `DownloadWorker.kt` | `CoroutineWorker` (WorkManager). Performs the actual image download + save in background. Posts progress and completion notifications. |
| `NotificationHelper.kt` | Creates the notification channel, builds and updates progress / completion / failure notifications. |

### Modified files

| File | Change |
|---|---|
| `instadown_android.py` | Add `fetch_metadata(url, cookies_path)` — metadata-only fetch, no file download. |
| `AndroidManifest.xml` | Move `ACTION_SEND` intent filter from `MainActivity` to `ShareBottomSheetActivity`. Add `POST_NOTIFICATIONS` permission. Add `ShareBottomSheetActivity` with transparent/bottom-sheet theme. |
| `InstaDownApp.kt` | Pre-import gallery-dl in a background thread at app start to eliminate first-call import cost. |
| `MainActivity.kt` | Remove share intent handling. Keep paste-URL flow unchanged. |
| `build.gradle.kts` | Add WorkManager dependency: `androidx.work:work-runtime-ktx:2.9.1`. |

---

## Data Flow

```
Instagram share intent
        │
        ▼
ShareBottomSheetActivity.onCreate()
        │  launch(Dispatchers.IO) immediately
        ▼
PythonBridge.fetchMetadata(url)
        │  calls fetch_metadata() in Python
        │  gallery-dl extractor only — no image download
        │  returns in ~2–3 seconds
        ▼
MetadataResult {
    count: Int,
    thumbnailUrls: List<String>,
    type: "single" | "carousel"
}
        │
        ▼
Sheet updates: shimmer → thumbnail grid with checkboxes
        │  user selects images (all selected by default)
        │  taps Download
        ▼
WorkManager.enqueue(DownloadWorker, inputData = { url, cookies, selectedIndices })
        │  sheet dismisses instantly
        ▼
DownloadWorker (background)
        │  calls PythonBridge.download() with selected indices
        │  saves via SaveHelper
        │  posts notifications
        ▼
Completion notification → tap opens save folder
```

---

## Python: `fetch_metadata()`

```python
def fetch_metadata(url: str, cookies_path: str) -> dict:
    """
    Fetch post metadata only — image count, thumbnail URLs, type.
    Does NOT download any image files.
    Returns {"ok": True, "count": N, "thumbnails": [...], "type": "single|carousel"}
    or      {"ok": False, "error": "..."}
    """
```

Uses gallery-dl's extractor `.items()` iterator to collect image metadata without writing files. Configures `gallery-dl` with `"skip": true` and no base-directory to prevent any file I/O. Collects `thumbnail_url` or `display_url` from each item's metadata dict. Returns after iterating all items — no download jobs run.

**Performance target:** < 3 seconds on a good connection.

---

## Bottom Sheet UI

### Appearance
- Slides up to **~40% screen height**
- Matches `InstaDownTheme` (OLED Black / Light, auto)
- Background: `MaterialTheme.colorScheme.surface`
- Corner radius: 24dp top corners
- Dim scrim behind sheet: `Color(0x85000000)`

### Layout (top → bottom)
1. **Drag handle** — centered pill, 40×4dp, `outlineVariant` color
2. **Header row** — gradient app icon (32dp rounded square) + "Save Post" title (18sp bold) + ✕ close button
3. **URL line** — `instagram.com/p/Xk3f...` truncated, `onSurfaceVariant`, 13sp
4. **Divider** — 1dp `outlineVariant`
5. **Content area** — shimmer OR thumbnail grid OR single-image label
6. **Footer** — Cancel (outlined) + Download (gradient) buttons

### Shimmer state
Two rounded rectangles (80×80dp) with shimmer animation. Shown while `fetch_metadata` is running.

### Loaded state — carousel
- Horizontal `LazyRow` of 80dp square thumbnail chips
- Each chip: Coil `AsyncImage`, `RoundedCornerShape(12dp)`, gradient border when selected
- Deselected: dimmed to 40% alpha + ✕ overlay
- All selected by default
- Below row: `"{N} photos · Select all"` — tapping "Select all" re-selects all

### Loaded state — single image
- Text: `"1 photo ready"`, `onSurfaceVariant`

### Download button states
- ≥1 selected → gradient background, "Download" label, enabled
- 0 selected → `surfaceVariant` background, disabled

### Dismissal
- Drag down, tap scrim, or ✕ → dismiss with no download started
- Back button → same as ✕

---

## WorkManager: `DownloadWorker`

- Class: `CoroutineWorker`
- Constraints: `NetworkType.CONNECTED`
- Input data: `url: String`, `cookiesPath: String`, `selectedIndices: IntArray` (empty = all)
- Runs `PythonBridge.download()` on the worker's coroutine dispatcher
- Updates notification progress after each saved file
- On success: posts completion notification
- On failure: posts failure notification with retry action (re-opens MainActivity with URL)
- Returns `Result.success()` or `Result.failure()`

---

## Notifications

**Channel:** `instadown_downloads`, importance = `IMPORTANCE_DEFAULT`, no vibration for completion.

| State | Title | Body | Actions |
|---|---|---|---|
| Downloading | InstaDown | Downloading N photos… | — (non-dismissable) |
| Complete | InstaDown | N photos saved | Tap → open save folder |
| Failed | InstaDown | Download failed | Tap → open app with URL |

Single image uses "1 photo" (no plural). Notification icon uses the app's download icon.

**Android 13+:** Request `POST_NOTIFICATIONS` permission on first share if not granted. If denied, download still works silently (no notification).

---

## Manifest Changes

```xml
<!-- Remove ACTION_SEND from MainActivity -->

<!-- Add new activity -->
<activity
    android:name=".ShareBottomSheetActivity"
    android:exported="true"
    android:theme="@style/Theme.InstaDown.Transparent"
    android:taskAffinity=""
    android:excludeFromRecents="true">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/*" />
    </intent-filter>
</activity>

<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

`taskAffinity=""` ensures the sheet appears over Instagram rather than launching InstaDown's task stack. `excludeFromRecents="true"` hides it from the recents screen.

---

## Pre-warming gallery-dl

In `InstaDownApp.onCreate()`, after Python starts, launch a background thread that imports gallery-dl:

```kotlin
thread(isDaemon = true) {
    try {
        Python.getInstance().getModule("instadown_android")
            .callAttr("warmup")
    } catch (_: Throwable) {}
}
```

Python side:
```python
def warmup():
    """Pre-import gallery-dl so the first fetch_metadata call is fast."""
    import gallery_dl.extractor
    import gallery_dl.config
```

This amortises the ~1–2s import cost before the user ever shares a post.

---

## Error Cases

| Situation | Behaviour |
|---|---|
| Not signed in | Sheet shows inline error: "Sign in first" with a button that opens SettingsActivity |
| Network error during metadata fetch | Sheet shows "Couldn't load post. Check your connection." with a Retry button |
| Cookies expired (metadata fetch returns auth error) | Sheet shows "Session expired. Sign in again." with Settings button |
| Worker fails mid-download | Failure notification with retry tap action |
| POST_NOTIFICATIONS denied | Worker runs silently, no notification |

---

## Out of Scope

- Paste-URL flow in MainActivity — unchanged
- Video / Reels support — still disabled
- Download queue / history
- Batch sharing multiple URLs at once
