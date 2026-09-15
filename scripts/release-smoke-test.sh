#!/usr/bin/env bash
#
# Builds the release APK, installs it on the connected emulator and checks the app is
# still running a few seconds after launch.
#
# Why this exists: isMinifyEnabled and isShrinkResources are both on for release, while
# the instrumented suite runs connectedDebugAndroidTest — the debug, unminified build.
# So R8 and resource shrinking were never exercised by anything, and a missing keep rule
# would surface as a crash on first launch for whoever side-loads the release APK, with
# CI fully green. The committed keystore exists precisely so people install that APK as
# an update, which makes it the build that matters most.
#
# Why a file rather than inline YAML: reactivecircus/android-emulator-runner executes
# its `script:` input one line at a time, each through its own `sh -c`. A multi-line
# `if ... fi` is therefore split into fragments and dies with "Syntax error: end of file
# unexpected" — which is exactly how the first version of this failed.
set -euo pipefail

PKG="com.smartring.app"
APK="app/build/outputs/apk/release/app-release.apk"

echo "── Building the release APK (minified + resource-shrunk) ──"
./gradlew assembleRelease --stacktrace --no-daemon

echo "── Installing $APK ──"
adb logcat -c
adb install -r "$APK"

echo "── Launching $PKG/.MainActivity ──"
adb shell am start -W -n "$PKG/$PKG.MainActivity"

# Long enough for Application.onCreate (Hilt graph, Room open, WorkManager enqueue) and
# the first composition to have run and thrown, if they were going to.
sleep 12

PID="$(adb shell pidof "$PKG" | tr -d '[:space:]')"
if [ -z "$PID" ]; then
  echo "::error::The minified release APK is not running 12s after launch. R8 or resource shrinking has very likely stripped something needed at startup — add a keep rule to app/proguard-rules.pro based on the crash below."
  echo "── crash buffer ──"
  adb logcat -d -b crash | tail -200
  echo "── last 200 lines mentioning $PKG ──"
  adb logcat -d | grep -F "$PKG" | tail -200 || true
  exit 1
fi

echo "Release APK is alive (pid $PID)."

# Printed even on success: a caught-and-logged startup failure (a runCatching that
# swallowed a missing class, say) leaves the process up but the app broken, and this is
# the only place anyone would see it.
echo "── crash buffer (expected to be empty) ──"
adb logcat -d -b crash | tail -50 || true
