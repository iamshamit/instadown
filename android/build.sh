#!/usr/bin/env bash
# Build the InstaDown Android APK.
#
# Prerequisites (set once):
#   - JDK 17 on PATH (or JAVA_HOME pointing at it)
#   - Android SDK with platforms;android-35 and build-tools;35.0.0
#   - local.properties pointing at the SDK (auto-created if SDK
#     lives in $HOME/android-dev/sdk)
#
# Output: app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail

cd "$(dirname "$0")"

# Sane defaults if the env vars aren't set.
: "${JAVA_HOME:=$HOME/android-dev/jdk/jdk17}"
: "${ANDROID_HOME:=$HOME/android-dev/sdk}"
export JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

if [ ! -f local.properties ]; then
    echo "sdk.dir=$ANDROID_HOME" > local.properties
    echo "wrote local.properties (sdk.dir=$ANDROID_HOME)"
fi

if ! command -v java >/dev/null 2>&1; then
    echo "error: java not on PATH. Set JAVA_HOME to a JDK 17 install." >&2
    exit 1
fi

java_version="$(java -version 2>&1 | head -1 | awk -F\" '{print $2}')"
java_major="$(echo "$java_version" | cut -d. -f1)"
if [ "$java_major" -ne 17 ]; then
    echo "warning: java is $java_version, but AGP 8.7 needs 17." >&2
fi

echo "==> JAVA_HOME=$JAVA_HOME"
echo "==> ANDROID_HOME=$ANDROID_HOME"
echo "==> building debug APK..."

./gradlew --no-daemon assembleDebug "$@"

APK=app/build/outputs/apk/debug/app-debug.apk
if [ -f "$APK" ]; then
    size=$(du -h "$APK" | cut -f1)
    echo "==> built $APK ($size)"
    echo "==> install with: adb install -r $APK"
else
    echo "error: APK not found at $APK" >&2
    exit 1
fi
