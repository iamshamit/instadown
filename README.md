# InstaDown

Download Instagram photos and carousels directly to your Android phone — no PC, no Termux, no third-party website.

Tap **Share → InstaDown** on any post, or paste a link inside the app. The highest-quality image lands in your gallery.

---

## Screenshots

| Main (OLED) | Settings |
|---|---|
| ![Main screen](.github/screenshots/main_oled.png) | ![Settings screen](.github/screenshots/settings_oled.png) |

---

## How it works

InstaDown embeds a full Python interpreter ([Chaquopy](https://chaquo.com/chaquopy/)) and the battle-tested [`gallery-dl`](https://github.com/mikf/gallery-dl) library inside the APK. There is no server — everything runs on your device.

**Auth flow:** An in-app WebView loads `instagram.com`. You log in normally (including 2FA). On a successful login the app captures your session cookies from the WebView and writes them to a private `cookies.txt` file that `gallery-dl` can read. The cookies never leave your phone.

**Download flow:**

```
Share intent / paste URL
        │
        ▼
    MainActivity
        │  passes URL + cookies path
        ▼
    PythonBridge  ──────────────────────────▶  instadown_android.py
        │                                           │
        │◀────── JSON result {ok, files[]} ─────────┤
        │                                    gallery-dl fetches
        ▼                                    from Instagram
    SaveHelper
        │  copies from app cache
        ▼
    Downloads/InstaDown/   (or your chosen folder)
```

---

## Features

- **Share-sheet integration** — appears in the Android share menu for any Instagram URL
- **Paste a link** — works without leaving the app
- **Carousels** — downloads all images in a multi-photo post in one tap
- **Custom save folder** — choose any folder via the system file picker (SAF)
- **OLED & Light themes** — true black OLED and a soft purple light theme
- **No account registration** — uses your own Instagram session
- **No network calls home** — all processing is local

---

## Requirements

- Android 7.0 (API 24) or newer
- An Instagram account

---

## Installation

### Option A — Download the APK (easiest)

1. Go to the [Releases](https://github.com/iamshamit/instadown/releases) page.
2. Download `app-release.apk` from the latest release.
3. On your phone: **Settings → Apps → Install unknown apps** → allow your browser or file manager.
4. Open the downloaded APK and tap **Install**.

### Option B — Build from source

**Prerequisites:**

| Tool | Version |
|------|---------|
| JDK | 17 or 21 (JDK 26 requires Gradle 8.14+) |
| Android SDK | API 35, build-tools 35.0.0 |
| Python | 3.10 (for Chaquopy build step) |

```bash
git clone https://github.com/iamshamit/instadown.git
cd instadown/android

# Point to your Python 3.10 (if not on PATH)
# Edit chaquopy { defaultConfig { buildPython("...") } } in app/build.gradle.kts

./gradlew assembleRelease
# APK → android/app/build/outputs/apk/release/app-release.apk

adb install -r app/build/outputs/apk/release/app-release.apk
```

> **Note:** The first build downloads Python 3.10, `gallery-dl`, and its dependencies (~30 MB). Subsequent builds are fast.

---

## First run

1. Open **InstaDown**.
2. Tap the **gear icon** (top right) → **Sign in to Instagram**.
3. Log in with your credentials inside the WebView. Complete any 2FA challenge.
4. Once Instagram redirects away from the login page the app auto-captures your cookies and returns you to the main screen. You'll see "Signed in" in Settings.
5. Open Instagram, find a post, tap **Share** → **InstaDown**.
6. The photo(s) appear — tap the **Save** button or wait for the auto-save notification.

> **Tip:** Long-pressing the paste FAB (clipboard icon, bottom right) on the main screen pastes a copied Instagram URL directly.

---

## Settings

| Setting | Description |
|---------|-------------|
| **Account** | Shows sign-in status. Tap "Sign out & clear cookies" to revoke the session. |
| **Color theme** | Light (soft purple) or OLED Black (true black for OLED displays). |
| **Save location** | Defaults to `Downloads/InstaDown`. Tap **Change** to pick any folder via the system file picker. Tap **Reset** to go back to the default. |
| **About** | App version, description, and a link to this repository. |

---

## Project structure

```
instadown/
├── android/                        # The Android app
│   └── app/src/main/
│       ├── java/com/instadown/app/
│       │   ├── MainActivity.kt     # Share-receiver + paste-URL UI
│       │   ├── LoginActivity.kt    # In-app WebView login
│       │   ├── SettingsActivity.kt # Theme, storage, account, about
│       │   ├── PythonBridge.kt     # Kotlin ↔ Python bridge (Chaquopy)
│       │   ├── SaveHelper.kt       # MediaStore / SAF file saving
│       │   ├── Theme.kt            # Material 3 color tokens + ThemePrefs
│       │   └── InstaDownApp.kt     # Application class
│       └── python/
│           └── instadown_android.py  # gallery-dl wrapper (Python)
├── src/instadown/                  # Desktop CLI (Python)
├── web/                            # PWA frontend
├── LICENSE
└── README.md
```

---

## Tech stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose + Material 3 |
| Language | Kotlin 2.1 |
| Python runtime | Chaquopy 17 (Python 3.10) |
| Downloader | gallery-dl (latest via pip) |
| Theme | Instagram gradient (`#833AB4 → #E1306C → #F77737`) |
| Storage | MediaStore (Android 10+), SAF for custom folders |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 35 (Android 15) |

---

## Limitations

- **Images only.** Reels and videos are skipped (`videos: false` in the gallery-dl config). This is intentional for v0.1.
- **One download at a time.** There is no queue or background service — closing the app mid-download cancels it.
- **No history.** Past downloads are not tracked inside the app (they're in your gallery).
- **Throwaway account recommended.** Automated downloads via a personal account may attract an Instagram challenge. A secondary account reduces risk.
- **APK size ~35 MB.** This is because the Python runtime and gallery-dl are bundled inside the APK.

---

## Privacy

- No analytics, no telemetry, no crash reporting.
- Your Instagram cookies are stored only in the app's private files directory (`/data/data/com.instadown.app/files/instagram-cookies.txt`), inaccessible to other apps without root.
- Downloaded files go to your chosen folder (default: `Downloads/InstaDown`).
- No data is sent anywhere except directly to Instagram's servers to perform the download.

---

## License

[MIT](LICENSE) — © 2026 iamshamit
