#!/usr/bin/env bash
# Build the debug APK and install it on the connected Android device,
# then force-stop and relaunch the app fresh.
#
# Usage: ./build_and_install.sh
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/home/perry/.jdks/jbr-21.0.11}"
export PATH="/home/perry/Software/android/platform-tools:$PATH"

if ! adb devices -l | grep -q "device$\|device "; then
    echo "No Android device found via adb. Plug it in (or check Wireless debugging) and try again." >&2
    exit 1
fi

echo "==> Building debug APK..."
./gradlew assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
echo "==> Installing $APK..."
adb install -r "$APK"

echo "==> Relaunching app..."
adb shell am force-stop com.perryhertler.cragmap
adb shell monkey -p com.perryhertler.cragmap -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

echo "==> Done."
