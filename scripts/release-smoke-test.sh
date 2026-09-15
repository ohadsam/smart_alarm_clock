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

# The pid as launched. Everything below compares against this, because "a process with
# this package name exists" is not the same claim as "the process we started is the one
# still running" — see the pid-identity check at the bottom.
FIRST_PID="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '[:space:]' || true)"
echo "Launched as pid ${FIRST_PID:-<not captured>}"

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

# Pid identity, not just pid existence. The crash-buffer check above is the primary
# signal, but it is only as good as the buffer: on an emulator image where `logcat -b
# crash` comes back empty for any reason, this script would silently fall back to the
# bare `pidof` check — which is precisely the vacuous check that let v1.6.7 go green
# with a fatal exception in the log. A process that died and was restarted by Android
# gets a *new* pid, so comparing the two catches that case with no dependence on the
# crash buffer at all.
if [ -n "$FIRST_PID" ] && [ "$PID" != "$FIRST_PID" ]; then
  echo "::error::The minified release APK restarted during the 12s after launch (pid $FIRST_PID -> $PID). Android respawns a process that dies on startup, so this is a crash even though the crash buffer did not show one."
  adb logcat -d | grep -F "$PKG" | tail -120 || true
  exit 1
fi

# ── Beyond MainActivity ──────────────────────────────────────────────────────
# Launching the activity proves the Hilt graph, Room, Compose and DataStore survived
# R8. It proves nothing about the three subsystems that make this an alarm clock
# rather than a screen, and each of them is reached through a name-based lookup that
# R8 can break exactly as it broke the LocalLifecycleOwner bridge:
#
#   - the Glance widget receivers      (kept by `-keep class * extends GlanceAppWidgetReceiver`)
#   - the @HiltWorker workers          (kept by `-keep @androidx.hilt.work.HiltWorker`)
#   - the foreground service           (the alarm actually ringing)
#
# These cannot be driven from `adb shell`: every receiver and the service are
# android:exported="false", so a broadcast from the shell uid is refused. What *can*
# be checked from outside is whether the platform still sees them in the minified,
# resource-shrunk APK — which is precisely what stripping or renaming would destroy.

# Two independent probes, because neither is portable on its own: `dumpsys appwidget`
# formats its provider list differently across releases (and on some images lists only
# *bound* instances, of which a freshly installed app has none), while `dumpsys package`
# resolves receivers by their real class names. Failing only when BOTH come back empty
# keeps this a test of the APK rather than a test of a particular emulator image's
# dumpsys formatting.
echo "── Widget receivers visible to the platform ──"
APPWIDGET_HITS="$(adb shell dumpsys appwidget 2>/dev/null | grep -c "$PKG/" || true)"
PACKAGE_HITS="$(adb shell dumpsys package "$PKG" 2>/dev/null | grep -c "SmartRingWidget" || true)"
echo "dumpsys appwidget entries for $PKG: ${APPWIDGET_HITS:-0}"
echo "dumpsys package receivers named SmartRingWidget*: ${PACKAGE_HITS:-0}"
if [ "${APPWIDGET_HITS:-0}" -lt 1 ] && [ "${PACKAGE_HITS:-0}" -lt 1 ]; then
  echo "::error::Neither dumpsys appwidget nor dumpsys package can see a widget receiver for $PKG in the minified APK. R8 or resource shrinking has stripped or renamed the Glance receivers — the widgets would be missing from the picker entirely."
  echo "── dumpsys appwidget ──"
  adb shell dumpsys appwidget 2>/dev/null | head -40 || true
  echo "── dumpsys package (receivers) ──"
  adb shell dumpsys package "$PKG" 2>/dev/null | grep -i -A2 "receiver" | head -40 || true
  exit 1
fi

# WorkManager schedules through JobScheduler on API 23+. An entry here means the
# app got far enough through its own startup to build a worker request, which is the
# @HiltWorker + WorkManager Configuration.Provider path — the one that silently stops
# the boot reschedule, the log cleanup and the widget refresh when a keep rule is
# missing. Reported rather than enforced: the app legitimately enqueues on its own
# schedule, and a hard failure here would be a flake generator.
echo "── WorkManager jobs registered by the platform ──"
JOBS="$(adb shell dumpsys jobscheduler 2>/dev/null | grep -c "$PKG" || true)"
echo "JobScheduler entries mentioning $PKG: ${JOBS:-0} (informational)"

echo "Release APK launched, still alive (pid $PID, unchanged since launch), nothing in the crash buffer, widget providers visible to the platform."
