# InstaDown — Android app

A real Android APK that downloads Instagram photos and carousels
straight from the share sheet. Tap **Share → InstaDown** on any
Instagram post and the highest-quality image lands in
`Downloads/InstaDown/`.

## How it works

The app embeds Python (via [Chaquopy](https://chaquo.com/chaquopy/))
and the `gallery-dl` package, so the actual Instagram download logic
is the same battle-tested code used by the desktop CLI. Auth is
handled by an in-app login WebView that captures the session cookies
on first use — no file export, no PC, no Termux.

| Component | What it does |
| --- | --- |
| `MainActivity` | Share-receiver + paste-URL form. Calls Python, shows the image + a **Save** button. |
| `LoginActivity` | In-app WebView that loads `instagram.com/accounts/login/`. On login, cookies are written to a Netscape `cookies.txt` for gallery-dl. |
| `SettingsActivity` | Sign in / sign out / about. |
| `PythonBridge.kt` | Calls `instadown_android.download(url, cookies_path, out_dir)` in the embedded Python and parses the JSON result. |
| `SaveHelper.kt` | Copies the downloaded file from cache to `Downloads/InstaDown/` via MediaStore. |
| `instadown_android.py` | Python entry point. Configures gallery-dl, runs the job, captures file paths. |

## Build it

You need:

- **JDK 17** (not JDK 21+; AGP 8.7 doesn't support it)
- **Android SDK** with `platforms;android-35` and `build-tools;35.0.0`
- **Internet access** for the first build (downloads Python 3.10
  runtime, gallery-dl + dependencies)

A helper script is provided:

```bash
cd android
./build.sh          # writes app/build/outputs/apk/debug/app-debug.apk
```

Or, manually:

```bash
cd android
./gradlew assembleDebug
```

The APK ends up at `app/build/outputs/apk/debug/app-debug.apk`
(~30-40 MB, since the Chaquopy runtime + gallery-dl are bundled).

## Install it

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to your phone (USB, email, cloud), enable
**Install unknown apps** for your file manager, and tap to install.

## First run

1. Open the app.
2. Tap the gear icon → **Sign in to Instagram**.
3. Log in inside the WebView (handle any 2FA / challenge that pops up).
4. Once the page changes away from `/accounts/login/`, the app
   captures your cookies and returns to the main screen.
5. Open Instagram, find a post, tap **Share**, pick **InstaDown**.
6. The image appears — tap **Save** to copy it to
   `Downloads/InstaDown/`.

## File layout

```
android/
├── app/
│   ├── build.gradle.kts             # Chaquopy + Compose deps
│   └── src/main/
│       ├── AndroidManifest.xml      # ACTION_SEND intent filter
│       ├── java/com/instadown/app/  # Kotlin code
│       │   ├── InstaDownApp.kt
│       │   ├── MainActivity.kt
│       │   ├── LoginActivity.kt
│       │   ├── SettingsActivity.kt
│       │   ├── PythonBridge.kt
│       │   ├── SaveHelper.kt
│       │   └── Theme.kt
│       ├── python/
│       │   └── instadown_android.py # Python entry point
│       └── res/                     # icons, strings, theme
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/wrapper/
```

## Limitations (v0.1)

- **Images only.** Reels and videos are intentionally skipped
  (`videos: false` in the gallery-dl config). Add a separate UI
  toggle if you want them later.
- **Throwaway account recommended.** Logging in with your main
  Instagram account is technically supported but increases the risk
  of Instagram flagging the session.
- **No background work.** Downloads happen in the foreground while
  the app is open. Closing the app mid-download cancels it.
- **No retry / queue.** One URL at a time. Past downloads aren't
  listed anywhere.
- **Debug-signed APK.** Don't try to publish to the Play Store
  without setting up a release keystore.

## License

Same as the parent project (MIT).
