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
# -b all: `logcat -c` alone does not reliably clear the crash buffer, and a stale
# crash from the instrumented run that preceded this would be indistinguishable from
# a real one.
adb logcat -b all -c || true
adb install -r "$APK"

echo "── Launching $PKG/.MainActivity ──"
adb shell am start -W -n "$PKG/$PKG.MainActivity"

# Long enough for Application.onCreate (Hilt graph, Room open, WorkManager enqueue) and
# the first composition to have run and thrown, if they were going to.
sleep 12

# The crash buffer is the real check, not pidof. Android restarts a process that dies
# on launch, so pidof finds the *replacement* and reports a healthy app — which is
# exactly what happened the first time this script ran: the release APK crashed during
# its first composition, the process came back with a new pid, and the run went green.
#
# "Process: com.smartring.app," with the trailing comma is deliberate: the debug build
# installed by the instrumented suite is com.smartring.app.debug, and a substring match
# would blame this on that.
CRASH="$(adb logcat -d -b crash 2>/dev/null || true)"
if printf '%s' "$CRASH" | grep -q "Process: $PKG,"; then
  echo "::error::The minified release APK crashed on launch. R8 or resource shrinking has stripped something the app needs — add a keep rule to app/proguard-rules.pro based on the trace below."
  echo "── crash buffer (head: the exception and its cause are at the TOP) ──"
  printf '%s\n' "$CRASH" | head -120
  exit 1
fi

PID="$(adb shell pidof "$PKG" | tr -d '[:space:]')"
if [ -z "$PID" ]; then
  echo "::error::The minified release APK is not running 12s after launch, with nothing in the crash buffer — it may have been killed rather than have thrown."
  adb logcat -d | grep -F "$PKG" | tail -120 || true
  exit 1
fi

echo "Release APK launched, still alive (pid $PID), and nothing in the crash buffer."
