# InstaDown — Build Context

> Last updated: 2026-06-07, mid-build. This file is the source of truth for the project state.
> Update at end of every work session.

## Goal
Build a real Android APK (InstaDown) that downloads Instagram images at highest quality via share-sheet + in-app WebView auth.

## Constraints & Preferences
- Distribution: sideload (no Play Store).
- Auth UX: in-app WebView login that captures cookies (chosen in response to "Cant we do it like seal does?").
- Save location: `Downloads/InstaDown/`.
- UI scope: Minimal v0.1 (share-receiver + login + settings screens, no theme toggle, no multi-account, no profile archive).
- No image background tasks; foreground only.
- Reels/videos out of scope for v0.1 (`videos: false`).
- Throwaway account strongly recommended (user previously sent personal email `finnicfinfac@gmail.com` and a password — was warned not to use it; password was not used).
- No sudo available on machine; install dev tools to `~/android-dev/`.

## Current Status
**Phase:** Manual test on SM_S931B (Galaxy S25). Login form is rendering. Awaiting throwaway-account credentials from user.

**Build:** SUCCESS — `app/build/outputs/apk/debug/app-debug.apk` (53 MB).

**Resolved:** The `friendPathsSet$kotlin_gradle_plugin_common` state-lock error was fixed by:
- `org.gradle.parallel=false` in `gradle.properties`
- `org.gradle.caching=false` in `gradle.properties`
- `org.gradle.configuration-cache=false` in `gradle.properties`
- Deleted `.gradle/` to clear cached project state
- Stopped daemon with `./gradlew --stop`
- Kotlin 2.1.20 + Compose Compiler plugin 2.1.20 are now in use

**Resolved:** The blank-WebView issue. Instagram's login page was rendering blank in the WebView because:
1. The hardcoded `Chrome/130.0.0.0` UA was too old (Instagram's JS requires Chrome 148+ for the passkey / public-key-credentials code path).
2. The login page rendered blank if visited directly without first priming `csrftoken` / `ig_did` / `mid` cookies via the home page.

Fix in `LoginActivity.kt` (2026-06-07):
- UA: now `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36`
- Load `https://www.instagram.com/` first, then auto-redirect to `/accounts/login/` once the home page finishes loading.
- Added a `LoginState` class to track whether we've reached the login page so we don't accidentally fire cookie-capture too early.
- Added `WebChromeClient` with `onProgressChanged` / `onReceivedTitle` / `onConsoleMessage` for visibility.
- Did NOT enable `setWebContentsDebuggingEnabled(true)` (the "Stop!" console message from Instagram is its own anti-bot warning, but disabling the dev bridge is just safer).
- Form fields (verified via uiautomator dump): username input at `(540, 666)`, password at `(540, 817)`, Log in button at `(540, 987)`.

## Progress
### Done
- Researched Instagram auth reality: no anonymous download possible; all media bytes require authenticated session; gallery-dl 1.32+ removed username/password login.
- Built and tested local Python CLI (`src/instadown/`) wrapping gallery-dl via `_CaptureJob(DownloadJob)` subclass overriding `handle_url`. 4 pytest tests pass.
- **Cleaned up dead `-u/-p` auth (2026-06-07)**: removed username/password CLI flags, `Settings.username/password` from web.py, and `username/password` params from `downloader.download()`. Deduped `_human()` into new `instadown.fmt` module used by both `cli.py` and `web.py`. Tests still pass (4/4).
- Built and tested local FastAPI PWA backend + frontend (`src/instadown/web.py`, `web/`). Verified: `/api/health`, `/`, `/static/manifest.webmanifest`, `/static/sw.js`, `/static/app.js`, 400 for non-IG URL, 401 for IG with no auth. Share-target registered in manifest.
- Installed JDK 17 (Temurin 17.0.13+11) to `~/android-dev/jdk/jdk17`.
- Installed Android cmdline-tools + `platforms;android-35` + `build-tools;35.0.0` + `platform-tools` to `~/android-dev/sdk`.
- Created `~/android-dev/env.sh` with JAVA_HOME / ANDROID_HOME / PATH.
- Scaffolded `android/` project: `app/build.gradle.kts`, `build.gradle.kts` (root), `settings.gradle.kts`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.10.2), `local.properties` (`sdk.dir=/home/finfac/android-dev/sdk`), `build.sh`, `README.md`.
- Wrote Kotlin sources: `InstaDownApp.kt`, `MainActivity.kt` (Compose, share-intent + paste-URL + states), `LoginActivity.kt` (in-app WebView, cookie capture on non-login URL), `SettingsActivity.kt` (sign in/out, confirm dialog), `PythonBridge.kt` (Chaquopy + CookieManager → Netscape `cookies.txt`), `SaveHelper.kt` (MediaStore Q+ + legacy with FileProvider), `Theme.kt` (Material 3 light/dark).
- Wrote `AndroidManifest.xml` with `ACTION_SEND text/plain` intent filter, INTERNET perm, WRITE_EXTERNAL_STORAGE (maxSdkVersion=28), FileProvider authority `${applicationId}.fileprovider`.
- Wrote `app/src/main/python/instadown_android.py` (Python entry point: subclasses `gallery_dl.job.DownloadJob` to capture files; returns JSON dict to Kotlin).
- Wrote resources: `strings.xml`, `themes.xml`, `colors.xml`, `file_paths.xml`, `mipmap-anydpi-v26/ic_launcher.xml` + `_round.xml`, `drawable/ic_launcher_foreground.xml`, generated PNGs at mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi from `web/icon.svg`.
- Fixed Chaquopy DSL: `pip { install("gallery-dl") }` belongs inside `chaquopy.defaultConfig { }` (which is an `Action<PythonExtension>`).
- Installed **Python 3.10.20** via `uv python install 3.10` to `~/.local/share/uv/python/cpython-3.10.20-linux-x86_64-gnu/`. Symlinked to `~/android-dev/bin/python3.10` and prepended to `PATH` in `env.sh` (Chaquopy requires `python3.10` on PATH for `installDebugPythonRequirements`).
- Fixed Kotlin 2.0 Compose Compiler requirement: added `org.jetbrains.kotlin.plugin.compose` plugin (must match Kotlin version: now 2.1.20).
- Regenerated `gradlew` + `gradle-wrapper.jar` via `gradle wrapper --gradle-version 8.10.2`.
- Confirmed first build progresses through: `installDebugPythonRequirements` (charmed; pip install of gallery-dl works), `mergeDebugPythonSources`, `generateDebugPythonProxies`, manifest/asset processing, then **fails at `compileDebugKotlin`** with the state-lock error.

### In Progress
- Manual device install + throwaway-account login test.

### Blocked
- Nothing. APK is built.

## Key Decisions
- **Chaquopy over native Kotlin scraper**: reuses existing `gallery-dl` Python code, battle-tested, no IG API reverse-engineering. APK ~30-40 MB.
- **In-app WebView login (not Chrome cookie extraction)**: Chrome cookie DB is encrypted + scoped-storage-restricted on Android 11+; WebView is reliable.
- **abandon `-u/-p` CLI flags** in `src/instadown/cli.py`: gallery-dl 1.32 dropped password login. **DONE 2026-06-07** — removed from CLI, downloader API, and web.Settings.
- **Save to `Downloads/InstaDown/`** via MediaStore (Q+) and FileProvider (`${applicationId}.fileprovider`) on legacy.
- **No background work, no retry/queue, debug-signed APK**: v0.1 scope.
- **Reels skipped** in Python entry point (`videos: false`, `format: jpg:best`).

## Next Steps
1. **Manual test** with a real Instagram URL (throwaway account required):
   - `adb install android/app/build/outputs/apk/debug/app-debug.apk`
   - Open app → tap "Sign in" → log in with throwaway account.
   - Verify cookies file is non-empty in app's `filesDir`.
   - Open Instagram in a browser → copy a post URL → share to InstaDown.
   - Confirm file lands in `Downloads/InstaDown/` and is the highest-quality JPG.
2. **Cleanup**: ~~remove dead `-u/-p` code from `src/instadown/cli.py` and verify no circular imports in `src/instadown/web.py`~~ DONE 2026-06-07.
3. **Optional**: try re-enabling `org.gradle.parallel=true` to see if newer Kotlin/AGP versions handle it (or leave it off for stability).

## Build Environment
- `JAVA_HOME=~/android-dev/jdk/jdk17` (Temurin 17.0.13+11)
- `ANDROID_HOME=~/android-dev/sdk`
- `PATH` includes `~/android-dev/bin` (where `python3.10` symlink lives)
- Source env: `source ~/android-dev/env.sh`
- Wrapper: `./gradlew` (Gradle 8.10.2-bin)
- `gradle` 8.10.2 also at `/tmp/gradle-8.10.2/bin/gradle` (used for `gradle wrapper`)

## Gradle Versions (Pinned)
- AGP: `8.7.3`
- Kotlin: `2.1.20` (was 2.0.21; bumped trying to fix state-lock, didn't help)
- Compose Compiler plugin: `2.1.20` (matches Kotlin)
- Chaquopy: `17.0.0`
- Compose BOM: `2024.10.01`
- Coil: `2.7.0`

## Project Config
- `applicationId=com.instadown.app`
- `minSdk=24`, `targetSdk=35`, `compileSdk=35`
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`
- Python version: `3.10`
- Python deps: `gallery-dl`
- `usesCleartextTraffic="false"` (IG is HTTPS)
- WebView login success detection: URL not containing `/accounts/login`, `/accounts/onetap`, or `/challenge/`
- CookieManager cookies written in Netscape format to `filesDir/instagram-cookies.txt`; gallery-dl reads via `("extractor", "instagram")` config `cookies` key.

## Critical Context
- `ChaquopyExtension` (PythonDsl.kt source): only `defaultConfig`, `productFlavors`, `sourceSets` are DSL fields. `defaultConfig` is an `Action<PythonExtension>`-accepting function. **So `pip` must be inside `defaultConfig { }`.**
- `_CaptureJob` Python class: rebinds `handle_url` on the inner gallery-dl `DownloadJob` instance via `types.MethodType` before calling `run()`.
- `SaveHelper.saveToDownloads` returns the MediaStore `Uri` (Q+) or FileProvider `Uri` (legacy).
- Old `instadown_password` flow is dead — gallery-dl 1.32+ ignores username/password config keys; we did NOT add this to the Android side.
- Known Chaquopy risk: bundling `gallery-dl` on Android is not heavily production-tested; Python 3.10 + requests/urllib3 are pure-Python so should work, but first build may surface SSL/cert store issues.
- Chaquopy `installDebugPythonRequirements` task needs `python3.10` (or the version pinned) on PATH. With Chaquopy 17, default Python is 3.10, and it must match exactly. We have it via uv symlink.

## Relevant Files
- `/home/finfac/Code/InstaDown/pyproject.toml`: gallery-dl + FastAPI/uvicorn deps.
- `/home/finfac/Code/InstaDown/src/instadown/cli.py`: argparse CLI; `serve` and `setup` subcommands; `-u/-p` removed 2026-06-07.
- `/home/finfac/Code/InstaDown/src/instadown/downloader.py`: `_CaptureJob` subclass for gallery-dl; `_apply_config`; `Result` dataclass.
- `/home/finfac/Code/InstaDown/src/instadown/web.py`: FastAPI app, `Settings.from_env()` reads `INSTADOWN_COOKIES` (no more INSTAGRAM_USERNAME/PASSWORD as of 2026-06-07); serves `web/` as static.
- `/home/finfac/Code/InstaDown/web/index.html`, `app.js`, `sw.js`, `manifest.webmanifest`, `style.css`, `icon.svg`, `icon-192.png`, `icon-512.png`: PWA frontend with `share_target`.
- `/home/finfac/Code/InstaDown/tests/test_downloader.py`: 4 passing unit tests.
- `/home/finfac/Code/InstaDown/android/build.gradle.kts` (root): AGP 8.7.3, Kotlin 2.1.20, Chaquopy 17.0.0, Compose plugin 2.1.20.
- `/home/finfac/Code/InstaDown/android/app/build.gradle.kts`: app config, Compose BOM 2024.10.01, Coil 2.7.0, Chaquopy block with `pip` inside `defaultConfig`.
- `/home/finfac/Code/InstaDown/android/app/src/main/AndroidManifest.xml`: intent filter for `ACTION_SEND text/plain`; permissions.
- `/home/finfac/Code/InstaDown/android/app/src/main/java/com/instadown/app/MainActivity.kt`: share-receiver + paste-URL + UiState (Idle/Working/Ready/Error).
- `/home/finfac/Code/InstaDown/android/app/src/main/java/com/instadown/app/LoginActivity.kt`: WebView login + cookie capture.
- `/home/finfac/Code/InstaDown/android/app/src/main/java/com/instadown/app/SettingsActivity.kt`: sign in/out, confirm dialog.
- `/home/finfac/Code/InstaDown/android/app/src/main/java/com/instadown/app/PythonBridge.kt`: Chaquopy interop, `writeWebViewCookiesToFile()` writes Netscape cookies.
- `/home/finfac/Code/InstaDown/android/app/src/main/java/com/instadown/app/SaveHelper.kt`: MediaStore + FileProvider save paths.
- `/home/finfac/Code/InstaDown/android/app/src/main/java/com/instadown/app/Theme.kt`, `InstaDownApp.kt`: Material 3 theme + Application class.
- `/home/finfac/Code/InstaDown/android/app/src/main/python/instadown_android.py`: Python entry, `_CaptureJob` rebinds `handle_url` via `types.MethodType`.
- `/home/finfac/Code/InstaDown/android/app/src/main/res/{values,drawable,xml,mipmap-*}/`: strings, theme, colors, file_paths, launcher icons.
- `/home/finfac/Code/InstaDown/android/build.sh`: helper that sets env vars and runs `./gradlew assembleDebug`.
- `/home/finfac/Code/InstaDown/android/README.md`: build + install + first-run docs.
- `/home/finfac/Code/InstaDown/android/local.properties`: `sdk.dir=/home/finfac/android-dev/sdk`.
- `/home/finfac/Code/InstaDown/android/gradle.properties`: `org.gradle.jvmargs=-Xmx2048m` etc.
- `/home/finfac/android-dev/env.sh`: exports JAVA_HOME, ANDROID_HOME, ANDROID_SDK_ROOT, PATH (incl. `~/android-dev/bin` for python3.10).
- `/home/finfac/Code/InstaDown/CONTEXT.md`: this file.

## Open Questions
- Does `AndroidManifest.xml` set `android:exported="true"` on `MainActivity` and the share intent-filter category? Need to verify before first device install.
- Does the WebView user agent match what gallery-dl sends? If not, IG might serve a stripped-down mobile site that lacks the `download_url` field.
- Does gallery-dl's `instagram` extractor need any extra config besides `cookies`? (e.g. `user-agent`, `directory`.)
- Is the `_CaptureJob.handle_url` rebind actually working, or does gallery-dl's pipeline call a different method? Should add a `print` to `instadown_android.py` and run it via `chaquopy` to verify.

## Test Plan (Once APK Exists)
1. `adb install app-debug.apk`
2. Open app → tap "Sign in" → log in with throwaway account.
3. Verify cookies file is non-empty in app's `filesDir`.
4. Open Instagram in a browser → copy a post URL → share to InstaDown.
5. Confirm file lands in `Downloads/InstaDown/` and is the highest-quality JPG.

## Things to Try / Look Out For
- `gallery-dl` on Android sometimes has SSL handshake issues with Python's bundled `certifi`; may need to set `ssl_no_verify=True` in `_apply_config` (NOT recommended for prod; only for debugging).
- `CookieManager` cookies may include the `HttpOnly` ones (which is what we need for `sessionid`).
- `MediaStore.Images.Media.RELATIVE_PATH` requires API 29+; on minSdk 24 we must use `Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)` + FileProvider.
- Chaquopy's `pip` task caches wheels in `~/.gradle/caches/chaquopy/`. Re-running the build should be fast.
